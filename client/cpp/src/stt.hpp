// ローカル文字起こし: stt/transcribe.py（faster-whisper / Python）を子プロセスで実行する。
// 音声認識そのものは従来どおり Python 側に任せ、C++ 側は起動と入出力だけを受け持つ。
#pragma once

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

#include "util.hpp"

namespace stt {

inline std::string g_stt_dir;  // main() が実行ファイル位置から設定する（<base>/stt）

// ローカル文字起こしに使う python。WHISPER_PYTHON で上書きでき、
// 既定は stt/.venv（make stt-deps が作る）の python。
inline std::string stt_python() {
  const std::string env = util::env_or("WHISPER_PYTHON", "");
  if (!env.empty()) return env;
  const std::string venv = g_stt_dir + "/.venv/bin/python3";
  struct stat st{};
  return (::stat(venv.c_str(), &st) == 0) ? venv : "";
}

// アカウントの音声認識クオリティ → transcribe.py への環境変数上書き。
// high は従来どおりの自動判定（GPU なら large-v3 の精度最優先）。
inline std::map<std::string, std::string> quality_env(const std::string& quality) {
  if (quality == "light") {
    return {{"WHISPER_MODEL", "small"},
            {"WHISPER_BEAM_SIZE", "5"},
            {"WHISPER_BEST_OF", "5"},
            {"WHISPER_PATIENCE", "1.0"}};
  }
  if (quality == "standard") return {{"WHISPER_MODEL", "large-v3-turbo"}};
  return {};
}

// 長時間録音対策のタイムアウト（2時間）。
constexpr int kTranscribeTimeoutSec = 2 * 3600;

// transcribe.py を実行し、stdout の本文を返す。失敗時は stderr の末尾を添えて投げる。
inline std::string local_transcribe(const std::string& file_path, const std::string& quality) {
  const std::string py = stt_python();
  if (py.empty()) {
    throw std::runtime_error("ローカル文字起こしが未設定です（`make stt-deps` を実行してください）");
  }

  int out_pipe[2];
  int err_pipe[2];
  if (::pipe(out_pipe) != 0 || ::pipe(err_pipe) != 0) {
    throw std::runtime_error("文字起こしを起動できません: pipe に失敗");
  }

  const std::string script = g_stt_dir + "/transcribe.py";
  const pid_t pid = ::fork();
  if (pid < 0) {
    for (int fd : {out_pipe[0], out_pipe[1], err_pipe[0], err_pipe[1]}) ::close(fd);
    throw std::runtime_error("文字起こしを起動できません: fork に失敗");
  }
  if (pid == 0) {
    ::dup2(out_pipe[1], STDOUT_FILENO);
    ::dup2(err_pipe[1], STDERR_FILENO);
    for (int fd : {out_pipe[0], out_pipe[1], err_pipe[0], err_pipe[1]}) ::close(fd);
    for (const auto& [k, v] : quality_env(quality)) ::setenv(k.c_str(), v.c_str(), 1);
    ::execlp(py.c_str(), py.c_str(), script.c_str(), file_path.c_str(), nullptr);
    std::fprintf(stderr, "exec failed: %s: %s\n", py.c_str(), std::strerror(errno));
    ::_exit(127);
  }

  ::close(out_pipe[1]);
  ::close(err_pipe[1]);

  std::string out;
  std::string err;
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(kTranscribeTimeoutSec);
  bool timed_out = false;

  struct pollfd fds[2] = {{out_pipe[0], POLLIN, 0}, {err_pipe[0], POLLIN, 0}};
  int open_fds = 2;
  char buf[8192];
  while (open_fds > 0) {
    if (std::chrono::steady_clock::now() >= deadline) {
      timed_out = true;
      ::kill(pid, SIGKILL);
      break;
    }
    const int rc = ::poll(fds, 2, 1000);
    if (rc < 0) {
      if (errno == EINTR) continue;
      break;
    }
    for (int i = 0; i < 2; i++) {
      if (fds[i].fd < 0 || !(fds[i].revents & (POLLIN | POLLHUP))) continue;
      const ssize_t n = ::read(fds[i].fd, buf, sizeof(buf));
      if (n > 0) {
        if (i == 0) {
          out.append(buf, static_cast<size_t>(n));
        } else {
          err.append(buf, static_cast<size_t>(n));
          if (err.size() > 2000) err.erase(0, err.size() - 2000);  // 末尾2000文字だけ保持
        }
      } else {
        ::close(fds[i].fd);
        fds[i].fd = -1;
        open_fds--;
      }
    }
  }
  for (auto& fd : fds) {
    if (fd.fd >= 0) ::close(fd.fd);
  }

  int wstatus = 0;
  ::waitpid(pid, &wstatus, 0);

  if (timed_out) {
    throw std::runtime_error("文字起こし失敗 (タイムアウト: 2時間を超過)");
  }
  const int code = WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : 128 + WTERMSIG(wstatus);
  if (code == 0) return util::trim(out);

  // stderr の末尾2行をエラーメッセージへ（JS版と同じ体裁）。
  std::vector<std::string> lines;
  std::string line;
  for (char c : util::trim(err)) {
    if (c == '\n') {
      if (!util::trim(line).empty()) lines.push_back(util::trim(line));
      line.clear();
    } else {
      line.push_back(c);
    }
  }
  if (!util::trim(line).empty()) lines.push_back(util::trim(line));
  std::string tail;
  const size_t start = lines.size() > 2 ? lines.size() - 2 : 0;
  for (size_t i = start; i < lines.size(); i++) {
    if (!tail.empty()) tail += " / ";
    tail += lines[i];
  }
  throw std::runtime_error("文字起こし失敗 (exit " + std::to_string(code) + ")" +
                           (tail.empty() ? "" : ": " + tail));
}

}  // namespace stt
