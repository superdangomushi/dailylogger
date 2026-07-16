// 公開サーバー上の音声ジョブを、このPCで処理する外部ワーカー（C++版）。
//
// このプロセスは同時にローカル管理UIも起動する:
//   http://127.0.0.1:39123
//
// 起動後の最初のフェーズは「クライアント登録（アカウント作成）」:
//   1. UIで公開サーバーURL・このPCの表示名を決め、メール+パスワードでログイン
//   2. クライアントがこのPC用の ID（UUID）を自動生成し、表示名とともに
//      POST /api/client/register でサーバーに登録する
//   3. 登録が済んだアカウントだけがジョブのポーリングを開始する
//
// 以後のサーバーとのやりとりはすべて JSON ボディで行い、毎リクエストに
// 認証情報（auth.email / auth.token）と clientId（UUID）を含める。
// 音声のダウンロードも「認証情報+clientId+jobId のJSONリクエスト → WAV応答」で、
// 自分が claim したジョブ以外は取得できない（なりすまし・取違防止）。
// パスワードはログイン時に一度使うだけで保存しない。
//
// 音声認識そのもの（faster-whisper）は従来どおり Python（stt/transcribe.py）が担い、
// このバイナリは通信・管理UI・プロセス管理だけを受け持つ。

#include "httplib.h"

#include <limits.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cctype>
#include <csignal>
#include <cstdio>
#include <thread>

#include "config.hpp"
#include "stt.hpp"
#include "ui.hpp"
#include "worker.hpp"

namespace {

volatile sig_atomic_t g_signal_count = 0;

void on_signal(int) {
  g_signal_count++;
  if (g_signal_count >= 2) ::_exit(0);
}

// 実行ファイルのあるディレクトリ（accounts.json / stt / worker-audio の既定位置）。
std::string executable_dir() {
  char buf[PATH_MAX];
  const ssize_t n = ::readlink("/proc/self/exe", buf, sizeof(buf) - 1);
  if (n <= 0) return ".";
  buf[n] = '\0';
  const std::string p(buf);
  const auto pos = p.find_last_of('/');
  return pos == std::string::npos ? "." : p.substr(0, pos);
}

void mkdir_p(const std::string& dir) {
  std::string cur;
  for (size_t i = 0; i < dir.size(); i++) {
    cur.push_back(dir[i]);
    if ((dir[i] == '/' && i > 0) || i + 1 == dir.size()) {
      ::mkdir(cur.c_str(), 0755);  // 既存ならEEXISTで素通り
    }
  }
}

long env_interval_ms(const char* name, long default_sec) {
  const std::string v = util::env_or(name, "");
  long sec = default_sec;
  if (!v.empty()) {
    try {
      sec = std::stol(v);
    } catch (...) {
      sec = default_sec;
    }
  }
  return std::max(sec, 1L) * 1000;
}

// 既定の表示名に使うホスト名（ASCII以外は落とす）。
std::string host_label() {
  char name[256] = {0};
  ::gethostname(name, sizeof(name) - 1);
  std::string out;
  for (const char* p = name; *p; p++) {
    if (*p >= 0x20 && *p <= 0x7E) out.push_back(*p);
  }
  return util::clip_utf8(util::trim(out), 100);
}

}  // namespace

int main() {
  // ログはリダイレクト先でも行単位で即時に出す（サービス運用時のログ欠落防止）。
  ::setvbuf(stdout, nullptr, _IOLBF, 0);
  ::setvbuf(stderr, nullptr, _IONBF, 0);

  const std::string base_dir = executable_dir();

  g_default_base_url = clean_base_url(
      util::env_or("AIHELPER_SERVER_URL", util::env_or("SERVER_URL", "http://localhost:3000")));
  // clean_base_url は空文字時に g_default_base_url へフォールバックするため、先に確定させる。
  if (g_default_base_url.empty()) g_default_base_url = "http://localhost:3000";
  g_config_path = util::env_or("AUDIO_WORKER_CONFIG", base_dir + "/accounts.json");
  g_host_label = host_label();

  worker::g_poll_interval_ms = env_interval_ms("AUDIO_WORKER_POLL_SEC", 10);
  worker::g_metrics_interval_ms = env_interval_ms("AUDIO_WORKER_METRICS_SEC", 3);
  worker::g_work_dir = util::env_or("AUDIO_WORKER_DIR", base_dir + "/worker-audio");
  ui::g_ui_host = util::env_or("AUDIO_WORKER_UI_HOST", "127.0.0.1");
  ui::g_ui_port = static_cast<int>(std::stol(util::env_or("AUDIO_WORKER_UI_PORT", "39123")));
  stt::g_stt_dir = base_dir + "/stt";

  mkdir_p(worker::g_work_dir);
  load_config();

  struct sigaction sa {};
  sa.sa_handler = on_signal;
  ::sigaction(SIGINT, &sa, nullptr);
  ::sigaction(SIGTERM, &sa, nullptr);
  ::signal(SIGPIPE, SIG_IGN);

  httplib::Server svr;
  ui::setup_routes(svr);
  std::thread ui_thread([&svr] {
    if (!svr.listen(ui::g_ui_host, ui::g_ui_port)) {
      std::fprintf(stderr, "管理UIを起動できません: http://%s:%d\n", ui::g_ui_host.c_str(),
                   ui::g_ui_port);
    }
  });
  // listen 開始を少し待ってからURLを表示する（JS版の listen コールバック相当）。
  svr.wait_until_ready();
  std::printf("管理UI: http://%s:%d\n", ui::g_ui_host.c_str(), ui::g_ui_port);

  std::thread metrics_thread(worker::metrics_loop);

  // シグナル監視: 1回目で停止フラグ+UI停止、2回目は即終了（ハンドラ側）。
  std::thread signal_thread([&svr] {
    while (!g_signal_count && !worker::g_stopping) {
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    worker::g_stopping = true;
    svr.stop();
  });

  worker::worker_loop();

  worker::g_stopping = true;
  svr.stop();
  signal_thread.join();
  metrics_thread.join();
  ui_thread.join();
  return 0;
}
