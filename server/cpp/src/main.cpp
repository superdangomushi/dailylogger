// AIHelper public HTTP transport.
//
// The C++ process owns the public socket and proxies byte-for-byte HTTP
// requests to the existing Node/Express application on loopback.  Keeping the
// application behind this narrow transport boundary preserves all existing
// routes, authentication rules, generated dashboard HTML, and integrations.

#include "httplib.h"

#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

using namespace std::chrono_literals;

constexpr const char *kClientIpHeader = "X-AIHelper-Gateway-Client-IP";
constexpr const char *kNoAcceptRangesMarker =
    "X-AIHelper-Internal-No-Accept-Ranges";
constexpr const char *kNoContentTypeMarker =
    "X-AIHelper-Internal-No-Content-Type";
constexpr const char *kNoContentLengthMarker =
    "X-AIHelper-Internal-No-Content-Length";

volatile std::sig_atomic_t g_signal = 0;

void signal_handler(int signal) { g_signal = signal; }

std::string ascii_lower(std::string value) {
  for (char &c : value) {
    if (c >= 'A' && c <= 'Z') { c = static_cast<char>(c - 'A' + 'a'); }
  }
  return value;
}

std::string trim(std::string value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) { return {}; }
  const auto last = value.find_last_not_of(" \t\r\n");
  return value.substr(first, last - first + 1);
}

