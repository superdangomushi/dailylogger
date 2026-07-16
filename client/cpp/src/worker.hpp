// ジョブ処理の本体: クライアント登録フェーズ → claim → download → 文字起こし → result。
// メトリクス送信ループもここに置く。
#pragma once

#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <set>
#include <string>
#include <thread>

#include "api.hpp"
#include "config.hpp"
#include "metrics.hpp"
#include "stt.hpp"

namespace worker {

inline std::atomic<bool> g_stopping{false};
inline std::string g_work_dir;
inline long g_poll_interval_ms = 10000;
inline long g_metrics_interval_ms = 3000;

// 停止要求に素早く反応できるよう、小刻みに刻んで眠る。
inline void sleep_ms(long ms) {
  const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
  while (!g_stopping && std::chrono::steady_clock::now() < until) {
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
}

// =====================================================================
// クライアント登録（起動後の最初のフェーズ）
// このPCのID（UUID）をクライアント側で生成し、表示名とともにサーバーへ登録する。
// UUID が他アカウントと衝突したら（通常起きない）再生成してやり直す。
// =====================================================================

inline void mark_unregistered(const std::string& email) {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  Account* a = find_account_locked(email);
  if (!a || !a->registered) return;
  a->registered = false;
  a->updated_at = util::now_iso();
  persist_config_locked();
  std::printf("[%s] サーバー側でこのPCの登録が失われました。次回ポーリングで再登録します\n",
              email.c_str());
}

// acc はスナップショット。生成した clientId や登録結果はグローバル設定にも反映する。
inline void register_account(Account& acc) {
  for (int attempt = 0; attempt < 3; attempt++) {
    if (acc.client_id.empty()) {
      acc.client_id = util::uuid4();
      std::lock_guard<std::mutex> lock(g_state_mutex);
      if (Account* a = find_account_locked(acc.email)) a->client_id = acc.client_id;
    }
    std::string name;
    std::string mode;
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      name = client_name_locked();
      mode = normalize_mode(g_config.mode);
    }
    try {
      const json r = api::post_json(acc, "/api/client/register", {{"name", name}, {"mode", mode}});
      acc.registered = true;
      acc.updated_at = util::now_iso();
      {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        if (Account* a = find_account_locked(acc.email)) {
          a->client_id = acc.client_id;
          a->registered = true;
          a->updated_at = acc.updated_at;
          if (a->source != "env") persist_config_locked();
        }
      }
      std::string shown = name;
      if (r.contains("client") && r["client"].is_object()) {
        shown = jstr(r["client"], "name", name);
      }
      std::printf("[%s] このPCを登録しました: %s (ID %s)\n", acc.email.c_str(), shown.c_str(),
                  acc.client_id.c_str());
      return;
    } catch (const ApiError& e) {
      if (e.code == "uuid_conflict") {
        acc.client_id.clear();  // 再生成して次のループで登録し直す
        std::lock_guard<std::mutex> lock(g_state_mutex);
        if (Account* a = find_account_locked(acc.email)) a->client_id.clear();
        continue;
      }
      throw;
    }
  }
  throw ApiError("クライアントIDの登録に失敗しました（ID衝突が解消できません）");
}

// 未登録なら登録フェーズを済ませてからジョブ処理へ進む。
inline void ensure_registered(Account& acc) {
  if (acc.registered && !acc.client_id.empty()) return;
  update_status(acc.email, "polling", "このPCをサーバーに登録中");
  register_account(acc);
}

inline std::string safe_name(long job_id, const std::string& filename) {
  const std::string base =
      util::basename_of(filename.empty() ? ("audio-" + std::to_string(job_id) + ".wav") : filename);
  return std::to_string(job_id) + "-" + util::sanitize_name(base, 200);
}

inline void report_error(const Account& acc, long job_id, const std::string& message) {
  try {
    api::post_json(acc, "/api/client/jobs/result",
                   {{"jobId", job_id}, {"error", util::clip_utf8(message, 1000)}});
  } catch (const std::exception& e) {
    std::fprintf(stderr, "[%s] ジョブ #%ld のエラー報告に失敗: %s\n", acc.email.c_str(), job_id,
                 e.what());
  }
}

// 1アカウント分の1周。ジョブを処理したら true。
inline bool process_one(Account& acc) {
  ensure_registered(acc);
  update_status(acc.email, "polling", "ジョブ確認中");
  std::string mode;
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    mode = normalize_mode(g_config.mode);
  }
  json claimed;
  try {
    claimed = api::post_json(acc, "/api/client/claim", {{"mode", mode}});
  } catch (const ApiError& e) {
    if (e.code == "unregistered") mark_unregistered(acc.email);
    throw;
  }
  if (claimed.contains("client") && claimed["client"].is_object() &&
      !jbool(claimed["client"], "allowed", true)) {
    update_status(acc.email, "idle",
                  "このPCはサーバー設定で処理対象外です（ダッシュボードのPC選択を確認）");
    return false;
  }
  if (!claimed.contains("job") || !claimed["job"].is_object()) {
    update_status(acc.email, "idle", "待機中");
    return false;
  }
  const json& job = claimed["job"];
  const long job_id = jlong(job, "jobId");
  const std::string filename = jstr(job, "filename");
  const std::string quality = jstr(job, "quality", "high");

  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    auto& st = status_of_locked(acc.email);
    st.state = "working";
    st.message = filename + " を処理中";
    st.last_seen_at = util::now_iso();
    st.last_job_at = util::now_iso();
    st.last_job_id = job_id;
  }
  std::printf("[%s] ジョブ #%ld を取得: %s (%s)\n", acc.email.c_str(), job_id, filename.c_str(),
              quality.empty() ? "high" : quality.c_str());

  const std::string file_path =
      g_work_dir + "/" + util::sanitize_name(acc.email, 200) + "-" + safe_name(job_id, filename);
  bool downloaded = false;
  try {
    api::download_job_file(acc, job_id, file_path);
    downloaded = true;
    std::string text = stt::local_transcribe(file_path, quality.empty() ? "high" : quality);
    if (text.empty()) text = "本文なし";
    const json result = api::post_json(acc, "/api/client/jobs/result", {{"jobId", job_id}, {"text", text}});
    const std::string result_name = jstr(result, "filename");
    const long chars = jlong(result, "chars");
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      auto& st = status_of_locked(acc.email);
      st.completed++;
      st.state = "idle";
      st.message = "完了: " + (result_name.empty() ? "(本文なし)" : result_name);
      st.last_seen_at = util::now_iso();
    }
    std::printf("[%s] ジョブ #%ld 完了: %s %s\n", acc.email.c_str(), job_id,
                result_name.empty() ? "(本文なし)" : result_name.c_str(),
                chars ? (std::to_string(chars) + "文字").c_str() : "");
  } catch (const std::exception& e) {
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      auto& st = status_of_locked(acc.email);
      st.failed++;
      st.state = "error";
      st.message = e.what();
      st.last_seen_at = util::now_iso();
    }
    std::fprintf(stderr, "[%s] ジョブ #%ld 失敗: %s\n", acc.email.c_str(), job_id, e.what());
    report_error(acc, job_id, e.what());
  }
  if (downloaded) ::unlink(file_path.c_str());
  return true;
}

