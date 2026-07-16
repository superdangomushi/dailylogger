// 設定（accounts.json）とアカウント毎の実行状態の管理。
//
// JS版と同じファイル形式を読み書きするため、既存の accounts.json はそのまま使える。
// 複数スレッド（ワーカー / メトリクス / 管理UI）から触るので、すべて
// g_state_mutex を取ってからアクセスする。ネットワークI/O中はロックを持たない。
#pragma once

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <fstream>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "json.hpp"
#include "util.hpp"

using nlohmann::json;

// json::value() は「キーはあるが値が null / 型違い」のとき例外を投げる。
// accounts.json は clientId: null を含みうるので、型を確かめてから読む。
inline std::string jstr(const json& j, const char* key, const std::string& def = "") {
  if (!j.is_object() || !j.contains(key) || !j.at(key).is_string()) return def;
  return j.at(key).get<std::string>();
}

inline bool jbool(const json& j, const char* key, bool def) {
  if (!j.is_object() || !j.contains(key) || !j.at(key).is_boolean()) return def;
  return j.at(key).get<bool>();
}

inline long jlong(const json& j, const char* key, long def = 0) {
  if (!j.is_object() || !j.contains(key) || !j.at(key).is_number()) return def;
  return j.at(key).get<long>();
}

struct Account {
  std::string email;
  std::string token;
  bool enabled = true;
  std::string source = "ui";  // "ui" | "env"
  std::string client_id;      // このPCのID（UUID）。未生成なら空
  bool registered = false;
  std::string added_at;
  std::string updated_at;
};

// アカウント毎の実行状態（管理UIに表示する。保存はしない）。
struct AccountStatus {
  std::string state = "idle";  // idle | polling | working | error
  std::string message;
  std::string last_seen_at;
  std::string last_job_at;
  long completed = 0;
  long failed = 0;
  long last_job_id = 0;
};

struct Config {
  std::string base_url;
  std::string mode = "private";  // private | global
  std::string client_name;       // 空ならホスト名
  std::vector<Account> accounts;
};

struct Metrics {
  std::optional<double> cpu;
  std::optional<double> mem;
  std::optional<double> gpu;
  std::string at;
};

// ---------------------------------------------------------------------
// グローバル状態
// ---------------------------------------------------------------------

inline std::mutex g_state_mutex;
inline Config g_config;
inline std::map<std::string, AccountStatus> g_runtime;
inline Metrics g_latest_metrics;
inline std::string g_host_label;
inline std::string g_config_path;
inline std::string g_default_base_url;

inline std::string clean_base_url(const std::string& value) {
  std::string s = util::trim(value);
  while (!s.empty() && s.back() == '/') s.pop_back();
  return s.empty() ? g_default_base_url : s;
}

inline std::string normalize_mode(const std::string& value) {
  return util::to_lower(util::trim(value)) == "global" ? "global" : "private";
}

inline Account normalize_account(const json& raw) {
  Account a;
  a.email = util::trim(jstr(raw, "email"));
  a.token = util::trim(jstr(raw, "token"));
  a.enabled = jbool(raw, "enabled", true);
  a.source = jstr(raw, "source", "ui");
  std::string cid = util::to_lower(util::trim(jstr(raw, "clientId")));
  a.client_id = util::is_uuid(cid) ? cid : "";
  a.registered = jbool(raw, "registered", false) && !a.client_id.empty();
  a.added_at = jstr(raw, "addedAt", util::now_iso());
  a.updated_at = jstr(raw, "updatedAt", util::now_iso());
  return a;
}

// accounts.json + 環境変数（AIHELPER_EMAIL / AIHELPER_TOKEN）を読み込む。
inline void load_config() {
  json loaded;
  std::ifstream in(g_config_path);
  if (in.good()) {
    try {
      loaded = json::parse(in);
    } catch (const std::exception& e) {
      std::fprintf(stderr, "設定ファイルの読み込みに失敗: %s: %s\n", g_config_path.c_str(), e.what());
      loaded = json::object();
    }
  }

  Config cfg;
  cfg.base_url = clean_base_url(jstr(loaded, "baseUrl", g_default_base_url));
  // このPCの公開範囲。private=登録したアカウントの音声のみ、global=全ユーザーの音声を処理。
  cfg.mode = normalize_mode(jstr(loaded, "mode"));
  // ユーザーが決めるこのPCの表示名（サーバーのPC選択画面に出る）。既定はホスト名。
  cfg.client_name = util::clip_utf8(util::trim(jstr(loaded, "clientName")), 100);
  if (loaded.contains("accounts") && loaded["accounts"].is_array()) {
    for (const auto& raw : loaded["accounts"]) {
      Account a = normalize_account(raw);
      if (!a.email.empty() && !a.token.empty()) cfg.accounts.push_back(std::move(a));
    }
  }

  const std::string env_email = util::trim(util::env_or("AIHELPER_EMAIL", util::env_or("EMAIL", "")));
  const std::string env_token = util::trim(util::env_or("AIHELPER_TOKEN", util::env_or("TOKEN", "")));
  const bool exists = std::any_of(cfg.accounts.begin(), cfg.accounts.end(),
                                  [&](const Account& a) { return a.email == env_email; });
  if (!env_email.empty() && !env_token.empty() && !exists) {
    Account a;
    a.email = env_email;
    a.token = env_token;
    a.source = "env";
    a.added_at = a.updated_at = util::now_iso();
    cfg.accounts.push_back(std::move(a));
  }

  std::lock_guard<std::mutex> lock(g_state_mutex);
  g_config = std::move(cfg);
}