bool valid_env_name(const std::string &name) {
  if (name.empty() ||
      !(name.front() == '_' ||
        (name.front() >= 'A' && name.front() <= 'Z') ||
        (name.front() >= 'a' && name.front() <= 'z'))) {
    return false;
  }
  for (const char c : name) {
    if (!(c == '_' || (c >= 'A' && c <= 'Z') ||
          (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
      return false;
    }
  }
  return true;
}

void load_dot_env(const std::filesystem::path &path) {
  std::ifstream input(path);
  if (!input) { return; }

  std::string line;
  while (std::getline(input, line)) {
    const auto stripped = trim(line);
    if (stripped.empty() || stripped.front() == '#') { continue; }
    const auto equals = line.find('=');
    if (equals == std::string::npos) { continue; }
    const auto key = trim(line.substr(0, equals));
    if (!valid_env_name(key) || std::getenv(key.c_str()) != nullptr) {
      continue;
    }
    auto value = trim(line.substr(equals + 1));
    if (value.size() >= 2 &&
        ((value.front() == '"' && value.back() == '"') ||
         (value.front() == '\'' && value.back() == '\''))) {
      value = value.substr(1, value.size() - 2);
    }
    ::setenv(key.c_str(), value.c_str(), 0);
  }
  std::cout << ".env を読み込みました" << std::endl;
}

std::string env_string(const char *name, const std::string &fallback = {}) {
  const char *value = std::getenv(name);
  return value == nullptr || *value == '\0' ? fallback : std::string(value);
}

bool env_flag(const char *name, bool fallback = false) {
  const char *value = std::getenv(name);
  if (value == nullptr) { return fallback; }
  const auto lowered = ascii_lower(trim(value));
  return lowered == "1" || lowered == "true" || lowered == "yes" ||
         lowered == "on";
}

long env_long(const char *name, long fallback, long minimum, long maximum) {
  const char *value = std::getenv(name);
  if (value == nullptr || *value == '\0') { return fallback; }
  char *end = nullptr;
  errno = 0;
  const long parsed = std::strtol(value, &end, 10);
  if (errno != 0 || end == value || *end != '\0' || parsed < minimum ||
      parsed > maximum) {
    std::cerr << name << " が不正なため既定値 " << fallback
              << " を使用します: " << value << std::endl;
    return fallback;
  }
  return parsed;
}

std::filesystem::path executable_directory(const char *argv0) {
  std::vector<char> buffer(4096);
  const auto length = ::readlink("/proc/self/exe", buffer.data(),
                                 buffer.size() - 1);
  if (length > 0) {
    buffer[static_cast<size_t>(length)] = '\0';
    return std::filesystem::path(buffer.data()).parent_path();
  }
  std::error_code error;
  auto fallback = std::filesystem::absolute(argv0, error);
  return error ? std::filesystem::current_path() : fallback.parent_path();
}

class TemporaryFile {
public:
  static std::shared_ptr<TemporaryFile> create() {
    std::string pattern = "/tmp/aihelper-http-body-XXXXXX";
    std::vector<char> name(pattern.begin(), pattern.end());
    name.push_back('\0');
    const int fd = ::mkstemp(name.data());
    if (fd < 0) {
      throw std::runtime_error(std::string("一時ファイルを作成できません: ") +
                               std::strerror(errno));
    }
    const int flags = ::fcntl(fd, F_GETFD);
    if (flags >= 0) { ::fcntl(fd, F_SETFD, flags | FD_CLOEXEC); }
    std::string file_path(name.data());
    // Keep only the open descriptor. This also guarantees cleanup if the
    // gateway is killed while a large upload is being forwarded.
    if (::unlink(file_path.c_str()) == 0) { file_path.clear(); }
    return std::shared_ptr<TemporaryFile>(
        new TemporaryFile(fd, std::move(file_path)));
  }

  ~TemporaryFile() {
    if (fd_ >= 0) { ::close(fd_); }
    if (!path_.empty()) { ::unlink(path_.c_str()); }
  }

  TemporaryFile(const TemporaryFile &) = delete;
  TemporaryFile &operator=(const TemporaryFile &) = delete;

  bool append(const char *data, size_t length) {
    size_t written = 0;
    while (written < length) {
      const auto result = ::write(fd_, data + written, length - written);
      if (result < 0 && errno == EINTR) { continue; }
      if (result <= 0) { return false; }
      written += static_cast<size_t>(result);
    }
    if (length > std::numeric_limits<size_t>::max() - size_) { return false; }
    size_ += length;
    return true;
  }

  ssize_t read_at(size_t offset, char *data, size_t length) const {
    for (;;) {
      const auto result =
          ::pread(fd_, data, length, static_cast<off_t>(offset));
      if (result < 0 && errno == EINTR) { continue; }
      return result;
    }
  }

  size_t size() const { return size_; }

private:
  TemporaryFile(int fd, std::string path)
      : fd_(fd), path_(std::move(path)) {}

  int fd_ = -1;
  std::string path_;
  size_t size_ = 0;
};

std::unordered_set<std::string>
connection_tokens(const httplib::Headers &headers) {
  std::unordered_set<std::string> result;
  const auto range = headers.equal_range("Connection");
  for (auto it = range.first; it != range.second; ++it) {
    std::string_view value(it->second);
    size_t start = 0;
    while (start <= value.size()) {
      const auto comma = value.find(',', start);
      const auto end = comma == std::string_view::npos ? value.size() : comma;
      const auto token = trim(std::string(value.substr(start, end - start)));
      if (!token.empty()) { result.insert(ascii_lower(token)); }
      if (comma == std::string_view::npos) { break; }
      start = comma + 1;
    }
  }
  return result;
}

bool is_hop_by_hop(const std::string &name,
                   const std::unordered_set<std::string> &tokens) {
  const auto lower = ascii_lower(name);
  static const std::unordered_set<std::string> fixed{
      "connection",          "keep-alive",        "proxy-authenticate",
      "proxy-authorization", "proxy-connection",  "te",
      "trailer",             "transfer-encoding", "upgrade"};
  return fixed.count(lower) != 0 || tokens.count(lower) != 0;
}

bool is_internal_header(const std::string &name) {
  const auto lower = ascii_lower(name);
  return lower == ascii_lower(kClientIpHeader) ||
         lower == ascii_lower(kNoAcceptRangesMarker) ||
         lower == ascii_lower(kNoContentTypeMarker) ||
         lower == ascii_lower(kNoContentLengthMarker) ||
         lower == "remote_addr" || lower == "remote_port" ||
         lower == "local_addr" || lower == "local_port";
}

std::string first_forwarded_address(const httplib::Request &request) {
  const auto forwarded = request.get_header_value("X-Forwarded-For");
  const auto comma = forwarded.find(',');
  return trim(forwarded.substr(0, comma));
}

struct ProxyState {
  std::mutex mutex;
  std::condition_variable changed;
  std::deque<std::string> chunks;
  size_t queued_bytes = 0;
  size_t max_queued_bytes = 4 * 1024 * 1024;
  bool headers_ready = false;
  bool done = false;
  bool failed = false;
  bool cancelled = false;
  int status = -1;
  httplib::Headers headers;
  std::string error;
  std::shared_ptr<httplib::Client> client;
  std::thread worker;
  std::atomic<bool> finalized{false};
};

void publish_headers(const std::shared_ptr<ProxyState> &state,
                     const httplib::Response &response) {
  {
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->headers_ready) { return; }
    state->status = response.status;
    state->headers = response.headers;
    state->headers_ready = true;
  }
  state->changed.notify_all();
}

bool enqueue_response_data(const std::shared_ptr<ProxyState> &state,
                           const char *data, size_t length) {
  std::unique_lock<std::mutex> lock(state->mutex);
  state->changed.wait(lock, [&] {
    return state->cancelled ||
           state->queued_bytes < state->max_queued_bytes;
  });
  if (state->cancelled) { return false; }
  state->chunks.emplace_back(data, length);
  state->queued_bytes += length;
  lock.unlock();
  state->changed.notify_all();
  return true;
}

bool write_next_response_chunk(const std::shared_ptr<ProxyState> &state,
                               httplib::DataSink &sink, bool chunked) {
  std::string data;
  {
    std::unique_lock<std::mutex> lock(state->mutex);
    state->changed.wait(lock, [&] {
      return state->cancelled || !state->chunks.empty() || state->done;
    });
    if (state->cancelled) { return false; }
    if (!state->chunks.empty()) {
      data = std::move(state->chunks.front());
      state->chunks.pop_front();
      state->queued_bytes -= data.size();
    } else {
      if (state->failed) { return false; }
      if (chunked) { sink.done(); }
      return chunked;
    }
  }
  state->changed.notify_all();
  if (!sink.write(data.data(), data.size())) {
    {
      std::lock_guard<std::mutex> lock(state->mutex);
      state->cancelled = true;
    }
    state->changed.notify_all();
    return false;
  }
  return true;
}

void finish_proxy(const std::shared_ptr<ProxyState> &state) {
  if (state->finalized.exchange(true)) { return; }
  bool must_cancel = false;
  {
    std::lock_guard<std::mutex> lock(state->mutex);
    must_cancel = !state->done;
    if (must_cancel) { state->cancelled = true; }
  }
  state->changed.notify_all();
  if (must_cancel && state->client) { state->client->stop(); }
  if (state->worker.joinable()) { state->worker.join(); }
}

std::optional<size_t> response_content_length(
    const httplib::Headers &headers) {
  const auto it = headers.find("Content-Length");
  if (it == headers.end() || it->second.empty()) { return std::nullopt; }
  char *end = nullptr;
  errno = 0;
  const auto parsed = std::strtoull(it->second.c_str(), &end, 10);
  if (errno != 0 || end == it->second.c_str() || *end != '\0' ||
      parsed > std::numeric_limits<size_t>::max()) {
    return std::nullopt;
  }
  return static_cast<size_t>(parsed);
}

bool status_has_body(const std::string &method, int status) {
  return method != "HEAD" && status >= 200 && status != 204 && status != 304;
}

size_t request_body_limit(const httplib::Request &request,
                          size_t global_limit) {
  auto media_type = ascii_lower(request.get_header_value("Content-Type"));
  const auto semicolon = media_type.find(';');
  if (semicolon != std::string::npos) { media_type.erase(semicolon); }
  media_type = trim(std::move(media_type));
  constexpr size_t mebibyte = 1024 * 1024;
  if (media_type == "application/json" || media_type == "text/plain") {
    return std::min(global_limit, 10 * mebibyte);
  }
  if (media_type == "application/pdf" ||
      media_type == "application/octet-stream") {
    return std::min(global_limit, 25 * mebibyte);
  }
  if (media_type.rfind("audio/", 0) == 0) {
    return std::min(global_limit, 300 * mebibyte);
  }
  return global_limit;
}

struct GatewayConfig {
  std::string backend_host = "127.0.0.1";
  int backend_port = 0;
  long connect_timeout_sec = 5;
  long backend_read_timeout_sec = 3600;
  long backend_write_timeout_sec = 3600;
  size_t queue_bytes = 4 * 1024 * 1024;
  bool trust_proxy = false;
};

class HttpGateway {
public:
  explicit HttpGateway(GatewayConfig config) : config_(std::move(config)) {}

  void proxy(const httplib::Request &incoming, httplib::Response &outgoing,
             const std::shared_ptr<TemporaryFile> &body) const {
    if (stopping_.load()) {
      outgoing.status = httplib::StatusCode::ServiceUnavailable_503;
      outgoing.set_content("Server is shutting down\n",
                           "text/plain; charset=utf-8");
      return;
    }
    // cpp-httplib parses Range before routing and would otherwise apply it a
    // second time after Node has already produced its 206 response.
    const_cast<httplib::Request &>(incoming).ranges.clear();

    httplib::Request upstream;
    upstream.method = incoming.method;
    upstream.path = incoming.target.empty() ? incoming.path : incoming.target;

    const auto request_tokens = connection_tokens(incoming.headers);
    for (const auto &header : incoming.headers) {
      const auto lower = ascii_lower(header.first);
      if (is_hop_by_hop(header.first, request_tokens) ||
          is_internal_header(header.first) || lower == "content-length" ||
          lower == "expect") {
        continue;
      }
      upstream.headers.emplace(header.first, header.second);
    }

    auto client_ip = incoming.remote_addr;
    if (config_.trust_proxy) {
      const auto forwarded = first_forwarded_address(incoming);
      if (!forwarded.empty()) { client_ip = forwarded; }
    }
    upstream.headers.emplace(kClientIpHeader,
                             client_ip.empty() ? "unknown" : client_ip);

    const bool incoming_body_framed =
        incoming.has_header("Content-Length") ||
        incoming.has_header("Transfer-Encoding");
    if (body && body->size() > 0) {
      upstream.content_length_ = body->size();
      upstream.is_chunked_content_provider_ = false;
      upstream.content_provider_ =
          [body](size_t offset, size_t length,
                 httplib::DataSink &sink) -> bool {
        const size_t requested = std::min<size_t>(length, 64 * 1024);
        std::vector<char> buffer(requested);
        const auto count = body->read_at(offset, buffer.data(), buffer.size());
        if (count <= 0) { return false; }
        return sink.write(buffer.data(), static_cast<size_t>(count));
      };
    } else if (incoming_body_framed) {
      // Preserve the distinction between an explicitly empty entity and a
      // request with no body framing at all.
      upstream.set_header("Content-Length", "0");
    }

    auto state = std::make_shared<ProxyState>();
    state->max_queued_bytes = config_.queue_bytes;
    state->client =
        std::make_shared<httplib::Client>(config_.backend_host,
                                          config_.backend_port);
    state->client->set_connection_timeout(config_.connect_timeout_sec, 0);
    state->client->set_read_timeout(config_.backend_read_timeout_sec, 0);
    state->client->set_write_timeout(config_.backend_write_timeout_sec, 0);
    state->client->set_follow_location(false);
    state->client->set_decompress(false);
    state->client->set_keep_alive(false);
    state->client->set_tcp_nodelay(true);
    state->client->set_url_encode(false);

    upstream.response_handler = [state](const httplib::Response &response) {
      publish_headers(state, response);
      std::lock_guard<std::mutex> lock(state->mutex);
      return !state->cancelled;
    };
    upstream.content_receiver =
        [state](const char *data, size_t length, uint64_t, uint64_t) {
          return enqueue_response_data(state, data, length);
        };

    if (!track(state)) {
      outgoing.status = httplib::StatusCode::ServiceUnavailable_503;
      outgoing.set_content("Server is shutting down\n",
                           "text/plain; charset=utf-8");
      return;
    }
    state->worker = std::thread(
        [state, upstream = std::move(upstream)]() mutable {
          {
            std::lock_guard<std::mutex> lock(state->mutex);
            if (state->cancelled) {
              state->done = true;
              state->failed = true;
              state->error = "shutdown";
              state->changed.notify_all();
              return;
            }
          }
          httplib::Response response;
          httplib::Error error = httplib::Error::Success;
          const bool ok = state->client->send(upstream, response, error);
          if (response.status >= 0) { publish_headers(state, response); }
          {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->done = true;
            state->failed = !ok;
            if (!ok) { state->error = httplib::to_string(error); }
          }
          state->changed.notify_all();
        });

    int upstream_status = -1;
    httplib::Headers upstream_headers;
    {
      std::unique_lock<std::mutex> lock(state->mutex);
      state->changed.wait(
          lock, [&] { return state->headers_ready || state->done; });
      if (state->headers_ready) {
        upstream_status = state->status;
        upstream_headers = state->headers;
      }
    }

    if (upstream_status < 0) {
      std::string reason;
      {
        std::lock_guard<std::mutex> lock(state->mutex);
        reason = state->error;
      }
      finish_proxy(state);
      std::cerr << "Node backend への接続に失敗しました"
                << (reason.empty() ? "" : ": " + reason) << std::endl;
      outgoing.status = httplib::StatusCode::BadGateway_502;
      outgoing.set_content("Node backend is unavailable\n", "text/plain; charset=utf-8");
      return;
    }

    outgoing.status = upstream_status;
    const bool has_body = status_has_body(incoming.method, upstream_status);
    const auto content_length = response_content_length(upstream_headers);
    const bool had_content_type =
        upstream_headers.find("Content-Type") != upstream_headers.end();
    const bool had_accept_ranges =
        upstream_headers.find("Accept-Ranges") != upstream_headers.end();
    const bool had_content_length =
        upstream_headers.find("Content-Length") != upstream_headers.end();
    const auto response_tokens = connection_tokens(upstream_headers);

    for (const auto &header : upstream_headers) {
      const auto lower = ascii_lower(header.first);
      if (is_hop_by_hop(header.first, response_tokens) ||
          is_internal_header(header.first) ||
          (has_body && lower == "content-length")) {
        continue;
      }
      outgoing.headers.emplace(header.first, header.second);
    }

    if (incoming.method == "HEAD" && !had_accept_ranges) {
      outgoing.headers.emplace(kNoAcceptRangesMarker, "1");
    }
    if (has_body && !had_content_type) {
      outgoing.headers.emplace(kNoContentTypeMarker, "1");
    }
    if (!has_body && !had_content_length) {
      outgoing.headers.emplace(kNoContentLengthMarker, "1");
    }

    if (has_body && content_length && *content_length == 0) {
      outgoing.headers.emplace("Content-Length", "0");
    } else if (has_body) {
      outgoing.content_provider_ =
          [state, chunked = !content_length](size_t, size_t,
                                              httplib::DataSink &sink) {
            return write_next_response_chunk(state, sink, chunked);
          };
      outgoing.is_chunked_content_provider_ = !content_length;
      outgoing.content_length_ = content_length.value_or(0);
    }

    outgoing.content_provider_resource_releaser_ =
        [state](bool) { finish_proxy(state); };
  }

  void stop_all() const {
    stopping_.store(true);
    std::vector<std::shared_ptr<ProxyState>> active;
    {
      std::lock_guard<std::mutex> lock(active_mutex_);
      for (const auto &entry : active_) {
        if (auto state = entry.lock()) { active.push_back(std::move(state)); }
      }
      active_.clear();
    }
    for (const auto &state : active) {
      {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->cancelled = true;
      }
      state->changed.notify_all();
      if (state->client) { state->client->stop(); }
    }
  }

private:
  bool track(const std::shared_ptr<ProxyState> &state) const {
    std::lock_guard<std::mutex> lock(active_mutex_);
    if (stopping_.load()) { return false; }
    auto output = active_.begin();
    for (auto input = active_.begin(); input != active_.end(); ++input) {
      if (!input->expired()) { *output++ = *input; }
    }
    active_.erase(output, active_.end());
    active_.push_back(state);
    return true;
  }

  GatewayConfig config_;
  mutable std::mutex active_mutex_;
  mutable std::vector<std::weak_ptr<ProxyState>> active_;
  mutable std::atomic<bool> stopping_{false};
};

void spool_and_proxy(const HttpGateway &gateway,
                     const httplib::Request &request,
                     httplib::Response &response,
                     const httplib::ContentReader &reader, size_t body_limit,
                     long body_timeout_sec) {
  // Clear early so a local disk/read error cannot be transformed by Range.
  const_cast<httplib::Request &>(request).ranges.clear();
  const auto close_connection = [&] {
    auto &mutable_request = const_cast<httplib::Request &>(request);
    mutable_request.headers.erase("Connection");
    mutable_request.set_header("Connection", "close");
  };
  if (request.has_header("Content-Length") &&
      request.get_header_value_u64("Content-Length") > body_limit) {
    close_connection();
    response.status = httplib::StatusCode::PayloadTooLarge_413;
    return;
  }

  std::shared_ptr<TemporaryFile> body;
  try {
    body = TemporaryFile::create();
  } catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    response.status = httplib::StatusCode::InternalServerError_500;
    response.set_content("Request buffering failed\n",
                         "text/plain; charset=utf-8");
    return;
  }

  bool write_failed = false;
  bool too_large = false;
  bool timed_out = false;
  const auto deadline = std::chrono::steady_clock::now() +
                        std::chrono::seconds(body_timeout_sec);
  bool read_ok = false;
  {
#ifdef CPPHTTPLIB_ABSOLUTE_READ_DEADLINE
    httplib::detail::scoped_read_deadline absolute_body_deadline(deadline);
#endif
    read_ok = reader([&](const char *data, size_t length) {
      if (std::chrono::steady_clock::now() > deadline) {
        timed_out = true;
        return false;
      }
      if (length > body_limit - body->size()) {
        too_large = true;
        return false;
      }
      if (!body->append(data, length)) {
        write_failed = true;
        return false;
      }
      return true;
    });
#ifdef CPPHTTPLIB_ABSOLUTE_READ_DEADLINE
    if (!read_ok && httplib::detail::absolute_read_deadline_expired()) {
      timed_out = true;
    }
#endif
  }
  if (!read_ok) {
    close_connection();
    if (too_large || response.status == httplib::StatusCode::PayloadTooLarge_413) {
      response.status = httplib::StatusCode::PayloadTooLarge_413;
    } else if (timed_out) {
      response.status = httplib::StatusCode::RequestTimeout_408;
    } else if (write_failed) {
      response.status = httplib::StatusCode::InternalServerError_500;
      response.set_content("Request buffering failed\n",
                           "text/plain; charset=utf-8");
    } else if (response.status < 0) {
      response.status = httplib::StatusCode::BadRequest_400;
    }
    return;
  }
  gateway.proxy(request, response, body);
}

