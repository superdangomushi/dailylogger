// ローカル管理UIのページ本体。動的なデータはすべてページ内スクリプトが
// GET /api/state から取得するため、HTMLは静的な1枚で完結する。
#pragma once

namespace ui {

inline const char* kHtmlPage = R"__HTML__(<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>AIHelper 音声ワーカー</title>
  <style>
    :root { color-scheme: light; --bg:#f5f7fa; --panel:#fff; --line:#d9e0ea; --ink:#1d2733; --muted:#617083; --accent:#1f6feb; --danger:#b42318; --ok:#117a37; }
    * { box-sizing:border-box; }
    body { margin:0; background:var(--bg); color:var(--ink); font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
    header { background:#172033; color:white; padding:14px 20px; display:flex; justify-content:space-between; align-items:center; gap:12px; }
    h1 { font-size:18px; margin:0; font-weight:700; }
    main { max-width:1080px; margin:0 auto; padding:20px; display:grid; gap:16px; }
    section { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:16px; }
    h2 { font-size:16px; margin:0 0 12px; }
    label { display:block; font-size:13px; color:var(--muted); margin-bottom:6px; }
    input { width:100%; padding:10px 11px; border:1px solid var(--line); border-radius:6px; font:inherit; background:white; }
    button { border:0; border-radius:6px; padding:10px 12px; font:inherit; font-weight:700; cursor:pointer; background:var(--accent); color:white; }
    button.ghost { background:#edf2f7; color:var(--ink); }
    button.danger { background:#fff1f0; color:var(--danger); border:1px solid #ffd3cf; }
    button:disabled { opacity:.55; cursor:default; }
    .grid { display:grid; grid-template-columns:1.5fr 1fr auto; gap:10px; align-items:end; }
    .add { display:grid; grid-template-columns:1fr 1fr auto; gap:10px; align-items:end; }
    .muted { color:var(--muted); }
    .small { font-size:12px; }
    table { width:100%; border-collapse:collapse; }
    th, td { padding:10px 8px; border-bottom:1px solid var(--line); text-align:left; vertical-align:middle; }
    th { font-size:12px; color:var(--muted); font-weight:700; }
    .pill { display:inline-block; padding:3px 8px; border-radius:999px; font-size:12px; background:#eef2f7; color:var(--muted); }
    .pill.ok { background:#e7f6ec; color:var(--ok); }
    .pill.err { background:#fff1f0; color:var(--danger); }
    .actions { display:flex; gap:8px; flex-wrap:wrap; }
    .modes { margin-top:14px; display:grid; gap:6px; }
    .mode-option { display:flex; align-items:flex-start; gap:8px; margin:0; font-size:13px; color:var(--ink); cursor:pointer; }
    .mode-option input { width:auto; margin-top:2px; }
    #notice { min-height:20px; }
    @media (max-width:760px) {
      .grid, .add { grid-template-columns:1fr; }
      table, thead, tbody, tr, th, td { display:block; }
      thead { display:none; }
      tr { border-bottom:1px solid var(--line); padding:8px 0; }
      td { border:0; padding:6px 0; }
    }
  </style>
</head>
<body>
  <header>
    <h1>AIHelper 音声ワーカー</h1>
    <div class="small" id="topState">読み込み中</div>
  </header>
  <main>
    <section>
      <h2>サーバー / このPC</h2>
      <div class="grid">
        <div>
          <label for="baseUrl">公開サーバーURL</label>
          <input id="baseUrl" placeholder="https://example.com">
        </div>
        <div>
          <label for="clientName">このPCの表示名（サーバーのPC選択画面に表示）</label>
          <input id="clientName" placeholder="例: 研究室デスクトップ">
        </div>
        <button id="saveSettings">保存</button>
      </div>
      <div class="small muted" id="configPath" style="margin-top:8px"></div>
      <div class="modes">
        <label style="margin-bottom:4px">このPCの処理モード</label>
        <label class="mode-option">
          <input type="radio" name="workerMode" value="private" checked>
          <span><strong>private</strong> — このPCで登録したアカウントの音声だけを処理します</span>
        </label>
        <label class="mode-option">
          <input type="radio" name="workerMode" value="global">
          <span><strong>global</strong> — このサービスの全ユーザーの音声処理を担うPCとして公開します</span>
        </label>
        <div class="small muted">モードは選択した時点で保存されます。globalでは他のユーザーの音声データがこのPCにダウンロードされて処理されます。</div>
      </div>
      <div class="small muted" id="metricsLine" style="margin-top:10px"></div>
    </section>
    <section>
      <h2>アカウント登録（初回セットアップ）</h2>
      <p class="small muted" style="margin:0 0 10px">
        メール+パスワードでログインすると、このPC用のIDを自動生成し、上の表示名とともに
        サーバーへクライアント登録します。登録が済んだアカウントだけが音声処理を開始します。
        パスワードはログインに一度使うだけで保存されません。
      </p>
      <div class="add">
        <div>
          <label for="email">メール</label>
          <input id="email" type="email" autocomplete="username">
        </div>
        <div>
          <label for="password">パスワード</label>
          <input id="password" type="password" autocomplete="current-password">
        </div>
        <button id="addAccount">ログインして登録</button>
      </div>
      <div id="notice" class="small muted" style="margin-top:10px"></div>
    </section>
    <section>
      <h2>処理対象アカウント</h2>
      <div id="accounts"></div>
    </section>
  </main>
  <script>
    const $ = (id) => document.getElementById(id);

    async function api(path, options = {}) {
      const res = await fetch(path, {
        ...options,
        headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok || !json.ok) throw new Error(json.error || 'HTTP ' + res.status);
      return json;
    }

    function statusPill(status) {
      const state = status?.state || 'idle';
      const cls = state === 'error' ? 'err' : (state === 'working' ? 'ok' : '');
      const label = state === 'working' ? '処理中' : state === 'polling' ? '確認中' : state === 'error' ? 'エラー' : '待機';
      return '<span class="pill ' + cls + '">' + label + '</span>';
    }

    function esc(s) {
      return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    }

    let baseUrlDirty = false;
    let clientNameDirty = false;
    let modeDirty = false;

    function pct(v) {
      return (v === null || v === undefined) ? '—' : Math.round(v) + '%';
    }

    async function load() {
      const state = await api('/api/state');
      const baseUrlInput = $('baseUrl');
      if (!baseUrlDirty && document.activeElement !== baseUrlInput) {
        baseUrlInput.value = state.baseUrl || '';
      }
      const clientNameInput = $('clientName');
      if (!clientNameDirty && document.activeElement !== clientNameInput) {
        clientNameInput.value = state.clientName || '';
      }
      if (!modeDirty) {
        const radio = document.querySelector('input[name="workerMode"][value="' + (state.mode || 'private') + '"]');
        if (radio) radio.checked = true;
      }
      const m = state.metrics || {};
      $('metricsLine').textContent =
        'このPCの使用率 (' + state.metricsSec + '秒ごとにサーバーへ送信): CPU ' + pct(m.cpu) + ' / メモリ ' + pct(m.mem) + ' / GPU ' + pct(m.gpu);
      $('configPath').textContent = '設定: ' + state.configPath;
      $('topState').textContent = state.accounts.length + '件 / ' + state.pollSec + '秒間隔 / ' + (state.mode || 'private');
      if (!state.accounts.length) {
        $('accounts').innerHTML = '<p class="muted">まだアカウントがありません。上でログインして、このPCをクライアント登録してください。</p>';
        return;
      }
      $('accounts').innerHTML = '<table><thead><tr><th>メール</th><th>状態</th><th>直近</th><th>完了/失敗</th><th></th></tr></thead><tbody>' +
        state.accounts.map(a => {
          const st = a.status || {};
          const reg = a.registered
            ? '<br><span class="small muted">登録済み / クライアントID ' + esc(a.clientId || '') + '</span>'
            : '<br><span class="small" style="color:#b7791f">未登録（次回ポーリングで登録します）</span>';
          return '<tr>' +
            '<td><strong>' + esc(a.email) + '</strong><br><span class="small muted">' + (a.enabled ? '有効' : '停止中') + ' / ' + esc(a.source) + '</span>' +
              reg + '</td>' +
            '<td>' + statusPill(st) + '</td>' +
            '<td><div>' + esc(st.message || '') + '</div><div class="small muted">' + esc(st.lastSeenAt || '') + '</div></td>' +
            '<td>' + (st.completed || 0) + ' / ' + (st.failed || 0) + '</td>' +
            '<td><div class="actions">' +
              '<button class="ghost" onclick="toggleAccount(\'' + encodeURIComponent(a.email) + '\',' + (!a.enabled) + ')">' + (a.enabled ? '停止' : '再開') + '</button>' +
              '<button class="danger" onclick="removeAccount(\'' + encodeURIComponent(a.email) + '\')">削除</button>' +
            '</div></td>' +
          '</tr>';
        }).join('') + '</tbody></table>';
    }

    async function saveSettings() {
      const mode = document.querySelector('input[name="workerMode"]:checked')?.value || 'private';
      try {
        await api('/api/settings', { method:'POST', body: JSON.stringify({
          baseUrl: $('baseUrl').value, clientName: $('clientName').value, mode }) });
      } catch (e) {
        $('notice').textContent = '設定の保存に失敗しました: ' + e.message;
        return;
      }
      baseUrlDirty = false;
      clientNameDirty = false;
      modeDirty = false;
      $('notice').textContent = '設定を保存しました（モード: ' + mode + '）。表示名の変更は各アカウントの再登録で反映されます';
      await load();
    }

    async function addAccount() {
      const btn = $('addAccount');
      btn.disabled = true;
      $('notice').textContent = 'ログインしてこのPCを登録中...';
      try {
        const r = await api('/api/accounts', {
          method:'POST',
          body: JSON.stringify({ baseUrl: $('baseUrl').value, clientName: $('clientName').value,
            email: $('email').value, password: $('password').value })
        });
        $('password').value = '';
        $('notice').textContent = r.registered
          ? '✓ ログインし、このPCをクライアント登録しました'
          : '✓ ログインしました（クライアント登録は次回ポーリングで再試行します: ' + (r.registerError || '') + '）';
        await load();
      } catch (e) {
        $('notice').textContent = e.message;
      } finally {
        btn.disabled = false;
      }
    }

    async function toggleAccount(encoded, enabled) {
      await api('/api/accounts/' + encoded, { method:'PATCH', body: JSON.stringify({ enabled }) });
      await load();
    }

    async function removeAccount(encoded) {
      if (!confirm('このアカウントを削除しますか？')) return;
      await api('/api/accounts/' + encoded, { method:'DELETE' });
      await load();
    }

    $('baseUrl').addEventListener('input', () => { baseUrlDirty = true; });
    $('clientName').addEventListener('input', () => { clientNameDirty = true; });
    $('clientName').addEventListener('keydown', (event) => {
      if (event.key === 'Enter') saveSettings();
    });
    // モードはラジオを選んだ時点で保存する（「保存」ボタン待ちにすると、押し忘れて
    // リロードで元に戻ったように見えるため）。baseUrl は入力途中がありうるので送らない。
    document.querySelectorAll('input[name="workerMode"]').forEach((el) => {
      el.addEventListener('change', async () => {
        modeDirty = true;
        try {
          await api('/api/settings', { method:'POST', body: JSON.stringify({ mode: el.value }) });
          modeDirty = false;
          $('notice').textContent = 'モードを ' + el.value + ' に変更しました';
        } catch (e) {
          $('notice').textContent = 'モードの保存に失敗しました: ' + e.message;
        }
      });
    });
    $('baseUrl').addEventListener('keydown', (event) => {
      if (event.key === 'Enter') saveSettings();
    });
    $('saveSettings').addEventListener('click', saveSettings);
    $('addAccount').addEventListener('click', addAccount);
    setInterval(load, 3000);
    load().catch(e => { $('topState').textContent = e.message; });
  </script>
</body>
</html>)__HTML__";

}  // namespace ui
