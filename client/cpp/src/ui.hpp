// ローカル管理UI（http://127.0.0.1:39123）。認証なし（ローカルのみバインド）。
//
//   GET  /              管理画面HTML
//   GET  /api/state     現在の設定+アカウント状態
//   POST /api/settings  {baseUrl?, clientName?, mode?} の部分更新
//   POST /api/accounts  初回セットアップ（login → UUID生成 → register）
//   PATCH/DELETE /api/accounts/:email
#pragma once

#include <thread>

#include "httplib.h"
#include "json.hpp"
#include "config.hpp"
#include "ui_html.hpp"
#include "worker.hpp"

namespace ui {

inline std::string g_ui_host = "127.0.0.1";
inline int g_ui_port = 39123;

inline void send_json(httplib::Response& res, int status, const json& body) {
  res.status = status;
  res.set_header("Cache-Control", "no-store");
  res.set_content(body.dump(), "application/json; charset=utf-8");
}

inline json parse_body(const httplib::Request& req) {
  if (req.body.empty()) return json::object();
  return json::parse(req.body);  // 不正なJSONは例外 → exception handler が 500 を返す
}

// 表示名の変更は登録APIで伝わるため、登録済みアカウントを再登録する
// （失敗しても次のポーリングの ensure_registered で追い付く）。
inline void reregister_all_async() {
  std::thread([] {
    for (auto& acc : active_accounts_snapshot()) {
      if (!acc.registered) continue;
      try {
        worker::register_account(acc);
      } catch (const std::exception& e) {
        std::fprintf(stderr, "[%s] 表示名変更の再登録に失敗: %s\n", acc.email.c_str(), e.what());
      }
    }
  }).detach();
}

inline void setup_routes(httplib::Server& svr) {
  svr.set_payload_max_length(1024 * 1024);

  svr.set_exception_handler([](const httplib::Request&, httplib::Response& res, std::exception_ptr ep) {
    std::string message = "内部エラー";
    try {
      if (ep) std::rethrow_exception(ep);
    } catch (const std::exception& e) {
      message = e.what();
    } catch (...) {
    }
    send_json(res, 500, {{"ok", false}, {"error", message}});
  });

  svr.set_error_handler([](const httplib::Request&, httplib::Response& res) {
    if (!res.body.empty()) return;  // ハンドラが本文を用意済みなら触らない
    send_json(res, res.status ? res.status : 404, {{"ok", false}, {"error", "not found"}});
  });

  svr.Get("/", [](const httplib::Request&, httplib::Response& res) {
    res.set_header("Cache-Control", "no-store");
    res.set_content(kHtmlPage, "text/html; charset=utf-8");
  });

  svr.Get("/api/state", [](const httplib::Request&, httplib::Response& res) {
    const std::string ui_url = "http://" + g_ui_host + ":" + std::to_string(g_ui_port);
    send_json(res, 200,
              public_state(worker::g_poll_interval_ms / 1000.0,
                           worker::g_metrics_interval_ms / 1000.0, ui_url));
  });

  svr.Post("/api/settings", [](const httplib::Request& req, httplib::Response& res) {
    const json body = parse_body(req);
    bool name_changed = false;
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      // モードだけの部分更新でも baseUrl を既定値に巻き戻さないよう、送られた項目のみ反映する。
      if (body.contains("baseUrl")) g_config.base_url = clean_base_url(jstr(body, "baseUrl"));
      if (body.contains("mode")) g_config.mode = normalize_mode(jstr(body, "mode"));
      if (body.contains("clientName")) {
        const std::string next = util::clip_utf8(util::trim(jstr(body, "clientName")), 100);
        name_changed = next != g_config.client_name;
        g_config.client_name = next;
      }
      save_config_locked();
    }
    if (name_changed) reregister_all_async();
    send_json(res, 200, {{"ok", true}});
  });