int find_free_loopback_port() {
  const int socket_fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (socket_fd < 0) { return -1; }
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = 0;
  if (::bind(socket_fd, reinterpret_cast<sockaddr *>(&address),
             sizeof(address)) != 0) {
    ::close(socket_fd);
    return -1;
  }
  socklen_t length = sizeof(address);
  if (::getsockname(socket_fd, reinterpret_cast<sockaddr *>(&address),
                    &length) != 0) {
    ::close(socket_fd);
    return -1;
  }
  const int port = ntohs(address.sin_port);
  ::close(socket_fd);
  return port;
}

bool loopback_port_ready(int port) {
  const int socket_fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (socket_fd < 0) { return false; }
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  address.sin_port = htons(static_cast<uint16_t>(port));
  const bool ready =
      ::connect(socket_fd, reinterpret_cast<sockaddr *>(&address),
                sizeof(address)) == 0;
  ::close(socket_fd);
  return ready;
}

pid_t spawn_node(int backend_port) {
  const pid_t expected_parent = ::getpid();
  const pid_t child = ::fork();
  if (child != 0) {
    if (child > 0) { ::setpgid(child, child); }
    return child;
  }

  ::setpgid(0, 0);
  // If the C++ supervisor is killed, ask the managed Node process to stop
  // instead of leaving a second server and its timers behind.
  ::prctl(PR_SET_PDEATHSIG, SIGTERM);
  if (::getppid() != expected_parent) { ::_exit(1); }

  const auto port = std::to_string(backend_port);
  ::setenv("PORT", port.c_str(), 1);
  ::setenv("AIHELPER_NODE_HOST", "127.0.0.1", 1);
  ::setenv("AIHELPER_CPP_GATEWAY", "1", 1);
  const auto node = env_string("NODE_BINARY", "node");
  const auto entry = env_string("AIHELPER_NODE_ENTRY", "server.js");
  ::execlp(node.c_str(), node.c_str(), entry.c_str(),
           static_cast<char *>(nullptr));
  std::cerr << "Node.js を起動できません: " << std::strerror(errno)
            << std::endl;
  ::_exit(127);
}

