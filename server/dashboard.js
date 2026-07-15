// ダッシュボード（ / ）の HTML。server.js から分割した静的テンプレート（変数の埋め込みなし）。
function renderDashboard() {
  return `<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AIHelper — あなたのAIアシスタント</title>
  <style>
    :root {
      --accent:#4f46e5; --accent-2:#6366f1; --ink:#0f172a; --muted:#64748b;
      --line:#e5e7eb; --bg:#f6f7fb; --card:#ffffff; --green:#16a34a; --danger:#dc2626;
      --radius:16px; --shadow:0 6px 24px rgba(15,23,42,.06);
    }
    * { box-sizing: border-box; }
    html { -webkit-text-size-adjust:100%; }
    body { font-family: system-ui,-apple-system,"Hiragino Kaku Gothic ProN","Noto Sans JP",sans-serif;
           margin:0; background:var(--bg); color:var(--ink); line-height:1.55; }
    header { background:linear-gradient(135deg,var(--accent),var(--accent-2));
             color:#fff; padding:1.5rem 1.25rem; }
    header .wrap { max-width:980px; margin:0 auto; }
    header h1 { margin:0; font-size:1.35rem; font-weight:700; letter-spacing:.02em; }
    header p { margin:.35rem 0 0; color:#e0e7ff; font-size:.85rem; }
    main { max-width:980px; margin:1.25rem auto 3rem; padding:0 1rem; display:grid; gap:1.1rem; }
    .card { background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
            padding:1.2rem 1.3rem; box-shadow:var(--shadow); }
    .card h2 { margin:0 0 .9rem; font-size:1.05rem; font-weight:700; }
    .card h3 { font-size:.95rem; font-weight:700; }
    .row { display:flex; gap:.5rem; flex-wrap:wrap; align-items:center; }
    label { font-size:.85rem; color:var(--muted); }
    input, select, textarea { font:inherit; padding:.6rem .7rem; border:1px solid var(--line);
            border-radius:10px; background:#fff; color:var(--ink); transition:border-color .15s,box-shadow .15s; }
    input:focus, select:focus, textarea:focus { outline:none; border-color:var(--accent);
            box-shadow:0 0 0 3px rgba(79,70,229,.15); }
    input, textarea { width:100%; }
    button { font:inherit; font-weight:600; padding:.6rem 1rem; border:none; border-radius:10px;
             background:var(--accent); color:#fff; cursor:pointer; transition:filter .15s,transform .02s; }
    button:hover { filter:brightness(1.06); }
    button:active { transform:translateY(1px); }
    button:disabled { opacity:.5; cursor:default; }
    button.ghost { background:#eef2ff; color:var(--accent); }
    button.small { padding:.3rem .6rem; font-size:.8rem; border-radius:8px; }
    .muted { color:var(--muted); font-size:.85rem; }
    .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:.6rem; }
    @media (max-width:640px){ .grid2 { grid-template-columns:1fr; } }
    table { border-collapse:collapse; width:100%; font-size:.9rem; }
    th, td { border-bottom:1px solid var(--line); padding:.55rem .6rem; text-align:left; vertical-align:top; }
    th { background:#f8fafc; font-weight:600; color:#475569; font-size:.82rem; }
    tr:last-child td { border-bottom:none; }
    td.num { text-align:right; } td.empty { text-align:center; color:#94a3b8; }
    a.dl { display:inline-block; padding:.25rem .6rem; background:#eef2ff; color:var(--accent);
           text-decoration:none; border-radius:8px; font-size:.8rem; font-weight:600; }
    a.dl.csv { background:#dcfce7; color:#15803d; }
    span.pending { color:#94a3b8; font-size:.85em; }
    .badge { display:inline-block; font-size:.7rem; padding:.15rem .55rem; border-radius:999px;
             color:#fff; font-weight:700; }
    .badge.kadai { background:#7c3aed; } .badge.yotei { background:#0891b2; }
    .due { font-size:.82rem; color:var(--muted); } .due.soon { color:var(--danger); font-weight:700; }
    .due.warn { color:#d97706; font-weight:600; }
    /* 課題テーブル: 内容に幅を寄せ、種別・期限・操作は折り返さない */
    #tasks td, #tasks th { vertical-align:top; }
    #tasks .col-type, #tasks .col-due, #tasks .col-mid { white-space:nowrap; width:1%; }
    #tasks .col-mid { text-align:center; }
    #tasks td:nth-child(2) { width:100%; }
    #tasks .due .rel { font-size:.75rem; opacity:.85; margin-top:.1rem; }
    .chatlog { display:flex; flex-direction:column; gap:.5rem; max-height:340px; overflow:auto;
               margin-bottom:.7rem; padding:.25rem; }
    .bubble { padding:.6rem .8rem; border-radius:14px; max-width:82%; white-space:pre-wrap; line-height:1.5;
              font-size:.92rem; }
    .bubble.me { align-self:flex-end; background:var(--accent); color:#fff; border-bottom-right-radius:4px; }
    .bubble.bot { align-self:flex-start; background:#f1f5f9; border-bottom-left-radius:4px; }
    .done { text-decoration:line-through; color:#94a3b8; }
    .modalbg { position:fixed; inset:0; background:rgba(15,23,42,.5); backdrop-filter:blur(2px);
               display:flex; align-items:center; justify-content:center; padding:1rem; z-index:50; }
    .modalbox { background:#fff; border-radius:var(--radius); padding:1.1rem 1.25rem; width:min(760px,100%);
                max-height:85vh; display:flex; flex-direction:column; box-shadow:var(--shadow); }
    .modalpre { white-space:pre-wrap; word-break:break-word; overflow:auto; margin:.6rem 0 0;
                font-size:.9rem; line-height:1.6; background:#f8fafc; padding:.8rem; border-radius:10px; }
    /* タブ: スティッキーな横並びナビ */
    .tabs { display:flex; gap:.35rem; flex-wrap:wrap; position:sticky; top:0; z-index:10;
            background:var(--bg); padding:.5rem 0; }
    .tab { background:transparent; color:var(--muted); font-weight:600; border:1px solid transparent; }
    .tab:hover { color:var(--accent); }
    .tab.active { background:#fff; color:var(--accent); border-color:var(--line); box-shadow:var(--shadow); }
    .panel { display:none; }
    .panel.active { display:block; animation:fade .2s ease; }
    @keyframes fade { from { opacity:0; transform:translateY(4px); } to { opacity:1; transform:none; } }
    .login-wrap { max-width:420px; margin:3rem auto; text-align:center; }
    .login-wrap h2 { font-size:1.5rem; }
    .calendar-grid { display:grid; grid-template-columns:repeat(7,1fr); gap:4px; text-align:center; }
    .calendar-cell { padding:.6rem 0; border-radius:8px; cursor:pointer; position:relative; }
    .calendar-cell:hover { background:var(--line); }
    .calendar-cell.active { background:var(--accent); color:#fff; }
    .calendar-cell .dot { width:5px; height:5px; background:var(--accent); border-radius:50%; position:absolute; bottom:4px; left:50%; transform:translateX(-50%); }
    .calendar-cell.active .dot { background:#fff; }
    .calendar-day-header { font-weight:600; color:var(--muted); font-size:.85rem; padding-bottom:.4rem; }
    hr { border:none; border-top:1px solid var(--line); margin:1.1rem 0; }
  </style>
</head>
<body>
  <main>
    <!-- ログイン画面: ボタンのみ。押すとフォームが出る -->
    <div id="login">
      <section class="card login-wrap">
        <h2 style="margin:.2rem 0">AIHelper</h2>
        <p class="muted">常時録音から課題・予定を整理し、締切前に通知します。</p>
        <div class="row" style="justify-content:center; margin-top:1rem">
          <button onclick="showForm('login')">ログイン</button>
          <button class="ghost" onclick="showForm('register')">新規登録</button>
        </div>
        <div id="authForm" style="display:none; margin-top:1.2rem; text-align:left">
          <h3 id="formTitle" style="margin:.2rem 0 .6rem"></h3>
          <input id="email" placeholder="メールアドレス" autocomplete="username" style="margin-bottom:.5rem">
          <input id="password" type="password" placeholder="パスワード(6文字以上)"
                 autocomplete="current-password" onkeydown="if(event.key==='Enter')submitAuth()">
          <div class="row" style="margin-top:.6rem">
            <button id="submitBtn" onclick="submitAuth()"></button>
            <button class="ghost small" onclick="hideForm()">戻る</button>
          </div>
          <p id="authState" class="muted"></p>
        </div>
      </section>
    </div>

    <!-- アプリ本体: ログイン後にタブ表示 -->
    <div id="app" style="display:none">
      <nav class="tabs">
        <button class="tab" data-tab="chat" onclick="showTab('chat')">チャット</button>
        <button class="tab" data-tab="tasks" onclick="showTab('tasks')">予定・課題</button>
        <button class="tab" data-tab="calendar" onclick="showTab('calendar')">カレンダー</button>
        <button class="tab" data-tab="summary" onclick="showTab('summary')">今日の要約</button>
        <button class="tab" data-tab="files" onclick="showTab('files')">ファイル</button>
        <button class="tab" data-tab="account" onclick="showTab('account')">アカウント</button>
      </nav>

      <section class="card panel" data-panel="chat">
        <h2>AIに聞く / 頼む</h2>
        <div id="chatlog" class="chatlog"></div>
        <div class="row">
          <input id="q" placeholder="例）今日の予定は？ / 来週月曜10時にゼミ入れといて"
                 onkeydown="if(event.key==='Enter')ask()">
          <button onclick="ask()">送信</button>
        </div>
        <p class="muted">「〜の予定入れといて」「〇〇の宿題が出てるらしい、登録して」「〇〇終わった」も実行できます。</p>
      </section>

      <section class="card panel" data-panel="tasks">
        <h2>課題・予定</h2>
        <div id="tasksCalendarEvents" style="margin-bottom:1rem; display:none">
          <h3 style="font-size:.9rem; margin:.4rem 0 .2rem">Google カレンダーの直近予定</h3>
          <div id="tasksCalendarEventsList" style="font-size:.85rem; padding:.6rem; background:#f8fafc; border-radius:8px; line-height:1.5"></div>
        </div>
        <div style="display:grid; gap:.5rem; margin-bottom:.7rem">
          <input id="taskSearch" placeholder="キーワード検索（内容・詳細）" oninput="renderTasks()">
          <div class="row">
            <select id="taskFilter" onchange="renderTasks()">
              <option value="pending">未完了のみ</option>
              <option value="active">期限内のみ（未期限切れ）</option>
              <option value="overdue">期限切れ</option>
              <option value="all">すべて</option>
            </select>
            <select id="taskType" onchange="renderTasks()">
              <option value="all">課題+予定</option>
              <option value="kadai">課題のみ</option>
              <option value="yotei">予定のみ</option>
            </select>
            <select id="taskSort" onchange="renderTasks()">
              <option value="due-asc">締切が近い順</option>
              <option value="due-desc">締切が遠い順</option>
              <option value="new">追加が新しい順</option>
            </select>
            <button class="ghost small" onclick="loadTasks()">更新</button>
          </div>
          <div class="row">
            <label class="muted">期間</label>
            <input id="taskFrom" type="date" onchange="renderTasks()" style="width:auto">
            <span class="muted">〜</span>
            <input id="taskTo" type="date" onchange="renderTasks()" style="width:auto">
            <button class="ghost small" onclick="clearTaskPeriod()">期間クリア</button>
          </div>
        </div>
        <div id="tasks"><p class="muted">読み込み中…</p></div>
        <details style="margin-top:.6rem">
          <summary class="muted">手動で追加</summary>
          <div class="grid2" style="margin-top:.5rem">
            <select id="t_type"><option value="kadai">課題</option><option value="yotei">予定</option></select>
            <input id="t_deadline" type="datetime-local">
          </div>
          <input id="t_content" placeholder="内容" style="margin-top:.5rem">
          <input id="t_details" placeholder="詳細（任意）" style="margin-top:.5rem">
          <button style="margin-top:.5rem" onclick="addTask()">追加</button>
        </details>
      </section>

      <section class="card panel" data-panel="calendar">
        <h2>カレンダー</h2>
        <div class="row" style="justify-content:space-between; margin-bottom:.8rem">
          <button class="ghost small" onclick="prevMonth()">‹ 前月</button>
          <strong id="calMonthTitle" style="font-size:1.1rem"></strong>
          <button class="ghost small" onclick="nextMonth()">翌月 ›</button>
        </div>
        <div class="calendar-grid" id="calendarDayHeaders">
          <div class="calendar-day-header">日</div><div class="calendar-day-header">月</div><div class="calendar-day-header">火</div>
          <div class="calendar-day-header">水</div><div class="calendar-day-header">木</div><div class="calendar-day-header">金</div>
          <div class="calendar-day-header">土</div>
        </div>
        <div class="calendar-grid" id="calendarCells" style="margin-bottom:1rem"></div>
        <hr>
        <h3 id="calSelectedDateTitle" style="font-size:.95rem; margin:.4rem 0 .6rem">選択した日の予定</h3>
        <div id="calSelectedEvents" class="muted">日付を選択してください。</div>
        <div id="calSelectedSummaryBox" class="card" style="display:none; margin-top:.8rem; background:#f8fafc">
          <h4 style="font-size:.85rem; margin:0 0 .4rem; font-weight:700">この日の要約</h4>
          <div id="calSelectedSummary" style="font-size:.85rem; line-height:1.5; white-space:pre-wrap"></div>
        </div>
      </section>

      <section class="card panel" data-panel="summary">
        <h2>今日の要約</h2>
        <div id="summary"><p class="muted">読み込み中…</p></div>
        <button class="ghost small" style="margin-top:.5rem" onclick="genSummary()">今すぐ生成し直す</button>
      </section>

      <section class="card panel" data-panel="files">
        <h2>資料の要約（PDF / TXT）</h2>
        <p class="muted">PDF か テキストをアップロードすると、その場で AI が要約して保存します。</p>
        <div class="row">
          <input type="file" id="docFile" accept=".pdf,.txt,application/pdf,text/plain">
          <button onclick="uploadDoc()">要約する</button>
          <span id="docState" class="muted"></span>
        </div>
        <div id="docList" style="margin-top:.8rem"></div>
        <hr>
        <h2>音声の文字起こし状況（PCワーカー処理）</h2>
        <p class="muted">端末からアップロードされた音声はサーバーでキュー化され、ローカルPCワーカーが順番に文字起こしします。</p>
        <div class="row" style="margin-bottom:.5rem">
          <button class="ghost small" onclick="loadAudioJobs();loadAudioWorkers()">更新</button>
        </div>
        <h3 style="font-size:.95rem; margin:.2rem 0 .4rem">処理に使うPC（クライアント）</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">
          音声を処理させるPCを選べます（複数選択可）。チェックを外したPCには新しいジョブを割り振りません。
          PC側クライアントの初回登録（表示名を決めてアカウントに紐付け）が済むと、ここに表示されます。
          他ユーザー提供の global PC は初期状態では使いません。任せてもよい場合だけチェックを入れてください（そのPCにあなたの音声がダウンロードされて処理されます）。
          CPU/メモリ/GPU はクライアントから3秒ごとに届く使用率です。
        </p>
        <div id="audioWorkers" style="margin-bottom:.8rem"><p class="muted">読み込み中…</p></div>
        <h3 style="font-size:.95rem; margin:.6rem 0 .4rem">未完了の音声（処理中・待機中・失敗）</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">まだ文字起こしが終わっていない音声を状態別に表示します（見出しをクリックで折りたたみ）。完了した音声は下の履歴に文字起こしとして並びます。処理に失敗した音声は自動で数回まで再割り振りされ、それでも失敗したものは「失敗」に残ります（音声ファイルは保持されるので「再試行」できます）。失敗分はチェックを付けてまとめて再実行・削除もできます。</p>
        <div id="audioJobs"><p class="muted">読み込み中…</p></div>
        <hr>
        <h2>文字起こしの履歴</h2>
        <div style="display:grid; gap:.5rem; margin-bottom:.7rem">
          <div class="row">
            <input id="trName" placeholder="ファイル名で絞り込み" oninput="renderTranscripts()" style="flex:1; min-width:180px">
            <select id="trFilter" onchange="renderTranscripts()" style="width:auto">
              <option value="all">すべて</option>
              <option value="analyzed">解析済みのみ</option>
              <option value="unanalyzed">未解析のみ</option>
            </select>
            <select id="trSort" onchange="renderTranscripts()" style="width:auto">
              <option value="updated-desc">更新が新しい順</option>
              <option value="updated-asc">更新が古い順</option>
              <option value="name-desc">ファイル名（新→旧）</option>
              <option value="name-asc">ファイル名（旧→新）</option>
              <option value="chars-desc">文字数が多い順</option>
              <option value="chars-asc">文字数が少ない順</option>
            </select>
          </div>
          <div class="row">
            <input id="trContains" placeholder="本文にこの文字列を含むファイルを探す（例: ゼミ）"
                   onkeydown="if(event.key==='Enter')searchTranscripts()" style="flex:1; min-width:180px">
            <button class="small" onclick="searchTranscripts()">本文検索</button>
            <button class="ghost small" onclick="clearTranscriptSearch()">解除</button>
            <span id="trSearchState" class="muted"></span>
          </div>
          <div class="row">
            <button class="small" id="bulkAnalyzeBtn" onclick="analyzeAllUnanalyzed()">未解析を一括解析</button>
            <span id="bulkAnalyzeState" class="muted"></span>
          </div>
        </div>
        <div id="transcripts"><p class="muted">読み込み中…</p></div>
      </section>

      <section class="card panel" data-panel="account">
        <h2>アカウント</h2>
        <p>ログイン中: <strong id="accEmail"></strong></p>
        <hr>
        <h3 style="font-size:.95rem; margin:.2rem 0 .6rem">Gemini API キー（AI機能に必須）</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">
          AIチャット・課題/予定の抽出・要約には、あなた自身の Gemini API キーが必要です。
          <a href="https://aistudio.google.com/apikey" target="_blank" rel="noopener">Google AI Studio</a>
          で無料発行し、ここに登録してください。キーは暗号化して保存され、あなたのAI処理にのみ使われます。
        </p>
        <input id="geminiKey" type="password" placeholder="AIza..." autocomplete="off">
        <div class="row" style="margin-top:.6rem">
          <button onclick="saveGeminiKey()">登録する</button>
          <button class="ghost" onclick="deleteGeminiKey()">削除</button>
          <span id="geminiKeyState" class="muted"></span>
        </div>
        <div style="margin-top:.8rem">
          <label style="display:flex; align-items:center; gap:.5rem; cursor:pointer; color:var(--ink)">
            <input type="checkbox" id="geminiAuto" style="width:auto" onchange="saveGeminiAuto(this.checked)">
            文字起こし完了時に自動で要約・課題/予定の抽出を実行する
          </label>
          <p class="muted" style="margin:.3rem 0 0">
            オフにすると自動では解析されず、「ファイル」タブの履歴で各ファイルの「解析する」ボタンを押したときだけ実行されます。
          </p>
          <span id="geminiAutoState" class="muted"></span>
        </div>
        <hr>
        <h3 style="font-size:.95rem; margin:.2rem 0 .6rem">パスワード変更</h3>
        <input id="curpw" type="password" placeholder="現在のパスワード" autocomplete="current-password" style="margin-bottom:.5rem">
        <input id="newpw" type="password" placeholder="新しいパスワード(6文字以上)" autocomplete="new-password">
        <div class="row" style="margin-top:.6rem">
          <button onclick="changePassword()">変更する</button>
          <span id="pwState" class="muted"></span>
        </div>
        <hr>
        <h3 style="font-size:.95rem; margin:.2rem 0 .6rem">Moodle 連携（提出物・予定の取り込み）</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">
          Moodle のカレンダー › 書き出し › 「カレンダーの URL を取得」で得た iCal URL を貼り付けてください。
          取り込んだ提出物・予定は課題一覧・リマインドに反映されます。
        </p>
        <input id="moodleUrl" placeholder="https://…/calendar/export_execute.php?...">
        <div class="row" style="margin-top:.6rem">
          <button onclick="saveMoodle()">保存</button>
          <button class="ghost" onclick="syncMoodle()">今すぐ同期</button>
          <span id="moodleState" class="muted"></span>
        </div>
        <hr>
        <h3 style="font-size:.95rem; margin:.2rem 0 .6rem">Waseda アカウント連携（時間割の取り込み）</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">
          MyWaseda のログイン情報を保存すると、科目登録（時間割）を自動取得できます。
          パスワードは暗号化して保存され、時間割取得のログインにのみ使われます。
        </p>
        <input id="wasedaUser" placeholder="Waseda ID（例: xxxx@akane.waseda.jp）" autocomplete="off" style="margin-bottom:.5rem">
        <input id="wasedaPw" type="password" placeholder="Waseda パスワード" autocomplete="new-password">
        <div class="row" style="margin-top:.6rem">
          <button onclick="saveWaseda()">保存</button>
          <button id="wasedaSyncBtn" class="ghost" onclick="syncWaseda()">時間割を取り込む</button>
          <button class="ghost" onclick="clearWaseda()">連携解除</button>
          <span id="wasedaState" class="muted"></span>
        </div>
        <div id="wasedaSyncBox" style="display:none; margin-top:.6rem">
          <div style="height:6px; background:#e5e7eb; border-radius:999px; overflow:hidden">
            <div id="wasedaSyncBar" style="height:100%; width:30%; background:var(--accent); border-radius:999px;
                 animation: slide 1.2s ease-in-out infinite alternate"></div>
          </div>
          <p id="wasedaSyncMsg" class="muted" style="margin:.4rem 0 0"></p>
          <details style="margin-top:.4rem">
            <summary class="muted" style="cursor:pointer; font-size:.85rem">実行ログを表示</summary>
            <pre id="wasedaSyncLog" style="max-height:220px; overflow:auto; background:#f9fafb;
                 border:1px solid #e5e7eb; border-radius:6px; padding:.5rem; font-size:.72rem;
                 white-space:pre-wrap; word-break:break-all; margin:.3rem 0 0"></pre>
          </details>
        </div>
        <div id="wasedaCoursesBox" style="display:none; margin-top:.8rem">
          <h4 style="font-size:.9rem; margin:.4rem 0 .2rem">取り込んだ時間割</h4>
          <div id="wasedaCourses" style="font-size:.85rem; margin-bottom:.5rem; line-height:1.6"></div>
          <div class="row">
            <button class="ghost small" onclick="syncWasedaCoursesToGoogle()">Google カレンダーに同期</button>
            <span id="wasedaSyncGoogleState" class="muted"></span>
          </div>
        </div>
        <style>@keyframes slide { from { margin-left:0 } to { margin-left:70% } }</style>
        <hr>
        <h3 style="font-size:.95rem; margin:.2rem 0 .6rem">Google カレンダー連携</h3>
        <p class="muted" style="margin:.2rem 0 .5rem">
          Google アカウントを連携すると、課題・予定の締切を Google カレンダーに登録したり、
          直近の予定を表示できます。複数アカウントを連携できます。
        </p>
        <div id="googleAccounts"><p class="muted">未連携です。</p></div>
        <div class="row" style="margin-top:.6rem">
          <button onclick="connectGoogle()">Google アカウントを連携</button>
          <span id="googleState" class="muted"></span>
        </div>
        <div id="googleEvents" style="margin-top:.6rem"></div>
        <hr>
        <button class="ghost" onclick="logout()">ログアウト</button>
      </section>
    </div><!-- /#app -->
  </main>

  <div id="modal" class="modalbg" style="display:none" onclick="if(event.target===this)closeModal()">
    <div class="modalbox">
      <div class="row" style="justify-content:space-between">
        <strong id="modalTitle"></strong>
        <button class="ghost small" onclick="closeModal()">閉じる</button>
      </div>
      <pre id="modalBody" class="modalpre"></pre>
    </div>
  </div>

  <script>
    const $ = (id) => document.getElementById(id);
    let auth = JSON.parse(localStorage.getItem('mb_auth') || '{}');
    function headers(){ return { 'Content-Type':'application/json',
      'X-Account-Email': auth.email||'', 'Authorization':'Bearer '+(auth.token||'') }; }
    const AUTO_REFRESH_MS = 15000;
    const GOOGLE_REFRESH_MS = 60000;
    let activeTab = 'chat';
    let autoRefreshTimer = null;
    let audioJobsTimer = null;
    let audioWorkersTimer = null;
    let autoRefreshBusy = false;
    let lastGoogleRefresh = 0;
    function activeControl(){
      const el = document.activeElement;
      return el && ['INPUT','TEXTAREA','SELECT'].includes(el.tagName);
    }
    function startAutoRefresh(){
      stopAutoRefresh();
      autoRefreshTimer = setInterval(() => refreshCurrentTab(), AUTO_REFRESH_MS);
      document.addEventListener('visibilitychange', refreshWhenVisible);
    }
    function stopAutoRefresh(){
      if(autoRefreshTimer) clearInterval(autoRefreshTimer);
      autoRefreshTimer = null;
      if(audioJobsTimer) clearTimeout(audioJobsTimer);
      audioJobsTimer = null;
      if(audioWorkersTimer) clearTimeout(audioWorkersTimer);
      audioWorkersTimer = null;
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    }
    function refreshWhenVisible(){
      if(!document.hidden) refreshCurrentTab(true);
    }
    async function refreshGoogleEvents(force){
      const now = Date.now();
      if(!force && now - lastGoogleRefresh < GOOGLE_REFRESH_MS) return;
      lastGoogleRefresh = now;
      await loadGoogleEvents();
    }
    async function refreshCurrentTab(force){
      if(!auth.email || autoRefreshBusy) return;
      if(!force && (document.hidden || activeControl())) return;
      autoRefreshBusy = true;
      try {
        if(activeTab === 'chat') await loadChatHistory();
        else if(activeTab === 'tasks') { await loadTasks(); await refreshGoogleEvents(false); }
        else if(activeTab === 'calendar') { await loadTasks(); await refreshGoogleEvents(false); }
        else if(activeTab === 'summary') await loadSummary();
        else if(activeTab === 'files') { await loadDocs(); await loadAudioWorkers(); await loadAudioJobs(); await loadTranscripts(); }
      } catch(e) {
      } finally {
        autoRefreshBusy = false;
      }
    }

    let allGoogleEvents = [];
    let allCourses = [];
    let calYear = new Date().getFullYear();
    let calMonth = new Date().getMonth() + 1;
    // sv-SE gives yyyy-mm-dd format natively
    let calSelectedDate = new Date().toLocaleDateString('sv-SE').slice(0,10);
    
    function prevMonth(){ calMonth--; if(calMonth<1){ calMonth=12; calYear--; } renderCalendar(); }
    function nextMonth(){ calMonth++; if(calMonth>12){ calMonth=1; calYear++; } renderCalendar(); }

    const DOW_JA = ['日','月','火','水','木','金','土'];
    // 学期の大まかな開始日・終了日（早稲田の一般的な目安。公式の学事暦とはズレる場合あり）。
    // /api/google/sync-courses の「春学期は〜7/31, それ以外は翌1/31まで」という既存の目安に合わせた。
    function courseTermRange(term, refDate){
      const m = refDate.getMonth() + 1;
      const ay = m >= 4 ? refDate.getFullYear() : refDate.getFullYear() - 1; // 学年度の開始年（4月始まり）
      const d = (y,mo,day) => y + '-' + String(mo).padStart(2,'0') + '-' + String(day).padStart(2,'0');
      switch(term){
        case '通年': return { start: d(ay,4,1), end: d(ay+1,1,31) };
        case '春': return { start: d(ay,4,1), end: d(ay,7,31) };
        case '春Q': return { start: d(ay,4,1), end: d(ay,6,15) };
        case '夏Q': return { start: d(ay,6,16), end: d(ay,7,31) };
        case '夏季集中': return { start: d(ay,8,1), end: d(ay,9,15) };
        case '秋': return { start: d(ay,9,1), end: d(ay+1,1,31) };
        case '秋Q': return { start: d(ay,9,1), end: d(ay,11,15) };
        case '冬Q': return { start: d(ay,11,16), end: d(ay+1,1,31) };
        case '冬季集中': return { start: d(ay+1,1,1), end: d(ay+1,1,31) };
        default: return { start: d(ay,4,1), end: d(ay+1,1,31) }; // 不明な学期は通年扱いで広めに表示
      }
    }
    // この科目が指定日(YYYY-MM-DD)に該当するか。
    // 曜日・時限が判明していれば毎週その曜日、不明（オンデマンド等）なら学期中の全日に配置する。
    function courseOccursOn(course, dateStr){
      const dateObj = new Date(dateStr + 'T00:00:00');
      const range = courseTermRange(course.term || '', dateObj);
      if(dateStr < range.start || dateStr > range.end) return false;
      if(!course.day) return true; // オンデマンド等: 学期中は毎日
      return DOW_JA[dateObj.getDay()] === course.day;
    }

    function renderCalendar(){
      if(!$('calMonthTitle')) return;
      $('calMonthTitle').textContent = calYear + '年' + calMonth + '月';
      const firstDay = new Date(calYear, calMonth - 1, 1).getDay();
      const lastDate = new Date(calYear, calMonth, 0).getDate();
      let html = '';
      for(let i=0; i<firstDay; i++){
        html += '<div class="calendar-cell muted" style="opacity:.3; cursor:default"></div>';
      }
      const pad = (n) => String(n).padStart(2,'0');
      const dateMap = {};
      allTasks.forEach(t => {
        if(t.deadline_at){ dateMap[t.deadline_at.slice(0,10)] = true; }
      });
      allGoogleEvents.forEach(ev => {
        if(ev.startMillis){ dateMap[new Date(ev.startMillis).toLocaleDateString('sv-SE').slice(0,10)] = true; }
      });
      for(let d=1; d<=lastDate; d++){
        const dateStr = calYear + '-' + pad(calMonth) + '-' + pad(d);
        const isActive = dateStr === calSelectedDate;
        const hasEvents = dateMap[dateStr] || allCourses.some(c => courseOccursOn(c, dateStr));
        html += '<div class="calendar-cell ' + (isActive ? 'active' : '') + '" onclick="selectCalDate(\\'' + dateStr + '\\')">' + d + (hasEvents ? '<span class="dot"></span>' : '') + '</div>';
      }
      $('calendarCells').innerHTML = html;
      renderSelectedDateEvents();
    }
    function selectCalDate(d){ calSelectedDate = d; renderCalendar(); }
    async function renderSelectedDateEvents(){
      $('calSelectedDateTitle').textContent = calSelectedDate + ' の予定';
      const dayItems = [];
      allTasks.forEach(t => {
        if(t.deadline_at && t.deadline_at.slice(0,10) === calSelectedDate){
          const norm = t.deadline_at.replace('T',' ');
          const time = (!t.date_only && norm.length >= 16) ? norm.substring(11,16) : '終日';
          dayItems.push({ time, kind: 'task', task: t });
        }
      });
      allGoogleEvents.forEach(ev => {
        if(ev.startMillis){
          const dStr = new Date(ev.startMillis).toLocaleDateString('sv-SE').slice(0,10);
          if(dStr === calSelectedDate){
            const norm = ev.whenText.replace('T',' ');
            const start = norm.length >= 16 ? norm.substring(11,16) : '終日';
            const endNorm = (ev.endText || '').replace('T',' ');
            const end = endNorm.length >= 16 ? endNorm.substring(11,16) : '';
            const time = (start !== '終日' && end) ? start + '〜' + end : start;
            dayItems.push({ time, kind: 'calendar', title: '[カレンダー] ' + ev.title });
          }
        }
      });
      // 早稲田大学の公式時間割（100分授業。/api/google/sync-courses と同じ定義）。表示のソート・ラベル用。
      const PERIOD_TIMES = {
        1:{s:'08:50',e:'10:30'},2:{s:'10:40',e:'12:20'},3:{s:'13:10',e:'14:50'},
        4:{s:'15:05',e:'16:45'},5:{s:'17:00',e:'18:40'},6:{s:'18:55',e:'20:35'},7:{s:'20:45',e:'22:25'}
      };
      allCourses.forEach(c => {
        if(courseOccursOn(c, calSelectedDate)){
          // start_time/end_time は複数時限にまたがる授業の時限番号。単一時限は period。
          const startP = PERIOD_TIMES[c.start_time || c.period];
          const endP = PERIOD_TIMES[c.end_time || c.period];
          const time = startP ? (endP ? startP.s + '〜' + endP.e : startP.s) : '終日';
          dayItems.push({ time, kind: 'course', course: c });
        }
      });
      dayItems.sort((a,b) => (a.time === '終日' ? '00:00' : a.time).localeCompare(b.time === '終日' ? '00:00' : b.time));
      if(!dayItems.length){
        $('calSelectedEvents').innerHTML = '<p class="muted">予定はありません。</p>';
      } else {
        $('calSelectedEvents').innerHTML = dayItems.map(it => {
          if(it.kind === 'task') return dayTaskItemHtml(it.task, it.time);
          if(it.kind === 'course') return dayCourseItemHtml(it.course, it.time);
          return '<div class="card" style="margin:.3rem 0; padding:.6rem .8rem; display:flex; gap:.8rem; background:#fff; border:1px solid var(--line); border-radius:10px">' +
            '<strong style="color:var(--accent); min-width:40px">' + it.time + '</strong>' +
            '<span>' + escapeHtml(it.title) + '</span>' +
            '</div>';
        }).join('');
      }
      $('calSelectedSummaryBox').style.display = 'none';
      try {
        const r = await fetch('/api/summary/' + calSelectedDate, {headers: headers()});
        const j = await r.json();
        if(j.ok && j.summary){
          $('calSelectedSummaryBox').style.display = '';
          $('calSelectedSummary').textContent = j.summary;
        }
      } catch(e){}
    }

    // 選択した日の「授業」項目。編集フォームは courseRowHtml を 'day' prefix で共用する
    // （アカウント画面の一覧と同時に描画されても要素 id が衝突しないようにするため）。
    function dayCourseItemHtml(c, time){
      return '<div style="margin:.3rem 0">' +
        '<div class="muted" style="font-size:.75rem; margin:0 0 .1rem .1rem">' + time + '</div>' +
        courseRowHtml(c, 'day') +
        '</div>';
    }

    // 選択した日の「課題・予定」項目の編集。
    let taskEditingId = null;
    function isoForInput(deadlineAt){
      return deadlineAt ? deadlineAt.replace(' ', 'T').slice(0,16) : '';
    }
    function dayTaskItemHtml(t, time){
      const label = t.type === 'yotei' ? '予定' : '課題';
      if(taskEditingId === t.id){
        return '<div class="card" style="margin:.3rem 0; padding:.5rem .7rem; background:#fff; border:1px solid var(--line); border-radius:10px">' +
          '<div class="row" style="gap:.4rem; flex-wrap:wrap">' +
          '<select id="te_type_' + t.id + '" style="width:90px">' +
          '<option value="kadai"' + (t.type!=='yotei'?' selected':'') + '>課題</option>' +
          '<option value="yotei"' + (t.type==='yotei'?' selected':'') + '>予定</option>' +
          '</select>' +
          '<input id="te_content_' + t.id + '" value="' + escapeHtml(t.content) + '" placeholder="内容" style="flex:1; min-width:160px">' +
          '<input id="te_deadline_' + t.id + '" type="datetime-local" value="' + isoForInput(t.deadline_at) + '" style="width:180px">' +
          '</div>' +
          '<input id="te_details_' + t.id + '" value="' + escapeHtml(t.details || '') + '" placeholder="詳細（任意）" style="width:100%; margin-top:.4rem">' +
          '<div class="row" style="margin-top:.4rem; gap:.4rem">' +
          '<button class="small" onclick="saveTaskEdit(' + t.id + ')">保存</button>' +
          '<button class="ghost small" onclick="cancelTaskEdit()">キャンセル</button>' +
          '</div></div>';
      }
      return '<div class="card" style="margin:.3rem 0; padding:.6rem .8rem; display:flex; gap:.8rem; justify-content:space-between; align-items:center; background:#fff; border:1px solid var(--line); border-radius:10px">' +
        '<div style="display:flex; gap:.8rem; align-items:center; min-width:0"><strong style="color:var(--accent); min-width:40px">' + time + '</strong><span>[' + label + '] ' + escapeHtml(t.content) + '</span></div>' +
        '<span class="row" style="gap:.3rem; flex-shrink:0">' +
        '<button class="ghost small" onclick="startTaskEdit(' + t.id + ')">編集</button>' +
        '<button class="ghost small" onclick="delTask(' + t.id + ')">削除</button>' +
        '</span></div>';
    }
    function startTaskEdit(id){ taskEditingId = id; renderSelectedDateEvents(); }
    function cancelTaskEdit(){ taskEditingId = null; renderSelectedDateEvents(); }
    async function saveTaskEdit(id){
      const content = $('te_content_' + id).value.trim();
      if(!content){ alert('内容を入力してください'); return; }
      const body = {
        type: $('te_type_' + id).value,
        content,
        details: $('te_details_' + id).value.trim(),
        deadline: $('te_deadline_' + id).value,
      };
      try {
        const r = await fetch('/api/tasks/' + id, {method:'PATCH', headers:headers(), body:JSON.stringify(body)});
        const j = await r.json();
        if(j.ok){ taskEditingId = null; await loadTasks(); }
        else alert('保存に失敗しました: ' + (j.error || ''));
      } catch(e){ alert('通信エラー'); }
    }

    // ---- 認証・画面切替 ----
    let authMode = 'login';
    function showForm(mode){
      authMode = mode;
      $('formTitle').textContent = mode==='register' ? '新規登録' : 'ログイン';
      $('submitBtn').textContent = mode==='register' ? '登録する' : 'ログイン';
      $('authForm').style.display = ''; $('authState').textContent = '';
      $('email').focus();
    }
    function hideForm(){ $('authForm').style.display='none'; $('authState').textContent=''; }
    async function submitAuth(){
      const email = $('email').value.trim(), password = $('password').value;
      if(!email || !password){ $('authState').textContent='メールとパスワードを入力'; return; }
      const path = authMode==='register' ? '/api/register' : '/api/login';
      const r = await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},
        body: JSON.stringify({email, password})});
      const j = await r.json();
      if(j.ok){ auth = {email:j.email, token:j.token}; localStorage.setItem('mb_auth', JSON.stringify(auth)); onAuthed(); }
      else $('authState').textContent = '✗ ' + (j.error || (authMode==='register'?'登録失敗':'ログイン失敗'));
    }
    function showTab(name){
      activeTab = name;
      localStorage.setItem('mb_tab', name);
      document.querySelectorAll('.tab').forEach(b=>b.classList.toggle('active', b.dataset.tab===name));
      document.querySelectorAll('.panel').forEach(p=>p.classList.toggle('active', p.dataset.panel===name));
      refreshCurrentTab(true);
    }
    function initAuth(){ if(auth.email && auth.token) onAuthed(); }
    function onAuthed(){
      $('login').style.display = 'none';
      $('app').style.display = '';
      $('accEmail').textContent = auth.email || '';
      const savedTab = localStorage.getItem('mb_tab');
      showTab(document.querySelector('.tab[data-tab="'+savedTab+'"]') ? savedTab : 'chat');
      loadAll();
      startAutoRefresh();
      // Google OAuth から戻ってきた直後は、アカウントタブを開いて結果を表示する。
      const gq = new URLSearchParams(location.search).get('google');
      if(gq){
        history.replaceState(null, '', location.pathname);
        showTab('account');
        $('googleState').textContent =
          gq==='linked' ? '✓ Google アカウントを連携しました' :
          gq==='denied' ? '✗ 連携がキャンセルされました' :
          gq==='expired' ? '✗ 時間切れです。もう一度お試しください' : '✗ 連携に失敗しました';
      }
    }
    function logout(){
      stopAutoRefresh();
      auth = {}; localStorage.removeItem('mb_auth');
      $('app').style.display = 'none'; $('login').style.display = '';
      $('password').value = ''; hideForm();
    }
    async function changePassword(){
      const currentPassword = $('curpw').value, newPassword = $('newpw').value;
      if(!currentPassword || !newPassword){ $('pwState').textContent='両方入力してください'; return; }
      const r = await fetch('/api/change-password',{method:'POST',headers:headers(),
        body: JSON.stringify({currentPassword, newPassword})});
      const j = await r.json();
      if(j.ok){ $('pwState').textContent='✓ 変更しました'; $('curpw').value=$('newpw').value=''; }
      else $('pwState').textContent = '✗ ' + (j.error||'変更失敗');
    }
    function loadAll(){ loadTasks(); loadSummary(); loadMoodle(); loadWaseda(); loadDocs(); loadAudioWorkers(); loadAudioJobs(); loadTranscripts(); loadGoogle(); loadChatHistory(); loadGeminiKey(); loadGeminiAuto(); }

    // ---- Google カレンダー連携（Web OAuth） ----
    let googleAccounts = [];
    function googleDefault(){
      const d = localStorage.getItem('mb_gdefault');
      return googleAccounts.includes(d) ? d : (googleAccounts[0]||'');
    }
    function setGoogleDefault(e){ localStorage.setItem('mb_gdefault', e); renderGoogleAccounts(); }
    function renderGoogleAccounts(){
      if(!googleAccounts.length){
        $('googleAccounts').innerHTML = '<p class="muted">未連携です。</p>';
      } else {
        const def = googleDefault();
        $('googleAccounts').innerHTML = googleAccounts.map(e =>
          '<div class="row" style="margin:.2rem 0; gap:.4rem">'+
          '<label style="flex:1"><input type="radio" name="gdef" '+(e===def?'checked':'')+
          ' onchange="setGoogleDefault(\\\''+escapeHtml(e)+'\\\')"> '+escapeHtml(e)+'</label>'+
          '<button class="ghost small" onclick="unlinkGoogle(\\\''+escapeHtml(e)+'\\\')">解除</button></div>').join('') +
          (googleAccounts.length>1 ? '<p class="muted" style="margin:.2rem 0">選択中のアカウントが「カレンダー登録」の登録先になります。</p>' : '');
      }
      renderTasks(); // 連携状態でタスク行の「カレンダー登録」ボタン表示が変わる
    }
    async function loadGoogle(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/google/accounts',{headers:headers()});
        const j = await r.json();
        if(!j.ok){ $('googleState').textContent = '✗ ' + (j.error||'取得失敗'); return; }
        googleAccounts = j.accounts || [];
        if(!j.configured && !googleAccounts.length){
          $('googleState').textContent = 'サーバー側の設定（GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET）が必要です';
        }
        renderGoogleAccounts();
        loadGoogleEvents();
      } catch(e){}
    }
    async function loadGoogleEvents(){
      lastGoogleRefresh = Date.now();
      // Fetch events from server even if no Google accounts are linked,
      // because the server also merges and returns smartphone local calendar events.
      try {
        const r = await fetch('/api/google/events',{headers:headers()});
        const j = await r.json();
        if(!j.ok){
          const errMsg = '<p class="muted">'+escapeHtml(j.error||'予定取得失敗')+'</p>';
          $('googleEvents').innerHTML = errMsg;
          return;
        }
        allGoogleEvents = j.events || [];
        renderCalendar();
        const evs = (j.events||[]).slice(0,8);
        const html = evs.length ? evs.map(ev =>
          '<div>・'+escapeHtml(ev.whenText)+'　'+escapeHtml(ev.title)+
          (googleAccounts.length>1 ? ' <span class="muted">('+escapeHtml((ev.accountEmail||'').split('@')[0])+')</span>' : '')+
          '</div>').join('')
        : '<p class="muted">直近の予定はありません。</p>';
        $('googleEvents').innerHTML = '<p class="muted" style="margin:.4rem 0 .2rem">直近の予定</p>' + html + (j.error ? '<p class="muted">'+escapeHtml(j.error)+'</p>' : '');
        if($('tasksCalendarEvents')){
          if(evs.length){
            $('tasksCalendarEvents').style.display = '';
            $('tasksCalendarEventsList').innerHTML = html;
          } else {
            $('tasksCalendarEvents').style.display = 'none';
          }
        }
      } catch(e){}
    }
    async function connectGoogle(){
      $('googleState').textContent = '';
      const r = await fetch('/api/google/auth-url',{headers:headers()});
      const j = await r.json();
      if(!j.ok){ $('googleState').textContent = '✗ ' + (j.error||'連携を開始できません'); return; }
      location.href = j.url; // Google の同意画面へ（戻り先は /?google=linked）
    }
    async function unlinkGoogle(email){
      await fetch('/api/google/unlink',{method:'POST',headers:headers(),
        body:JSON.stringify({googleEmail:email})});
      $('googleState').textContent = email + ' の連携を解除しました';
      loadGoogle();
    }
    async function addToCalendar(id){
      const t = allTasks.find(x=>x.id===id); if(!t) return;
      const r = await fetch('/api/google/add-event',{method:'POST',headers:headers(),
        body:JSON.stringify({googleEmail:googleDefault(), content:t.content,
          deadline:t.deadline_at, dateOnly:!!t.date_only})});
      const j = await r.json();
      if(j.ok){ $('googleState').textContent = '✓ 「'+t.content+'」を '+j.googleEmail+' に登録しました'; loadGoogleEvents(); }
      else alert('カレンダー登録失敗: ' + (j.error||''));
    }

    // ---- 音声ワーカーPC（クライアント）選択 ----
    // クライアントは3秒ごとに使用率を送ってくるので、filesタブ表示中は
    // こちらも3秒ごとに再取得して最新の値を見せる。
    function meterCell(pct, fresh){
      if(pct===null || pct===undefined || !fresh) return '<span class="muted">—</span>';
      const v = Math.round(Number(pct));
      const color = v>=90 ? '#b42318' : v>=70 ? '#b7791f' : '#117a37';
      return '<div style="min-width:70px"><span style="font-variant-numeric:tabular-nums">'+v+'%</span>'+
        '<div style="height:4px;border-radius:2px;background:#e5eaf1;margin-top:2px">'+
        '<div style="height:4px;border-radius:2px;width:'+Math.min(v,100)+'%;background:'+color+'"></div></div></div>';
    }
    async function loadAudioWorkers(){
      if(!auth.email) return;
      if(audioWorkersTimer) clearTimeout(audioWorkersTimer);
      audioWorkersTimer = null;
      try {
        const r = await fetch('/api/audio/workers',{headers:headers()});
        const j = await r.json();
        if(!j.ok){ $('audioWorkers').innerHTML='<p class="muted">'+escapeHtml(j.error||'取得失敗')+'</p>'; return; }
        if(!j.workers.length){
          $('audioWorkers').innerHTML='<p class="muted">まだクライアントPCが接続していません。PC側で audio-worker を起動すると自動でここに表示されます。</p>';
          return;
        }
        const rows = j.workers.map(w =>
          '<tr><td><label style="display:flex;align-items:center;gap:.4rem;margin:0;cursor:pointer">'+
            '<input type="checkbox" '+(w.allowed?'checked':'')+' onchange="setWorkerAllowed('+w.id+',this.checked)">'+
            '#'+w.id+'</label></td>'+
          '<td>'+escapeHtml(w.name)+
            (w.owned && w.ip ? '<div class="muted" style="font-size:.8rem">'+escapeHtml(w.ip)+'</div>' : '')+'</td>'+
          '<td>'+(w.mode==='global'
            ? '<span style="color:#1f6feb">global</span>'+(w.owned?'':'<div class="muted" style="font-size:.8rem">他ユーザー提供</div>')
            : '<span class="muted">private</span>')+'</td>'+
          '<td>'+meterCell(w.cpuPct, w.metricsFresh)+'</td>'+
          '<td>'+meterCell(w.memPct, w.metricsFresh)+'</td>'+
          '<td>'+meterCell(w.gpuPct, w.metricsFresh)+'</td>'+
          '<td>'+(w.online
            ? '<span style="color:#117a37">接続中</span>'
            : '<span class="muted">'+(w.lastSeenAt ? new Date(w.lastSeenAt).toLocaleString('ja-JP') : '未接続')+'</span>')+'</td>'+
          '<td>'+(w.owned
            ? '<span class="row" style="gap:.3rem">'+
              '<button class="ghost small" onclick="renameWorker('+w.id+')">名前変更</button>'+
              '<button class="ghost small" onclick="deleteWorker('+w.id+')">削除</button>'+
            '</span>'
            : '')+'</td></tr>').join('');
        $('audioWorkers').innerHTML =
          '<table><thead><tr><th>処理する</th><th>名前</th><th>種別</th><th>CPU</th><th>メモリ</th><th>GPU</th><th>最終接続</th><th></th></tr></thead><tbody>'+rows+'</tbody></table>';
      } catch(e){}
      finally {
        if(activeTab==='files' && !document.hidden){
          audioWorkersTimer = setTimeout(loadAudioWorkers, 3000);
        }
      }
    }
    async function setWorkerAllowed(id, allowed){
      try {
        await fetch('/api/audio/workers/'+id,{method:'POST',headers:headers(),body:JSON.stringify({allowed})});
      } finally { loadAudioWorkers(); }
    }
    async function renameWorker(id){
      const name = prompt('このPCの表示名を入力してください');
      if(!name) return;
      await fetch('/api/audio/workers/'+id,{method:'POST',headers:headers(),body:JSON.stringify({name})});
      loadAudioWorkers();
    }
    async function deleteWorker(id){
      if(!confirm('このクライアントを一覧から削除しますか？（同じPCが再接続すると新しいIDで登録されます）')) return;
      await fetch('/api/audio/workers/'+id,{method:'DELETE',headers:headers()});
      loadAudioWorkers();
    }

    // ---- 音声ジョブ状況（処理中・待機中・失敗を状態別の折りたたみで表示） ----
    const AUDIO_STATUS = { queued:'待機中', processing:'処理中', error:'失敗' };
    // 各セクションの開閉状態。自動更新（15秒ごとの再描画）で畳んだものが開き直さない
    // ように覚えておく。失敗は件数が嵩みやすいので初期状態では畳んでおく。
    const audioJobsOpen = { processing:true, queued:true, error:false };
    // 失敗一覧でチェックされたジョブID。15秒ごとの自動再描画でも選択が消えないよう保持する。
    const audioJobsChecked = new Set();
    async function loadAudioJobs(){
      if(!auth.email) return;
      if(audioJobsTimer) clearTimeout(audioJobsTimer);
      audioJobsTimer = null;
      try {
        const r = await fetch('/api/audio/jobs?active=1&limit=100',{headers:headers()});
        const j = await r.json();
        if(!j.ok){ $('audioJobs').innerHTML='<p class="muted">'+escapeHtml(j.error||'取得失敗')+'</p>'; return; }
        if(!j.jobs.length){ audioJobsChecked.clear(); $('audioJobs').innerHTML='<p class="muted">未完了の音声はありません。</p>'; return; }
        // 失敗一覧のチェックは一覧に残っているジョブだけ有効（再試行・削除済みは外す）。
        const errIds = new Set(j.jobs.filter(a => a.status==='error').map(a => a.id));
        for(const id of [...audioJobsChecked]) if(!errIds.has(id)) audioJobsChecked.delete(id);
        const row = (a, withCk) => '<tr>'+
          (withCk ? '<td><input type="checkbox" class="audioJobCk" value="'+a.id+'"'+(audioJobsChecked.has(a.id)?' checked':'')+' onchange="audioJobCkChange(this)"></td>' : '')+
          '<td>'+escapeHtml(a.filename)+'</td>'+
          '<td class="num">'+Math.round((a.size_bytes||0)/1024/1024*10)/10+' MB</td>'+
          '<td>'+((a.attempts||0)>1 ? a.attempts+'回目' : '')+
            (a.error?'<div class="muted">'+escapeHtml(a.error)+'</div>':'')+
            (a.status==='error' ? '<div><button class="ghost small" onclick="retryAudioJob('+a.id+')">再試行</button></div>' : '')+'</td>'+
          '<td>'+(a.worker_name ? escapeHtml(a.worker_name) : (a.claimed_by ? '#'+a.claimed_by : ''))+'</td>'+
          '<td>'+new Date(a.updated_at).toLocaleString('ja-JP')+'</td></tr>';
        const html = ['processing','queued','error'].map(st => {
          const jobs = j.jobs.filter(a => a.status===st);
          if(!jobs.length) return '';
          const withCk = st==='error';
          // 失敗だけチェック列と一括操作（全選択 / まとめて再実行 / まとめて削除）を付ける。
          const toolbar = withCk
            ? '<div style="display:flex; gap:.5rem; align-items:center; flex-wrap:wrap; margin:.3rem 0">'+
              '<button class="ghost small" onclick="retryCheckedAudioJobs()">チェックした音声をまとめて再実行</button>'+
              '<button class="ghost small" onclick="deleteCheckedAudioJobs()">チェックした音声を削除</button>'+
              '<span class="muted" id="audioJobCkCount"></span></div>'
            : '';
          return '<details data-st="'+st+'"'+(audioJobsOpen[st]?' open':'')+' style="margin:.3rem 0">'+
            '<summary style="cursor:pointer; font-weight:600; padding:.2rem 0">'+AUDIO_STATUS[st]+' ('+jobs.length+'件)</summary>'+toolbar+
            '<table><thead><tr>'+(withCk?'<th><input type="checkbox" id="audioJobCkAll" title="全部チェック" onchange="toggleAllAudioJobCks(this.checked)"></th>':'')+
            '<th>ファイル</th><th>サイズ</th><th>状況</th><th>処理PC</th><th>更新</th></tr></thead><tbody>'+
            jobs.map(a => row(a, withCk)).join('')+'</tbody></table></details>';
        }).join('');
        $('audioJobs').innerHTML = html;
        updateAudioJobCkUi();
        // 開閉状態を覚える（自動更新後の再描画にも反映される）。
        $('audioJobs').querySelectorAll('details').forEach(d => {
          d.addEventListener('toggle', () => { audioJobsOpen[d.dataset.st] = d.open; });
        });
        // 未完了ジョブがあれば少し待って自動更新（完了すると一覧から消え、履歴側に現れる）。
        if(j.jobs.some(a => a.status==='queued'||a.status==='processing')) audioJobsTimer = setTimeout(loadAudioJobs, 15000);
      } catch(e){}
    }
    async function retryAudioJob(id){
      try {
        const r = await fetch('/api/audio/jobs/'+id+'/retry',{method:'POST',headers:headers()});
        const j = await r.json();
        if(!j.ok){ alert('再試行できません: '+(j.error||'')); return; }
      } catch(e){ alert('再試行に失敗しました'); return; }
      loadAudioJobs();
    }
    function audioJobCkChange(el){
      const id = Number(el.value);
      if(el.checked) audioJobsChecked.add(id); else audioJobsChecked.delete(id);
      updateAudioJobCkUi();
    }
    function toggleAllAudioJobCks(on){
      document.querySelectorAll('#audioJobs .audioJobCk').forEach(el => {
        el.checked = on;
        const id = Number(el.value);
        if(on) audioJobsChecked.add(id); else audioJobsChecked.delete(id);
      });
      updateAudioJobCkUi();
    }
    function updateAudioJobCkUi(){
      const count = $('audioJobCkCount');
      if(count) count.textContent = audioJobsChecked.size ? audioJobsChecked.size+'件選択中' : '';
      const all = $('audioJobCkAll');
      if(all){
        const boxes = document.querySelectorAll('#audioJobs .audioJobCk');
        all.checked = boxes.length > 0 && [...boxes].every(b => b.checked);
      }
    }
    // チェックした失敗ジョブへの一括操作。1件ずつ順に叩き、失敗があればまとめて知らせる。
    async function bulkAudioJobs(op){
      const ids = [...audioJobsChecked];
      let failed = 0;
      for(const id of ids){
        try {
          const r = op==='delete'
            ? await fetch('/api/audio/jobs/'+id,{method:'DELETE',headers:headers()})
            : await fetch('/api/audio/jobs/'+id+'/retry',{method:'POST',headers:headers()});
          const j = await r.json();
          if(!j.ok) failed++; else audioJobsChecked.delete(id);
        } catch(e){ failed++; }
      }
      if(failed) alert(failed+'件は'+(op==='delete'?'削除':'再実行')+'できませんでした');
      loadAudioJobs();
    }
    async function retryCheckedAudioJobs(){
      if(!audioJobsChecked.size){ alert('再実行する音声にチェックを入れてください'); return; }
      await bulkAudioJobs('retry');
    }
    async function deleteCheckedAudioJobs(){
      if(!audioJobsChecked.size){ alert('削除する音声にチェックを入れてください'); return; }
      if(!confirm('チェックした'+audioJobsChecked.size+'件の失敗ジョブを削除しますか？（音声ファイルも消え、再試行できなくなります）')) return;
      await bulkAudioJobs('delete');
    }

    // ---- Waseda アカウント連携 ----
    async function loadWaseda(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/waseda',{headers:headers()});
        const j = await r.json();
        if(j.ok){
          $('wasedaUser').value = j.wasedaUser || '';
          $('wasedaState').textContent = j.hasPassword ? '登録済み（パスワード保存済み）' : '';
        }
        // 取り込みが実行中のままならステータス表示を復元する（画面更新後も追える）。
        const s = await (await fetch('/api/waseda/sync/status',{headers:headers()})).json();
        if(s.ok && s.state==='running'){ showWasedaProgress(s.message||'取り込み中…'); pollWasedaSync(); }
        loadWasedaCourses();
      } catch(e){}
    }
    async function loadWasedaCourses(){
      try {
        const r = await fetch('/api/courses',{headers:headers()});
        const j = await r.json();
        allCourses = (j.ok && Array.isArray(j.courses)) ? j.courses : [];
        if($('wasedaCoursesBox')) $('wasedaCoursesBox').style.display = allCourses.length ? '' : 'none';
        renderCourseLists();
        renderCalendar();
      } catch(e){}
    }
    // ---- 時間割の編集（アカウント画面の一覧・カレンダー画面の日別一覧の両方から使う）----
    // 同じ科目が複数箇所（アカウント画面／カレンダーの日別一覧）に同時に描画され得るため、
    // 呼び出し元ごとに prefix を分けて要素 id を一意にする（そうしないと id 重複により、
    // 見えている方に入力しても隠れている方の古い値を保存してしまう）。
    let courseEditingKey = null; // 例: 'acct-14' | 'day-14'
    function courseRowHtml(c, prefix){
      const dayPeriod = c.day ? (c.day + '曜' + (c.period || '') + '限') : 'オンデマンド等';
      const room = c.room ? (' (' + c.room + ')') : '';
      const term = c.term ? ('[' + c.term + '] ') : '';
      if(courseEditingKey === (prefix + '-' + c.id)){
        const dayOptions = ['', '月','火','水','木','金','土','日'].map(d =>
          '<option value="' + d + '"' + (d === (c.day || '') ? ' selected' : '') + '>' + (d || '(なし/オンデマンド)') + '</option>'
        ).join('');
        return '<div class="card" style="margin:.3rem 0; padding:.5rem .7rem; background:#fff; border:1px solid var(--line); border-radius:10px">' +
          '<div class="row" style="gap:.4rem; flex-wrap:wrap">' +
          '<input id="ce_' + prefix + '_name_' + c.id + '" value="' + escapeHtml(c.name) + '" placeholder="科目名" style="flex:1; min-width:140px">' +
          '<input id="ce_' + prefix + '_term_' + c.id + '" value="' + escapeHtml(c.term || '') + '" placeholder="学期(例: 春)" style="width:90px">' +
          '<select id="ce_' + prefix + '_day_' + c.id + '" style="width:130px">' + dayOptions + '</select>' +
          '<input id="ce_' + prefix + '_period_' + c.id + '" type="number" min="1" max="7" value="' + (c.period || '') + '" placeholder="時限" style="width:70px">' +
          '<input id="ce_' + prefix + '_room_' + c.id + '" value="' + escapeHtml(c.room || '') + '" placeholder="教室" style="width:120px">' +
          '</div>' +
          '<div class="row" style="margin-top:.4rem; gap:.4rem">' +
          '<button class="small" onclick="saveCourseEdit(' + c.id + ',&#39;' + prefix + '&#39;)">保存</button>' +
          '<button class="ghost small" onclick="cancelCourseEdit()">キャンセル</button>' +
          '</div></div>';
      }
      return '<div style="padding:.3rem 0; border-bottom:1px dashed #f3f4f6; display:flex; justify-content:space-between; align-items:center; gap:.5rem">' +
        '<span>・' + term + '<strong>' + dayPeriod + '</strong> ' + escapeHtml(c.name) + escapeHtml(room) + '</span>' +
        '<span class="row" style="gap:.3rem; flex-shrink:0">' +
        '<button class="ghost small" onclick="startCourseEdit(' + c.id + ',&#39;' + prefix + '&#39;)">編集</button>' +
        '<button class="ghost small" onclick="deleteCourseRow(' + c.id + ')">削除</button>' +
        '</span></div>';
    }
    function renderCourseLists(){
      const html = allCourses.length ? allCourses.map(c => courseRowHtml(c, 'acct')).join('') : '<p class="muted">時間割が未登録です。</p>';
      if($('wasedaCourses')) $('wasedaCourses').innerHTML = html;
    }
    function startCourseEdit(id, prefix){ courseEditingKey = prefix + '-' + id; renderCourseLists(); renderSelectedDateEvents(); }
    function cancelCourseEdit(){ courseEditingKey = null; renderCourseLists(); renderSelectedDateEvents(); }
    async function saveCourseEdit(id, prefix){
      const name = $('ce_' + prefix + '_name_' + id).value.trim();
      if(!name){ alert('科目名を入力してください'); return; }
      const body = {
        name,
        term: $('ce_' + prefix + '_term_' + id).value.trim(),
        day: $('ce_' + prefix + '_day_' + id).value,
        period: $('ce_' + prefix + '_period_' + id).value ? Number($('ce_' + prefix + '_period_' + id).value) : null,
        room: $('ce_' + prefix + '_room_' + id).value.trim(),
      };
      try {
        const r = await fetch('/api/courses/' + id, {method:'PATCH', headers:headers(), body:JSON.stringify(body)});
        const j = await r.json();
        if(j.ok){ courseEditingKey = null; await loadWasedaCourses(); }
        else alert('保存に失敗しました: ' + (j.error || ''));
      } catch(e){ alert('通信エラー'); }
    }
    async function deleteCourseRow(id){
      if(!confirm('この科目を削除しますか？')) return;
      try {
        const r = await fetch('/api/courses/' + id, {method:'DELETE', headers:headers()});
        const j = await r.json();
        if(j.ok){ await loadWasedaCourses(); }
        else alert('削除に失敗しました: ' + (j.error || ''));
      } catch(e){ alert('通信エラー'); }
    }
    async function syncWasedaCoursesToGoogle(){
      $('wasedaSyncGoogleState').textContent = '同期中…';
      try {
        const r = await fetch('/api/google/sync-courses',{method:'POST',headers:headers(),
          body:JSON.stringify({googleEmail:googleDefault()})});
        const j = await r.json();
        if(j.ok){
          $('wasedaSyncGoogleState').textContent = '✓ ' + j.googleEmail + ' に ' + j.count + ' 件の授業予定を同期しました' +
            (j.skipped ? '（' + j.skipped + ' 件は登録済みのためスキップ）' : '');
          loadGoogleEvents();
        } else {
          $('wasedaSyncGoogleState').textContent = '✗ ' + (j.error||'同期失敗');
        }
      } catch(e){
        $('wasedaSyncGoogleState').textContent = '✗ 通信エラー';
      }
    }
    async function saveWaseda(){
      const wasedaUser = $('wasedaUser').value.trim(), wasedaPassword = $('wasedaPw').value;
      if(!wasedaUser){ $('wasedaState').textContent='Waseda ID を入力してください'; return; }
      const r = await fetch('/api/waseda',{method:'POST',headers:headers(),
        body:JSON.stringify({wasedaUser, wasedaPassword})});
      const j = await r.json();
      if(j.ok){ $('wasedaState').textContent='✓ 保存しました'; $('wasedaPw').value=''; }
      else $('wasedaState').textContent='✗ '+(j.error||'保存失敗');
    }
    async function clearWaseda(){
      const r = await fetch('/api/waseda',{method:'POST',headers:headers(),
        body:JSON.stringify({wasedaUser:'', wasedaPassword:''})});
      const j = await r.json();
      if(j.ok){ $('wasedaUser').value=''; $('wasedaPw').value=''; $('wasedaState').textContent='✓ 解除しました'; loadWasedaCourses(); }
      else $('wasedaState').textContent='✗ '+(j.error||'解除失敗');
    }
    // 時間割の取り込み（サーバーでスクレイパを実行し、完了まで状況をポーリング表示）。
    let wasedaPollTimer = null;
    async function syncWaseda(){
      $('wasedaState').textContent='';
      const r = await fetch('/api/waseda/sync',{method:'POST',headers:headers()});
      const j = await r.json();
      if(!j.ok && !/実行中/.test(j.error||'')){ $('wasedaState').textContent='✗ '+(j.error||'開始失敗'); return; }
      showWasedaProgress('時間割を取得しています…');
      pollWasedaSync();
    }
    function showWasedaProgress(msg){
      $('wasedaSyncBox').style.display=''; $('wasedaSyncBar').style.display='';
      $('wasedaSyncMsg').textContent = msg;
      $('wasedaSyncBtn').disabled = true;
    }
    // スクレイパの実行ログを表示欄に反映する（開いていれば末尾へ自動スクロール）。
    function updateWasedaLog(log){
      const el = $('wasedaSyncLog');
      if(!el || el.textContent === (log||'')) return;
      el.textContent = log || '';
      el.scrollTop = el.scrollHeight;
    }
    async function pollWasedaSync(){
      clearTimeout(wasedaPollTimer);
      try {
        const r = await fetch('/api/waseda/sync/status',{headers:headers()});
        const j = await r.json();
        if(j.ok){
          updateWasedaLog(j.log);
          if(j.state==='running'){
            $('wasedaSyncMsg').textContent = '取り込み中: '+(j.message||'…');
            wasedaPollTimer = setTimeout(pollWasedaSync, 3000);
            return;
          }
          $('wasedaSyncBar').style.display='none';
          $('wasedaSyncMsg').textContent = (j.state==='done' ? '✓ ' : (j.state==='error' ? '✗ ' : '')) + (j.message||'');
          if(j.state==='done'){
            loadTasks(); loadWasedaCourses();
            // Google 連携済みなら、取り込んだ時間割をそのままカレンダーへ反映する。
            if(googleAccounts.length) syncWasedaCoursesToGoogle();
          }
        }
      } catch(e){ $('wasedaSyncMsg').textContent='状況の取得に失敗しました'; }
      $('wasedaSyncBtn').disabled = false;
    }

    // ---- 資料要約 ----
    async function loadDocs(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/files',{headers:headers()});
        const j = await r.json();
        if(!j.ok || !j.documents.length){ $('docList').innerHTML=''; return; }
        $('docList').innerHTML = j.documents.map(d =>
          '<details style="margin:.3rem 0"><summary>'+escapeHtml(d.name)+'</summary>'+
          '<div style="white-space:pre-wrap;line-height:1.6;margin-top:.4rem">'+escapeHtml(d.summary)+'</div></details>'
        ).join('');
      } catch(e){}
    }
    async function uploadDoc(){
      const f = $('docFile').files[0];
      if(!f){ $('docState').textContent='ファイルを選んでください'; return; }
      $('docState').textContent='要約中…';
      try {
        const isTxt = /\.txt$/i.test(f.name) || f.type==='text/plain';
        const h = { 'X-Account-Email': auth.email||'', 'Authorization':'Bearer '+(auth.token||''),
                    'X-Filename': f.name, 'Content-Type': isTxt ? 'text/plain' : 'application/pdf' };
        const body = isTxt ? await f.text() : await f.arrayBuffer();
        const r = await fetch('/api/files',{method:'POST',headers:h,body});
        const j = await r.json();
        if(j.ok){ $('docState').textContent='✓ 要約しました'; $('docFile').value=''; loadDocs(); }
        else $('docState').textContent='✗ '+(j.error||'失敗');
      } catch(e){ $('docState').textContent='✗ 通信エラー'; }
    }

    // ---- Gemini API キー（ユーザーごとの登録制） ----
    async function loadGeminiKey(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/gemini-key',{headers:headers()});
        const j = await r.json();
        if(!j.ok) return;
        $('geminiKeyState').textContent = j.hasKey
          ? '登録済み'+(j.tail ? '（****'+j.tail+'）' : '')+' / モデル: '+j.model
          : '未登録（AI機能を使うには登録が必要です）';
      } catch(e){}
    }
    async function saveGeminiKey(){
      const apiKey = $('geminiKey').value.trim();
      if(!apiKey){ $('geminiKeyState').textContent = '✗ APIキーを入力してください'; return; }
      $('geminiKeyState').textContent = '確認中…';
      try {
        const r = await fetch('/api/gemini-key',{method:'POST',headers:headers(),body:JSON.stringify({apiKey})});
        const j = await r.json();
        if(j.ok){ $('geminiKey').value=''; $('geminiKeyState').textContent = '✓ 登録しました（****'+j.tail+'）'; }
        else $('geminiKeyState').textContent = '✗ '+(j.error||'登録失敗');
      } catch(e){ $('geminiKeyState').textContent = '✗ 通信エラー'; }
    }
    async function deleteGeminiKey(){
      if(!confirm('登録済みの Gemini API キーを削除しますか？AI機能が使えなくなります。')) return;
      const r = await fetch('/api/gemini-key',{method:'DELETE',headers:headers()});
      const j = await r.json();
      if(j.ok){ $('geminiKeyState').textContent = '削除しました'; loadGeminiKey(); }
      else $('geminiKeyState').textContent = '✗ '+(j.error||'削除失敗');
    }

    // ---- Gemini 自動解析の on/off ----
    async function loadGeminiAuto(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/gemini-auto',{headers:headers()});
        const j = await r.json();
        if(j.ok) $('geminiAuto').checked = !!j.enabled;
      } catch(e){}
    }
    async function saveGeminiAuto(enabled){
      try {
        const r = await fetch('/api/gemini-auto',{method:'POST',headers:headers(),body:JSON.stringify({enabled})});
        const j = await r.json();
        $('geminiAutoState').textContent = j.ok
          ? (enabled ? '✓ 自動解析をオンにしました' : '✓ 自動解析をオフにしました（履歴の「解析する」で手動実行）')
          : '✗ '+(j.error||'保存失敗');
        if(!j.ok) loadGeminiAuto();
      } catch(e){ $('geminiAutoState').textContent = '✗ 通信エラー'; loadGeminiAuto(); }
    }

    // ---- Moodle 連携 ----
    async function loadMoodle(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/moodle',{headers:headers()});
        const j = await r.json();
        if(j.ok) $('moodleUrl').value = j.url || '';
      } catch(e){}
    }
    async function saveMoodle(){
      const url = $('moodleUrl').value.trim();
      const r = await fetch('/api/moodle',{method:'POST',headers:headers(),body:JSON.stringify({url})});
      const j = await r.json();
      $('moodleState').textContent = j.ok ? '✓ 保存しました' : ('✗ '+(j.error||'保存失敗'));
    }
    async function syncMoodle(){
      $('moodleState').textContent = '同期中…';
      const r = await fetch('/api/moodle/sync',{method:'POST',headers:headers()});
      const j = await r.json();
      if(j.ok){ $('moodleState').textContent = '✓ '+j.imported+' 件取り込みました'; loadTasks(); }
      else $('moodleState').textContent = '✗ '+(j.error||'同期失敗');
    }

    // ---- 文字起こしの履歴（絞り込み・並び替え・本文検索） ----
    let allTranscripts = [];
    let transcriptSearchWord = ''; // 本文検索中の語（空なら通常一覧）
    async function loadTranscripts(){
      if(!auth.email) return;
      try {
        const url = '/api/transcripts?limit=200' +
          (transcriptSearchWord ? '&contains='+encodeURIComponent(transcriptSearchWord) : '');
        const r = await fetch(url,{headers:headers()});
        const j = await r.json();
        if(!j.ok){
          $('transcripts').innerHTML = '<p class="muted">'+escapeHtml(j.error||'取得に失敗しました')+'</p>';
          return;
        }
        allTranscripts = Array.isArray(j.transcripts) ? j.transcripts : [];
        $('trSearchState').textContent = transcriptSearchWord
          ? '「'+transcriptSearchWord+'」を含む '+allTranscripts.length+' 件'
          : '';
        renderTranscripts();
      } catch(e){
        $('transcripts').innerHTML = '<p class="muted">取得に失敗しました。</p>';
      }
    }
    async function searchTranscripts(){
      transcriptSearchWord = $('trContains').value.trim();
      $('trSearchState').textContent = transcriptSearchWord ? '検索中…' : '';
      await loadTranscripts();
    }
    async function clearTranscriptSearch(){
      transcriptSearchWord = '';
      $('trContains').value = '';
      await loadTranscripts();
    }
    function renderTranscripts(){
      const nameQ = ($('trName')||{}).value ? $('trName').value.trim().toLowerCase() : '';
      const filter = ($('trFilter')||{}).value || 'all';
      const sort = ($('trSort')||{}).value || 'updated-desc';
      let list = allTranscripts.slice();
      if(nameQ) list = list.filter(t => (t.filename||'').toLowerCase().includes(nameQ));
      if(filter==='analyzed') list = list.filter(t => !!t.analyzed_at);
      else if(filter==='unanalyzed') list = list.filter(t => !t.analyzed_at);
      const upd = t => new Date(t.updated_at).getTime() || 0;
      const cmp = {
        'updated-desc': (a,b)=>upd(b)-upd(a),
        'updated-asc': (a,b)=>upd(a)-upd(b),
        'name-desc': (a,b)=>String(b.filename||'').localeCompare(String(a.filename||''),'ja'),
        'name-asc': (a,b)=>String(a.filename||'').localeCompare(String(b.filename||''),'ja'),
        'chars-desc': (a,b)=>(b.chars||0)-(a.chars||0),
        'chars-asc': (a,b)=>(a.chars||0)-(b.chars||0),
      }[sort] || ((a,b)=>upd(b)-upd(a));
      list.sort(cmp);
      if(!list.length){
        $('transcripts').innerHTML = '<p class="muted">'+
          (allTranscripts.length ? '条件に一致するファイルはありません。' :
           (transcriptSearchWord ? '「'+escapeHtml(transcriptSearchWord)+'」を含むファイルはありません。' : 'まだファイルがありません。'))+'</p>';
        return;
      }
      const rows = list.map(t => {
        const analyzed = !!t.analyzed_at;
        const analyzeBtn = '<button class="ghost small" id="an'+t.id+'" onclick="analyzeTranscript('+t.id+')">'+
          (analyzed?'再解析':'解析する')+'</button>';
        const csvLinks = analyzed
          ? '<button class="small" onclick="downloadFile(\\'/kadai/'+t.id+'.csv\\', \\'kadai-'+t.id+'.csv\\')">課題CSV</button> ' +
            '<button class="small" onclick="downloadFile(\\'/yotei/'+t.id+'.csv\\', \\'yotei-'+t.id+'.csv\\')">予定CSV</button> '
          : '<span class="pending">未解析</span> ';
        return '<tr>'+
          '<td>'+escapeHtml(t.filename)+'</td>'+
          '<td class="num">'+(t.chars || 0)+'</td>'+
          '<td>'+new Date(t.updated_at).toLocaleString('ja-JP')+'</td>'+
          '<td><button class="small" onclick="viewText('+t.id+')">本文</button> '+
          '<button class="small" onclick="downloadFile(\\'/download/'+t.id+'\\', '+JSON.stringify(t.filename || ('transcript-'+t.id+'.txt'))+')">DL</button></td>'+
          '<td>'+csvLinks+analyzeBtn+'</td>'+
        '</tr>';
      }).join('');
      $('transcripts').innerHTML =
        '<table><thead><tr><th>ファイル名</th><th>文字数</th><th>更新</th><th></th><th>課題/予定</th></tr></thead>'+
        '<tbody>'+rows+'</tbody></table>';
    }
    // 「解析する」ボタン: 自動解析 off のユーザーの手動実行（要約・課題/予定抽出）。
    async function analyzeTranscript(id){
      const btn = $('an'+id);
      if(btn){ btn.disabled = true; btn.textContent = '解析中…'; }
      try {
        const r = await fetch('/api/transcripts/'+id+'/analyze',{method:'POST',headers:headers()});
        const j = await r.json();
        if(!j.ok){ alert('解析に失敗しました: '+(j.error||'')); }
        else { loadTasks(); }
      } catch(e){ alert('解析に失敗しました（通信エラー）'); }
      await loadTranscripts();
    }
    // 「未解析を一括解析」ボタン: 未解析のファイルをサーバー側でまとめて解析する。
    // サーバーは1回の呼び出しで数件ずつ処理して残り件数を返すので、0 になるまで繰り返し呼ぶ。
    async function analyzeAllUnanalyzed(){
      const btn = $('bulkAnalyzeBtn'), st = $('bulkAnalyzeState');
      const total = allTranscripts.filter(t => !t.analyzed_at).length;
      if(!total){ st.textContent = '未解析のファイルはありません'; return; }
      if(!confirm('未解析の '+total+' 件をAI解析します（Gemini APIを件数分呼び出します）。よろしいですか？')) return;
      btn.disabled = true;
      let done = 0, failed = [];
      st.textContent = '解析中… 0/'+total;
      try {
        while(true){
          const r = await fetch('/api/transcripts/analyze-unanalyzed',{method:'POST',headers:headers()});
          const j = await r.json();
          if(!j.ok){ st.textContent = '✗ '+(j.error||'解析に失敗しました'); break; }
          done += j.analyzed; failed = failed.concat(j.failed||[]);
          if(j.remaining > 0 && j.analyzed > 0){
            st.textContent = '解析中… '+done+'/'+(done + j.remaining);
            continue;
          }
          st.textContent = '✓ '+done+' 件を解析しました'+
            (failed.length ? '（'+failed.length+' 件失敗: '+failed[0].error+'）' : '');
          break;
        }
      } catch(e){ st.textContent = '✗ 通信エラーで中断しました（解析済みの分は保存されています）'; }
      btn.disabled = false;
      loadTasks();
      await loadTranscripts();
    }

    // ---- 本文表示（モーダル） ----
    async function viewText(id){
      $('modalTitle').textContent = '読み込み中…'; $('modalBody').textContent = '';
      $('modal').style.display = 'flex';
      try {
        const r = await fetch('/api/transcripts/'+id, {headers: headers()});
        const j = await r.json();
        if(j.ok){
          const t = j.transcript || {};
          $('modalTitle').textContent = t.filename || '';
          $('modalBody').textContent = (t.summary ? '【要約】\\n'+t.summary+'\\n\\n【本文】\\n' : '') + (t.content || '');
        } else { $('modalTitle').textContent = 'エラー'; $('modalBody').textContent = j.error||''; }
      } catch(e){ $('modalTitle').textContent='通信エラー'; }
    }

    async function downloadFile(path, fallbackName){
      try {
        const r = await fetch(path, {headers: headers()});
        if(!r.ok){
          alert('ダウンロードに失敗しました');
          return;
        }
        const blob = await r.blob();
        let filename = fallbackName || 'download';
        const cd = r.headers.get('Content-Disposition') || '';
        const m = cd.match(/filename\\*=UTF-8''([^;]+)/);
        if(m) filename = decodeURIComponent(m[1]);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
      } catch(e){
        alert('ダウンロードに失敗しました');
      }
    }
    function closeModal(){ $('modal').style.display = 'none'; }

    // ---- チャット ----
    function bubble(text, who){
      const d = document.createElement('div');
      d.className = 'bubble '+who; d.textContent = text;
      $('chatlog').appendChild(d); $('chatlog').scrollTop = $('chatlog').scrollHeight;
    }
    // 画面を開き直しても会話が途切れないよう、保存済みの履歴を読み込んで表示する。
    async function loadChatHistory(){
      if(!auth.email) return;
      try {
        const r = await fetch('/api/chat/history',{headers:headers()});
        const j = await r.json();
        if(j.ok && Array.isArray(j.messages) && j.messages.length){
          $('chatlog').innerHTML = '';
          j.messages.forEach(m => bubble(m.content, m.role === 'user' ? 'me' : 'bot'));
        }
      } catch(e){}
    }
    async function ask(){
      const q = $('q').value.trim(); if(!q) return;
      $('q').value=''; bubble(q,'me');
      try{
        const r = await fetch('/api/ask',{method:'POST',headers:headers(),body:JSON.stringify({question:q})});
        const j = await r.json();
        bubble(j.ok ? j.reply : ('エラー: '+(j.error||'')), 'bot');
        if(j.ok){
          // 実際に登録・完了された内容を明示する（言っただけで登録されていない事故の可視化）。
          if(Array.isArray(j.applied) && j.applied.length){
            bubble(j.applied.map(a => a.op==='add_task'
              ? '✓ 登録: '+(a.type==='yotei'?'予定':'課題')+'「'+a.content+'」'+(a.deadline_at?'（期限 '+String(a.deadline_at).slice(0,16).replace('T',' ')+'）':'（期限未設定）')
              : (a.op==='delete_task'
                ? '✓ 削除: 「'+(a.content||'')+'」'
                : (a.op==='update_task'
                  ? '✓ 変更: 「'+(a.content||'')+'」'+(a.deadline_at?'（'+String(a.deadline_at).slice(0,16).replace('T',' ')+'）':'')
                  : '✓ 完了: 「'+(a.content||'')+'」'))).join('\\n'), 'bot');
          }
          loadTasks();
        }
      }catch(e){ bubble('通信エラー','bot'); }
    }

    // ---- タスク ----
    let allTasks = [];
    // deadline_at ("YYYY-MM-DD HH:MM:SS" 等) を Date に。不正なら null。
    function parseDeadline(s){
      if(!s) return null;
      const d = new Date(String(s).replace(' ','T'));
      return isNaN(d.getTime()) ? null : d;
    }
    function dueClass(at){
      const d = parseDeadline(at); if(!d) return '';
      const ms = d - new Date();
      if(ms < 3600e3) return 'soon';
      if(ms < 86400e3) return 'warn';
      return '';
    }
    // 期限を { base:日時, rel:相対 } に分けて返す（列で2行に分けて表示するため）。
    function dueParts(t){
      const d = parseDeadline(t.deadline_at); if(!d) return { base:'期限未定', rel:'' };
      const s = t.deadline_at;
      const base = t.date_only ? s.slice(0,10) : s.slice(0,16);
      const ms = d - new Date();
      let rel;
      if(ms < 0) rel = '期限切れ';
      else { const h = Math.floor(ms/3600e3);
        rel = h < 24 ? 'あと'+h+'時間' : 'あと'+Math.floor(h/24)+'日'; }
      return { base, rel };
    }
    async function loadTasks(){
      if(!auth.email) return;
      const r = await fetch('/api/tasks?done=1',{headers:headers()});
      const j = await r.json();
      if(!j.ok){ $('tasks').innerHTML='<p class="muted">'+escapeHtml(j.error||'取得に失敗しました')+'</p>'; return; }
      allTasks = Array.isArray(j.tasks) ? j.tasks : [];
      renderTasks();
      renderCalendar();
    }
    function clearTaskPeriod(){ $('taskFrom').value=''; $('taskTo').value=''; renderTasks(); }

    // 絞り込み（状態・種別・期間・キーワード）＋並び替え。
    function renderTasks(){
      const f = ($('taskFilter')||{}).value || 'pending';
      const type = ($('taskType')||{}).value || 'all';
      const sort = ($('taskSort')||{}).value || 'due-asc';
      const q = (($('taskSearch')||{}).value || '').trim().toLowerCase();
      const from = ($('taskFrom')||{}).value ? new Date(($('taskFrom').value)+'T00:00:00') : null;
      const to = ($('taskTo')||{}).value ? new Date(($('taskTo').value)+'T23:59:59') : null;
      const now = new Date();
      let list = allTasks.slice();

      // 状態
      if(f==='pending') list = list.filter(t => t.status!=='done');
      else if(f==='active') list = list.filter(t => { if(t.status==='done') return false; const d=parseDeadline(t.deadline_at); return !d || d>=now; });
      else if(f==='overdue') list = list.filter(t => { const d=parseDeadline(t.deadline_at); return d && d<now && t.status!=='done'; });
      // 種別
      if(type==='kadai') list = list.filter(t => t.type==='kadai');
      else if(type==='yotei') list = list.filter(t => t.type==='yotei');
      // キーワード
      if(q) list = list.filter(t => ((t.content||'')+' '+(t.details||'')).toLowerCase().includes(q));
      // 期間（締切が範囲内。期限未定は範囲指定時は除外）
      if(from || to) list = list.filter(t => { const d=parseDeadline(t.deadline_at); if(!d) return false; if(from && d<from) return false; if(to && d>to) return false; return true; });

      // 並び替え
      const val = t => { const d=parseDeadline(t.deadline_at); return d ? d.getTime() : null; };
      if(sort==='new') list.sort((a,b) => (b.id||0)-(a.id||0));
      else list.sort((a,b) => {
        const av=val(a), bv=val(b);
        if(av==null && bv==null) return 0;
        if(av==null) return 1;      // 未定は末尾
        if(bv==null) return -1;
        return sort==='due-desc' ? bv-av : av-bv;
      });

      if(!list.length){ $('tasks').innerHTML='<p class="muted">該当する項目はありません。</p>'; return; }
      const rows = list.map(t => {
        const done = t.status==='done';
        const label = t.type==='yotei' ? '予定' : '課題';
        const details = t.details ? '<div class="muted">'+escapeHtml(t.details)+'</div>' : '';
        const due = dueParts(t);
        const dueHtml = escapeHtml(due.base) +
          (due.rel ? '<div class="rel">'+escapeHtml(due.rel)+'</div>' : '');
        return '<tr>'+
          '<td class="col-type"><span class="badge '+(t.type==='yotei'?'yotei':'kadai')+'">'+label+'</span></td>'+
          '<td class="'+(done?'done':'')+'">'+escapeHtml(t.content)+details+'</td>'+
          '<td class="col-due due '+dueClass(t.deadline_at)+'">'+dueHtml+'</td>'+
          '<td class="col-mid"><input type="checkbox" '+(done?'checked':'')+
            ' onchange="toggle('+t.id+',this.checked)"></td>'+
          '<td class="col-mid"><button class="ghost small" onclick="delTask('+t.id+')">削除</button>'+
            (googleAccounts.length && t.deadline_at ?
              '<button class="ghost small" title="Google カレンダーに登録" onclick="addToCalendar('+t.id+')">📅</button>' : '')+
          '</td>'+
        '</tr>';
      }).join('');
      $('tasks').innerHTML =
        '<table><thead><tr><th>種別</th><th>内容</th><th>期限</th><th>完了</th><th></th></tr></thead>'+
        '<tbody>'+rows+'</tbody></table>';
    }
    async function toggle(id, done){
      await fetch('/api/tasks/'+id+'/done',{method:'POST',headers:headers(),
        body:JSON.stringify({status: done?'done':'pending'})}); loadTasks();
    }
    async function delTask(id){
      await fetch('/api/tasks/'+id,{method:'DELETE',headers:headers()}); loadTasks();
    }
    async function addTask(){
      const body = { type:$('t_type').value, content:$('t_content').value.trim(),
        details:$('t_details').value.trim(), deadline:$('t_deadline').value };
      if(!body.content) return;
      await fetch('/api/tasks',{method:'POST',headers:headers(),body:JSON.stringify(body)});
      $('t_content').value=$('t_details').value=$('t_deadline').value=''; loadTasks();
    }

    // ---- 要約 ----
    async function loadSummary(){
      if(!auth.email) return;
      const r = await fetch('/api/summary/today',{headers:headers()});
      const j = await r.json();
      $('summary').innerHTML = (j.ok && j.summary)
        ? '<div style="white-space:pre-wrap;line-height:1.5">'+escapeHtml(j.summary)+'</div>'
        : '<p class="muted">まだ今日の要約はありません。「今すぐ生成し直す」を押すか、録音がたまると自動生成されます。</p>';
    }
    async function genSummary(){
      if(!auth.email) return;
      $('summary').innerHTML='<p class="muted">生成中…</p>';
      const r = await fetch('/api/summary/today/generate',{method:'POST',headers:headers()});
      const j = await r.json();
      $('summary').innerHTML = (j.ok && j.summary)
        ? '<div style="white-space:pre-wrap;line-height:1.5">'+escapeHtml(j.summary)+'</div>'
        : '<p class="muted">'+(j.error || '今日の文字起こしがまだありません。')+'</p>';
    }

    function escapeHtml(s){ return String(s).replace(/[&<>"']/g,c=>(
      {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
    initAuth();
  </script>
</body>
</html>`;
}

module.exports = { renderDashboard };
