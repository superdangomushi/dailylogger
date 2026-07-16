// 公開サーバーとの通信（すべて POST + JSON ボディ）。
//
// 毎リクエストに認証情報（auth.email / auth.token）と clientId（UUID）を含める。
// 音声のダウンロードも「JSONリクエスト → バイナリ応答」で、自分が claim した
// ジョブ以外は取得できない（なりすまし・取違防止）。
#pragma once

#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>

#include "httplib.h"
#include "json.hpp"
#include "config.hpp"
#include "util.hpp"

using nlohmann::json;

// サーバーがエラーに付ける code（unregistered / uuid_conflict 等）を運ぶ例外。
struct ApiError : std::runtime_error {
  std::string code;
  explicit ApiError(const std::string& message, std::string c = "")
      : std::runtime_error(message), code(std::move(c)) {}
};

namespace api {

struct BaseUrl {
  std::string origin;       // scheme://host[:port]（httplib::Client に渡す）
  std::string path_prefix;  // 例: /aihelper（無ければ空）
};

inline BaseUrl parse_base_url(const std::string& base) {
  BaseUrl out;
  const auto scheme_end = base.find("://");
  const size_t host_start = scheme_end == std::string::npos ? 0 : scheme_end + 3;
  const auto slash = base.find('/', host_start);
  if (slash == std::string::npos) {
    out.origin = base;
  } else {
    out.origin = base.substr(0, slash);
    out.path_prefix = base.substr(slash);
    while (!out.path_prefix.empty() && out.path_prefix.back() == '/') out.path_prefix.pop_back();
  }
  return out;
}

inline std::string current_base_url() {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  return clean_base_url(g_config.base_url);
}

// httplib のエラーenumを、接続先が分かる読みやすい文字列にする
// （JS版 describeError 相当。ECONNREFUSED 等の切り分け用）。
inline std::string describe_httplib_error(httplib::Error err, const std::string& origin) {
  return httplib::to_string(err) + " (" + origin + ")";
}

inline std::unique_ptr<httplib::Client> make_client(const BaseUrl& b, time_t read_timeout_sec) {
  auto cli = std::make_unique<httplib::Client>(b.origin);
  cli->set_connection_timeout(10, 0);
  cli->set_read_timeout(read_timeout_sec, 0);
  cli->set_write_timeout(60, 0);
  // fetch と違い httplib は既定でリダイレクトを追わないが、意図を明示しておく。
  cli->set_follow_location(false);
  return cli;
}

inline bool is_redirect(int status) {
  return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
}

// リダイレクト検出（POSTがGETに化ける事故の防止）。http→https 転送のある
// プロキシ配下で起きがちなので、設定すべきURLを伝えて止める。
inline void throw_if_redirect(const httplib::Response& res, const std::string& base) {
  if (!is_redirect(res.status)) return;
  std::string loc = res.get_header_value("Location");
  if (loc.empty()) loc = "(不明)";
  throw ApiError(
      "公開サーバーURL " + base + " はリダイレクトされています (HTTP " +
      std::to_string(res.status) + " → " + loc +
      ")。POSTがGETに変わって失敗するため、設定の公開サーバーURLをリダイレクト先に合わせてください");
}

// レスポンス本文（JSONのはず）からエラーメッセージと code を取り出して投げる。
inline void throw_api_error(int status, const std::string& body) {
  std::string message;
  std::string code;
  try {
    const json j = json::parse(body);
    message = jstr(j, "error");
    code = jstr(j, "code");
  } catch (...) {
  }
  if (message.empty()) message = !body.empty() ? body : ("HTTP " + std::to_string(status));
  throw ApiError(message, code);
}

// すべてのAPIリクエストのJSONボディに載せる共通部分: 認証情報とこのPCのID。
inline json auth_body(const Account& account, json extra = json::object()) {
  extra["auth"] = {{"email", account.email}, {"token", account.token}};
  extra["clientId"] = account.client_id.empty() ? json(nullptr) : json(account.client_id);
  return extra;
}

// JSON API 呼び出し。成功（HTTP 2xx かつ ok:true）以外は ApiError を投げる。
inline json post_json(const Account& account, const std::string& pathname, json body = json::object()) {
  const std::string base = current_base_url();
  const BaseUrl b = parse_base_url(base);
  auto cli = make_client(b, 120);
  const std::string payload = auth_body(account, std::move(body)).dump();
  auto res = cli->Post(b.path_prefix + pathname, payload, "application/json");
  if (!res) throw ApiError(describe_httplib_error(res.error(), b.origin));
  throw_if_redirect(*res, base);
  json parsed;
  try {
    parsed = json::parse(res->body);
  } catch (...) {
    parsed = json::object();
  }
  if (res->status < 200 || res->status >= 300 || !jbool(parsed, "ok", false)) {
    throw_api_error(res->status, res->body);
  }
  return parsed;
}

// ログイン。パスワードはこの一度だけ使い、保存しない。
inline std::pair<std::string, std::string> login_with_password(const std::string& email,
                                                               const std::string& password) {
  const std::string base = current_base_url();
  const BaseUrl b = parse_base_url(base);
  auto cli = make_client(b, 60);
  const json payload = {{"email", email}, {"password", password}};
  auto res = cli->Post(b.path_prefix + "/api/login", payload.dump(), "application/json");
  if (!res) {
    throw ApiError(base + " に接続できません: " + describe_httplib_error(res.error(), b.origin));
  }
  throw_if_redirect(*res, base);
  json parsed;
  try {
    parsed = json::parse(res->body);
  } catch (...) {
    parsed = json::object();
  }
  if (res->status < 200 || res->status >= 300 || !jbool(parsed, "ok", false) ||
      jstr(parsed, "token").empty()) {
    throw_api_error(res->status, res->body);
  }
  return {jstr(parsed, "email", email), jstr(parsed, "token")};
}

// 音声本体の取得。認証情報+clientId+jobId をJSONで送り、WAV等のバイナリを受け取り
// file_path へストリーム保存する。
inline void download_job_file(const Account& account, long job_id, const std::string& file_path) {
  const std::string base = current_base_url();
  const BaseUrl b = parse_base_url(base);
  auto cli = make_client(b, 600);
  const std::string payload = auth_body(account, {{"jobId", job_id}}).dump();

  std::ofstream out(file_path, std::ios::binary | std::ios::trunc);
  if (!out) throw ApiError("音声ファイルを書き込めません: " + file_path);
  std::string error_body;
  int status = 0;
  // Post には受信ストリーミング用のオーバーロードが無いため、Request を組んで
  // send() し、content_receiver でファイルへ直接書き込む（大きなWAVをメモリに
  // 貯めないため）。
  httplib::Request req;
  req.method = "POST";
  req.path = b.path_prefix + "/api/client/jobs/download";
  req.set_header("Accept", "application/octet-stream");
  req.set_header("Content-Type", "application/json");
  req.body = payload;
  req.response_handler = [&](const httplib::Response& response) {
    status = response.status;
    return true;
  };
  req.content_receiver = [&](const char* data, size_t len, uint64_t /*offset*/,
                             uint64_t /*total*/) {
    if (status >= 200 && status < 300) {
      out.write(data, static_cast<std::streamsize>(len));
      return out.good();
    }
    // エラー応答はJSONのはずなので本文を控えておく。
    if (error_body.size() < 4096) error_body.append(data, len);
    return true;
  };
  auto res = cli->send(req);
  out.close();
  if (!res) throw ApiError(describe_httplib_error(res.error(), b.origin));
  throw_if_redirect(*res, base);
  if (status < 200 || status >= 300) {
    std::string message;
    try {
      message = jstr(json::parse(error_body), "error");
    } catch (...) {
    }
    if (message.empty()) {
      message = !error_body.empty() ? error_body : ("音声ダウンロード失敗: HTTP " + std::to_string(status));
    }
    throw ApiError(message);
  }
}

}  // namespace api