bool wait_for_backend(int port, pid_t child, long timeout_ms,
                      bool &child_reaped, int &child_status) {
  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
  while (std::chrono::steady_clock::now() < deadline) {
    if (g_signal != 0) { return false; }
    if (loopback_port_ready(port)) { return true; }
    if (child > 0) {
      const pid_t result = ::waitpid(child, &child_status, WNOHANG);
      if (result == child) {
        child_reaped = true;
        return false;
      }
    }
    std::this_thread::sleep_for(50ms);
  }
  return false;
}

void terminate_child(pid_t child, bool already_reaped) {
  if (child <= 0 || already_reaped) { return; }
  if (::kill(-child, SIGTERM) != 0) { ::kill(child, SIGTERM); }
  int status = 0;
  for (int attempt = 0; attempt < 100; ++attempt) {
    const pid_t result = ::waitpid(child, &status, WNOHANG);
    if (result == child || (result < 0 && errno == ECHILD)) { return; }
    std::this_thread::sleep_for(50ms);
  }
  if (::kill(-child, SIGKILL) != 0) { ::kill(child, SIGKILL); }
  while (::waitpid(child, &status, 0) < 0 && errno == EINTR) {}
}

} // namespace

int main(int argc, char **argv) {
  (void)argc;
  try {
    const auto configured_server_dir = env_string("AIHELPER_SERVER_DIR");
    const auto server_dir = configured_server_dir.empty()
                                ? executable_directory(argv[0])
                                : std::filesystem::path(configured_server_dir);
    if (::chdir(server_dir.c_str()) != 0) {
      std::cerr << "サーバーディレクトリへ移動できません: "
                << server_dir << ": " << std::strerror(errno) << std::endl;
      return 1;
    }
    load_dot_env(server_dir / ".env");

    const int public_port = static_cast<int>(
        env_long("PORT", 3000, 1, std::numeric_limits<uint16_t>::max()));
    // Node's host-omitted listener is dual-stack on Ubuntu. Binding the IPv6
    // wildcard keeps both existing IPv6 and IPv4 clients reachable.
    const auto public_host = env_string("AIHELPER_HTTP_HOST", "::");
    const bool no_spawn = env_flag("AIHELPER_GATEWAY_NO_SPAWN");

    int backend_port = static_cast<int>(
        env_long("AIHELPER_NODE_PORT", 0, 0,
                 std::numeric_limits<uint16_t>::max()));
    if (backend_port == 0) { backend_port = find_free_loopback_port(); }
    if (backend_port <= 0 || backend_port == public_port) {
      std::cerr << "Node backend 用の内部ポートを確保できません" << std::endl;
      return 1;
    }

    GatewayConfig gateway_config;
    gateway_config.backend_port = backend_port;
    gateway_config.connect_timeout_sec =
        env_long("AIHELPER_NODE_CONNECT_TIMEOUT_SEC", 5, 1, 3600);
    gateway_config.backend_read_timeout_sec =
        env_long("AIHELPER_NODE_READ_TIMEOUT_SEC", 3600, 1, 86400);
    gateway_config.backend_write_timeout_sec =
        env_long("AIHELPER_NODE_WRITE_TIMEOUT_SEC", 3600, 1, 86400);
    gateway_config.queue_bytes = static_cast<size_t>(
        env_long("AIHELPER_HTTP_QUEUE_BYTES", 4 * 1024 * 1024, 64 * 1024,
                 256 * 1024 * 1024));
    gateway_config.trust_proxy = env_flag("TRUST_PROXY");
    HttpGateway gateway(gateway_config);

    httplib::Server server;
    server.set_keep_alive_timeout(5);
    server.set_keep_alive_max_count(1000);
#ifdef CPPHTTPLIB_ABSOLUTE_READ_DEADLINE
    server.set_header_read_timeout(
        env_long("AIHELPER_HTTP_HEADER_TIMEOUT_SEC", 60, 1, 3600), 0);
#endif
    server.set_read_timeout(
        env_long("AIHELPER_HTTP_READ_TIMEOUT_SEC", 60, 1, 86400), 0);
    server.set_write_timeout(
        env_long("AIHELPER_HTTP_WRITE_TIMEOUT_SEC", 3600, 1, 86400), 0);
    const auto global_body_limit = static_cast<size_t>(env_long(
        "AIHELPER_HTTP_MAX_BODY_BYTES", 300L * 1024 * 1024, 1,
        2L * 1024 * 1024 * 1024));
    const auto body_timeout_sec =
        env_long("AIHELPER_HTTP_BODY_TIMEOUT_SEC", 300, 1, 86400);
    server.set_payload_max_length(global_body_limit);
    server.set_tcp_nodelay(true);
    const auto thread_count = static_cast<size_t>(
        env_long("AIHELPER_HTTP_THREADS", 32, 2, 512));
    const auto max_queued_requests = static_cast<size_t>(
        env_long("AIHELPER_HTTP_MAX_QUEUED_REQUESTS", 256, 1, 8192));
    server.new_task_queue = [thread_count, max_queued_requests] {
      return new httplib::ThreadPool(thread_count, max_queued_requests);
    };

    server.set_header_writer([](httplib::Stream &stream,
                                httplib::Headers &headers) {
      if (headers.find(kNoAcceptRangesMarker) != headers.end()) {
        headers.erase("Accept-Ranges");
        headers.erase(kNoAcceptRangesMarker);
      }
      if (headers.find(kNoContentTypeMarker) != headers.end()) {
        headers.erase("Content-Type");
        headers.erase(kNoContentTypeMarker);
      }
      if (headers.find(kNoContentLengthMarker) != headers.end()) {
        headers.erase("Content-Length");
        headers.erase(kNoContentLengthMarker);
      }
      return httplib::detail::write_headers(stream, headers);
    });

    server.set_pre_routing_handler(
        [](const httplib::Request &request, httplib::Response &response) {
          static const std::set<std::string> supported{
              "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};
          if (supported.count(request.method) != 0) {
            return httplib::Server::HandlerResponse::Unhandled;
          }
          auto &mutable_request = const_cast<httplib::Request &>(request);
          mutable_request.headers.erase("Connection");
          mutable_request.set_header("Connection", "close");
          response.status = httplib::StatusCode::BadRequest_400;
          return httplib::Server::HandlerResponse::Handled;
        });

    const auto body_handler =
        [&gateway, global_body_limit,
         body_timeout_sec](const httplib::Request &request,
                           httplib::Response &response,
                           const httplib::ContentReader &reader) {
          spool_and_proxy(gateway, request, response, reader,
                          request_body_limit(request, global_body_limit),
                          body_timeout_sec);
        };
    server.Post(R"(/.*)", body_handler);
    server.Put(R"(/.*)", body_handler);
    server.Patch(R"(/.*)", body_handler);
    server.Delete(R"(/.*)", body_handler);
    server.Get(R"(/.*)", body_handler);
    server.Options(R"(/.*)", body_handler);
    const auto bodyless_handler =
        [&gateway](const httplib::Request &request,
                   httplib::Response &response) {
          gateway.proxy(request, response, nullptr);
        };
    server.Get(R"(/.*)", bodyless_handler);
    server.Options(R"(/.*)", bodyless_handler);

    std::signal(SIGINT, signal_handler);
    std::signal(SIGTERM, signal_handler);

    if (!server.bind_to_port(public_host, public_port)) {
      std::cerr << "公開HTTPポートを開けません: " << public_host << ':'
                << public_port << std::endl;
      return 1;
    }

    pid_t child = -1;
    bool child_reaped = false;
    int child_status = 0;
    if (!no_spawn) {
      child = spawn_node(backend_port);
      if (child < 0) {
        std::cerr << "Node.js プロセスを作成できません: "
                  << std::strerror(errno) << std::endl;
        return 1;
      }
    }

    const long start_timeout =
        env_long("AIHELPER_NODE_START_TIMEOUT_MS", 30000, 100, 300000);
    if (!wait_for_backend(backend_port, child, start_timeout, child_reaped,
                          child_status)) {
      if (g_signal != 0) {
        terminate_child(child, child_reaped);
        return 0;
      }
      std::cerr << "Node backend が起動しませんでした (127.0.0.1:"
                << backend_port << ')' << std::endl;
      terminate_child(child, child_reaped);
      return 1;
    }

    const auto display_host = public_host.find(':') == std::string::npos
                                  ? public_host
                                  : '[' + public_host + ']';
    std::cout << "C++ HTTP gateway listening on http://" << display_host << ':'
              << public_port << std::endl;
    std::cout << "Node backend: http://127.0.0.1:" << backend_port
              << (no_spawn ? " (external)" : " (managed)") << std::endl;

    std::atomic<bool> stop_monitor{false};
    std::atomic<bool> child_exited{false};
    std::thread monitor([&] {
      while (!stop_monitor.load()) {
        if (g_signal != 0) {
          server.stop();
          gateway.stop_all();
          return;
        }
        if (child > 0 && !child_reaped) {
          const pid_t result = ::waitpid(child, &child_status, WNOHANG);
          if (result == child) {
            child_reaped = true;
            child_exited.store(true);
            ::kill(-child, SIGTERM);
            std::cerr << "Node backend が終了したためゲートウェイを停止します"
                      << std::endl;
            server.stop();
            gateway.stop_all();
            return;
          }
        }
        std::this_thread::sleep_for(50ms);
      }
    });

    const bool listen_ok = server.listen_after_bind();
    gateway.stop_all();
    stop_monitor.store(true);
    monitor.join();
    terminate_child(child, child_reaped);

    if (!listen_ok && g_signal == 0 && !child_exited.load()) {
      std::cerr << "HTTPゲートウェイが異常終了しました" << std::endl;
      return 1;
    }
    return child_exited.load() && g_signal == 0 ? 1 : 0;
  } catch (const std::exception &error) {
    std::cerr << "HTTPゲートウェイの初期化に失敗しました: " << error.what()
              << std::endl;
    return 1;
  }
}