  // 初回セットアップの本体: ログイン → このPCのIDを生成してクライアント登録。
  // 登録に失敗してもアカウントは保存され、次のポーリングで自動再試行される。
  svr.Post("/api/accounts", [](const httplib::Request& req, httplib::Response& res) {
    const json body = parse_body(req);
    std::string original_base_url;
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      original_base_url = g_config.base_url;
      if (!jstr(body, "baseUrl").empty()) {
        g_config.base_url = clean_base_url(jstr(body, "baseUrl"));
      }
      if (body.contains("clientName")) {
        g_config.client_name = util::clip_utf8(util::trim(jstr(body, "clientName")), 100);
      }
    }
    const std::string email = util::trim(jstr(body, "email"));
    const std::string password = jstr(body, "password");
    if (email.empty() || password.empty()) {
      send_json(res, 400, {{"ok", false}, {"error", "メールとパスワードを入力してください"}});
      return;
    }
    std::string logged_in_email;
    std::string token;
    try {
      std::tie(logged_in_email, token) = api::login_with_password(email, password);
    } catch (const std::exception& e) {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      g_config.base_url = original_base_url;
      send_json(res, 500, {{"ok", false}, {"error", e.what()}});
      return;
    }
    Account snapshot;
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      Account* existing = find_account_locked(logged_in_email);
      if (!existing) {
        g_config.accounts.push_back(Account{});
        existing = &g_config.accounts.back();
        existing->added_at = util::now_iso();
      }
      existing->email = logged_in_email;
      existing->token = token;
      existing->enabled = true;
      existing->source = "ui";
      existing->registered = false;  // clientId は既存のものがあれば引き継いで再登録する
      existing->updated_at = util::now_iso();
      save_config_locked();
      snapshot = *existing;
    }
    std::string register_error;
    try {
      worker::register_account(snapshot);
      update_status(snapshot.email, "idle", "クライアント登録済み");
    } catch (const std::exception& e) {
      register_error = e.what();
      update_status(snapshot.email, "error", std::string("クライアント登録に失敗: ") + register_error);
    }
    std::string client_id;
    bool registered = false;
    {
      std::lock_guard<std::mutex> lock(g_state_mutex);
      if (const Account* a = find_account_locked(snapshot.email)) {
        client_id = a->client_id;
        registered = a->registered;
      }
    }
    send_json(res, 200,
              {{"ok", true},
               {"email", snapshot.email},
               {"registered", registered},
               {"clientId", client_id.empty() ? json(nullptr) : json(client_id)},
               {"registerError", register_error.empty() ? json(nullptr) : json(register_error)}});
  });

  svr.Patch(R"(/api/accounts/(.+))", [](const httplib::Request& req, httplib::Response& res) {
    const std::string email = util::url_decode(req.matches[1].str());
    const json body = parse_body(req);
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Account* a = find_account_locked(email);
    if (!a) {
      send_json(res, 404, {{"ok", false}, {"error", "アカウントが見つかりません"}});
      return;
    }
    if (a->source == "env") {
      send_json(res, 400, {{"ok", false}, {"error", "環境変数由来のアカウントはUIから変更できません"}});
      return;
    }
    a->enabled = jbool(body, "enabled", true);
    a->updated_at = util::now_iso();
    save_config_locked();
    send_json(res, 200, {{"ok", true}});
  });

  svr.Delete(R"(/api/accounts/(.+))", [](const httplib::Request& req, httplib::Response& res) {
    const std::string email = util::url_decode(req.matches[1].str());
    std::lock_guard<std::mutex> lock(g_state_mutex);
    Account* a = find_account_locked(email);
    if (!a) {
      send_json(res, 404, {{"ok", false}, {"error", "アカウントが見つかりません"}});
      return;
    }
    if (a->source == "env") {
      send_json(res, 400, {{"ok", false}, {"error", "環境変数由来のアカウントはUIから変更できません"}});
      return;
    }
    g_config.accounts.erase(
        std::remove_if(g_config.accounts.begin(), g_config.accounts.end(),
                       [&](const Account& x) { return x.email == email; }),
        g_config.accounts.end());
    g_runtime.erase(email);
    save_config_locked();
    send_json(res, 200, {{"ok", true}});
  });
}

}  // namespace ui