// ユーザーが決めたこのPCの表示名。未設定ならホスト名。ロック中に呼ぶこと。
inline std::string client_name_locked() {
  std::string n = util::clip_utf8(util::trim(g_config.client_name), 100);
  if (!n.empty()) return n;
  return g_host_label.empty() ? "PC" : g_host_label;
}

inline std::string client_name() {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  return client_name_locked();
}

// tmpファイル経由のアトミック保存（mode 0600）。ロック中に呼ぶこと。
inline void save_config_locked() {
  json stored;
  stored["baseUrl"] = clean_base_url(g_config.base_url);
  stored["mode"] = normalize_mode(g_config.mode);
  stored["clientName"] = client_name_locked();
  stored["accounts"] = json::array();
  for (const auto& a : g_config.accounts) {
    if (a.source == "env") continue;
    json j;
    j["email"] = a.email;
    j["token"] = a.token;
    j["enabled"] = a.enabled;
    j["source"] = "ui";
    j["clientId"] = a.client_id.empty() ? json(nullptr) : json(a.client_id);
    j["registered"] = a.registered;
    j["addedAt"] = a.added_at;
    j["updatedAt"] = a.updated_at.empty() ? util::now_iso() : a.updated_at;
    stored["accounts"].push_back(std::move(j));
  }
  const std::string tmp = g_config_path + ".tmp";
  {
    std::ofstream out(tmp, std::ios::trunc);
    if (!out) throw std::runtime_error("設定ファイルを書き込めません: " + tmp);
    out << stored.dump(2);
  }
  ::chmod(tmp.c_str(), 0600);
  if (::rename(tmp.c_str(), g_config_path.c_str()) != 0) {
    throw std::runtime_error("設定ファイルの置き換えに失敗: " + g_config_path);
  }
}

inline void persist_config_locked() {
  try {
    save_config_locked();
  } catch (const std::exception& e) {
    std::fprintf(stderr, "設定の保存に失敗（動作は継続）: %s\n", e.what());
  }
}

inline AccountStatus& status_of_locked(const std::string& email) { return g_runtime[email]; }

// 実行状態の部分更新。lastSeenAt は毎回更新する（JS版 updateStatus と同じ）。
inline void update_status(const std::string& email, const std::string& state,
                          const std::string& message) {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  auto& st = status_of_locked(email);
  st.state = state;
  st.message = message;
  st.last_seen_at = util::now_iso();
}

inline Account* find_account_locked(const std::string& email) {
  for (auto& a : g_config.accounts) {
    if (a.email == email) return &a;
  }
  return nullptr;
}

// 有効なアカウントのスナップショット（ネットワーク処理はロック外で行うためコピーを使う）。
inline std::vector<Account> active_accounts_snapshot() {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  std::vector<Account> out;
  for (const auto& a : g_config.accounts) {
    if (a.enabled && !a.email.empty() && !a.token.empty()) out.push_back(a);
  }
  return out;
}

// 管理UIの GET /api/state が返す内容。
inline json public_state(double poll_sec, double metrics_sec, const std::string& ui_url) {
  std::lock_guard<std::mutex> lock(g_state_mutex);
  json m;
  m["cpu"] = g_latest_metrics.cpu ? json(*g_latest_metrics.cpu) : json(nullptr);
  m["mem"] = g_latest_metrics.mem ? json(*g_latest_metrics.mem) : json(nullptr);
  m["gpu"] = g_latest_metrics.gpu ? json(*g_latest_metrics.gpu) : json(nullptr);
  m["at"] = g_latest_metrics.at.empty() ? json(nullptr) : json(g_latest_metrics.at);

  json accounts = json::array();
  for (const auto& a : g_config.accounts) {
    const auto& st = g_runtime[a.email];
    json s;
    s["state"] = st.state;
    s["message"] = st.message;
    s["lastSeenAt"] = st.last_seen_at.empty() ? json(nullptr) : json(st.last_seen_at);
    s["lastJobAt"] = st.last_job_at.empty() ? json(nullptr) : json(st.last_job_at);
    s["completed"] = st.completed;
    s["failed"] = st.failed;
    s["lastJobId"] = st.last_job_id ? json(st.last_job_id) : json(nullptr);
    json j;
    j["email"] = a.email;
    j["enabled"] = a.enabled;
    j["source"] = a.source;
    j["clientId"] = a.client_id.empty() ? json(nullptr) : json(a.client_id);
    j["registered"] = a.registered;
    j["addedAt"] = a.added_at;
    j["updatedAt"] = a.updated_at;
    j["status"] = std::move(s);
    accounts.push_back(std::move(j));
  }

  json out;
  out["ok"] = true;
  out["baseUrl"] = g_config.base_url;
  out["mode"] = normalize_mode(g_config.mode);
  out["clientName"] = client_name_locked();
  out["metrics"] = std::move(m);
  out["hostname"] = g_host_label;
  out["pollSec"] = poll_sec;
  out["metricsSec"] = metrics_sec;
  out["ui"] = ui_url;
  out["configPath"] = g_config_path;
  out["accounts"] = std::move(accounts);
  return out;
}