inline void worker_loop() {
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    std::printf("audio-worker 起動: %s / %ld秒間隔 / 表示名=%s / モード=%s\n",
                clean_base_url(g_config.base_url).c_str(), g_poll_interval_ms / 1000,
                client_name_locked().c_str(), normalize_mode(g_config.mode).c_str());
  }
  {
    const auto accounts = active_accounts_snapshot();
    long unregistered = 0;
    for (const auto& a : accounts) {
      if (!a.registered) unregistered++;
    }
    if (unregistered) {
      std::printf(
          "未登録のアカウントが %ld 件あります。"
          "各アカウントは最初のポーリングで登録フェーズ（このPCのID生成と表示名の登録）を実行します\n",
          unregistered);
    }
  }
  while (!g_stopping) {
    auto accounts = active_accounts_snapshot();
    if (accounts.empty()) {
      sleep_ms(g_poll_interval_ms);
      continue;
    }
    bool worked = false;
    for (auto& acc : accounts) {
      if (g_stopping) break;
      try {
        worked = process_one(acc) || worked;
      } catch (const std::exception& e) {
        update_status(acc.email, "error", e.what());
        std::fprintf(stderr, "[%s] ポーリング失敗: %s\n", acc.email.c_str(), e.what());
      }
    }
    sleep_ms(worked ? 500 : g_poll_interval_ms);
  }
  std::printf("audio-worker を停止します\n");
}

// 3秒ごとに使用率をサーバーへ送る。処理中ジョブのIDをハートビートとして同送する
// （これが途絶えたジョブはサーバーが再キューして別のPCへ振り直す）。
inline void metrics_loop() {
  metrics::sample_cpu_pct();  // 差分計測の基準を作る
  std::set<std::string> error_logged;
  while (!g_stopping) {
    sleep_ms(g_metrics_interval_ms);
    if (g_stopping) break;
    const auto gpu = metrics::sample_gpu_pct();
    const auto cpu = metrics::sample_cpu_pct();
    const auto mem = metrics::sample_mem_pct();
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      g_latest_metrics = {cpu, mem, gpu, util::now_iso()};
    }
    for (const auto& acc : active_accounts_snapshot()) {
      // 登録フェーズ（/api/client/register）が済むまでは送らない。
      if (!acc.registered || acc.client_id.empty()) continue;
      long active_job_id = 0;
      {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        const auto& st = status_of_locked(acc.email);
        if (st.state == "working" && st.last_job_id) active_job_id = st.last_job_id;
      }
      try {
        json body;
        body["cpu"] = cpu ? json(*cpu) : json(nullptr);
        body["mem"] = mem ? json(*mem) : json(nullptr);
        body["gpu"] = gpu ? json(*gpu) : json(nullptr);
        body["activeJobId"] = active_job_id ? json(active_job_id) : json(nullptr);
        api::post_json(acc, "/api/client/metrics", std::move(body));
        error_logged.erase(acc.email);
      } catch (const ApiError& e) {
        // ダッシュボードからPCを削除された等で未登録扱いになったら、次の
        // ポーリングで登録フェーズからやり直す。
        if (e.code == "unregistered") mark_unregistered(acc.email);
        if (!error_logged.count(acc.email)) {
          error_logged.insert(acc.email);
          std::fprintf(stderr, "[%s] メトリクス送信に失敗（以後同じログは抑制）: %s\n",
                       acc.email.c_str(), e.what());
        }
      } catch (const std::exception& e) {
        if (!error_logged.count(acc.email)) {
          error_logged.insert(acc.email);
          std::fprintf(stderr, "[%s] メトリクス送信に失敗（以後同じログは抑制）: %s\n",
                       acc.email.c_str(), e.what());
        }
      }
    }
  }
}

}  // namespace worker
