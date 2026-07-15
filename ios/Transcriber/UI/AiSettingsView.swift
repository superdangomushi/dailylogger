import SwiftUI

// 連携・設定画面（AIタブの⚙から開く）。AiTabView.swift から分割。

/// 右上⚙から開く連携・設定画面。AIHelper ログインや Google/Moodle/Waseda/通知をここに集約した。
struct AiSettingsView: View {
    @EnvironmentObject var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 12) {
                    AiHelperCard()

                    // 通知の受け取り設定（ログイン前でも変更できる端末ローカル設定）。
                    NotificationSettingsCard()

                    // 出発・雨・終電アラート（GPS＋天気予報。端末ローカル設定）。
                    TravelSettingsCard()

                    // Google 連携は端末側サインインなので AIHelper ログイン前でも表示する。
                    GoogleCalendarCard()

                    if viewModel.ui.account.loggedIn {
                        MoodleCard()
                        WasedaCard()
                        DigestCard()
                    } else {
                        Text("AIHelper にログインすると、Moodle / Waseda 連携やまとめ通知を設定できます。")
                            .font(.caption)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            .background(AppTheme.background)
            .navigationTitle("連携・設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { dismiss() }
                }
            }
        }
    }
}

/// 通知の受け取り設定カード。マスターON/OFFと「おやすみモード」（指定時間帯は通知しない）。
/// 就寝中に締切リマインドやまとめ通知が鳴り響かないようにする。
struct NotificationSettingsCard: View {
    private let prefs = NotificationPrefs()
    @State private var enabled = true
    @State private var quietEnabled = false
    @State private var quietStart = Calendar.current.date(from: DateComponents(hour: 23, minute: 0)) ?? Date()
    @State private var quietEnd = Calendar.current.date(from: DateComponents(hour: 7, minute: 0)) ?? Date()

    var body: some View {
        CardView {
            Text("通知").font(.headline)

            Toggle(isOn: $enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("通知を受け取る")
                    Text("締切リマインドと1日のまとめ通知のON/OFF。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .onChange(of: enabled) { v in
                prefs.enabled = v
                rescheduleDigests()
            }

            Toggle(isOn: $quietEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("おやすみモード")
                    Text("指定した時間帯は通知しません。リマインドは時間帯が明けてから届きます。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .disabled(!enabled)
            .onChange(of: quietEnabled) { v in
                prefs.quietEnabled = v
                rescheduleDigests()
            }

            if enabled && quietEnabled {
                HStack {
                    DatePicker("開始", selection: $quietStart, displayedComponents: .hourAndMinute)
                        .onChange(of: quietStart) { v in
                            prefs.quietStart = hhmm(v); rescheduleDigests()
                        }
                    DatePicker("終了", selection: $quietEnd, displayedComponents: .hourAndMinute)
                        .onChange(of: quietEnd) { v in
                            prefs.quietEnd = hhmm(v); rescheduleDigests()
                        }
                }
            }
        }
        .onAppear {
            enabled = prefs.enabled
            quietEnabled = prefs.quietEnabled
            quietStart = parse(prefs.quietStart) ?? quietStart
            quietEnd = parse(prefs.quietEnd) ?? quietEnd
        }
    }

    private func hhmm(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private func parse(_ s: String) -> Date? {
        let p = s.split(separator: ":").compactMap { Int($0) }
        guard p.count == 2 else { return nil }
        return Calendar.current.date(from: DateComponents(hour: p[0], minute: p[1]))
    }

    // おやすみ帯・マスターON/OFF の変更は予約済みのまとめ通知に影響するので貼り直す。
    private func rescheduleDigests() {
        DispatchQueue.global().async { DailyDigestScheduler.scheduleAll() }
    }
}

/// 出発・雨・終電アラートの設定カード（⚙連携・設定内）。
/// 自宅のGPS登録・所要時間・終電時刻と、3機能それぞれのON/OFF。
struct TravelSettingsCard: View {
    private let prefs = TravelPrefs()
    @State private var departureEnabled = false
    @State private var rainEnabled = false
    @State private var lastTrainEnabled = false
    @State private var hasHome = false
    @State private var commuteText = "60"
    @State private var lastTrainDate = Calendar.current.date(from: DateComponents(hour: 0, minute: 20)) ?? Date()
    @State private var lastTrainSet = false
    @State private var homeMessage: String? = nil

    var body: some View {
        CardView {
            Text("出発・雨・終電アラート").font(.headline)
            Text("GPSと天気予報（Open-Meteo）を使った通知です。位置情報は常時追跡せず、チェック時の最終位置だけを使います。")
                .font(.caption)

            // ---- 自宅の登録（出発・終電の基準点） ----
            HStack(spacing: 8) {
                Button(hasHome ? "自宅を登録し直す" : "現在地を自宅にする") {
                    registerHome()
                }
                .buttonStyle(.bordered)
                if hasHome {
                    Text("登録済み").font(.caption)
                }
            }
            if let msg = homeMessage {
                Text(msg).font(.caption)
            }

            // ---- 出発アラート ----
            Toggle(isOn: $departureEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("出発アラート")
                    Text("今日の最初の授業・予定に間に合う出発時刻を30分前に通知。雨なら10分早め＋傘の一言。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .disabled(!hasHome)
            .onChange(of: departureEnabled) { v in prefs.departureEnabled = v }
            if departureEnabled {
                HStack {
                    Text("自宅から学校までの所要時間").font(.subheadline)
                    Spacer()
                    TextField("60", text: $commuteText)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: commuteText) { text in
                            let digits = String(text.filter(\.isNumber).prefix(3))
                            if digits != text { commuteText = digits }
                            if let v = Int(digits) { prefs.commuteMinutes = v }
                        }
                    Text("分").font(.subheadline)
                }
            }

            // ---- 雨アラート ----
            Toggle(isOn: $rainEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("雨アラート")
                    Text("現在地の15分刻み予報で「まもなく降る」「まもなく止む」を通知。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .onChange(of: rainEnabled) { v in
                prefs.rainEnabled = v
                if v { TravelLocation.shared.fetchFresh { _ in } } // 権限要求＋位置キャッシュ更新
            }

            // ---- 終電アラート ----
            Toggle(isOn: $lastTrainEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("終電アラート")
                    Text("夜に自宅から離れた場所にいるとき、終電の60分前・20分前に通知。おやすみモード中でも鳴ります。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .disabled(!hasHome)
            .onChange(of: lastTrainEnabled) { v in prefs.lastTrainEnabled = v }
            if lastTrainEnabled {
                DatePicker("最寄り駅の終電時刻", selection: $lastTrainDate, displayedComponents: .hourAndMinute)
                    .onChange(of: lastTrainDate) { v in
                        let c = Calendar.current.dateComponents([.hour, .minute], from: v)
                        prefs.lastTrainTime = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
                        lastTrainSet = true
                    }
                if !lastTrainSet {
                    Text("時刻を一度動かすと保存されます。").font(.caption).foregroundColor(.secondary)
                }
            }

            if !hasHome {
                Text("出発・終電アラートを使うには、まず自宅の登録が必要です。")
                    .font(.caption).foregroundColor(.red)
            }
        }
        .onAppear {
            departureEnabled = prefs.departureEnabled
            rainEnabled = prefs.rainEnabled
            lastTrainEnabled = prefs.lastTrainEnabled
            hasHome = prefs.hasHome
            commuteText = String(prefs.commuteMinutes)
            if !prefs.lastTrainTime.isEmpty {
                let p = prefs.lastTrainTime.split(separator: ":").compactMap { Int($0) }
                if p.count == 2 {
                    lastTrainDate = Calendar.current.date(from: DateComponents(hour: p[0], minute: p[1])) ?? lastTrainDate
                    lastTrainSet = true
                }
            }
        }
    }

    private func registerHome() {
        homeMessage = "現在地を取得中…"
        TravelLocation.shared.fetchFresh { loc in
            DispatchQueue.main.async {
                if let loc {
                    prefs.setHome(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
                    hasHome = true
                    homeMessage = "自宅を登録しました（現在地）"
                } else {
                    homeMessage = "現在地を取得できませんでした。位置情報の許可と電波状況を確認してください。"
                }
            }
        }
    }
}

/// AIHelper.jp のログイン / アカウント表示。
struct AiHelperCard: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        CardView {
            Text("AIHelper.jp 連携").font(.headline)
            if viewModel.ui.account.loggedIn {
                Text("ログイン中: \(viewModel.ui.account.email)")
                Text(viewModel.ui.account.baseUrl).font(.caption)
                SttQualitySection()
                Button("ログアウト") { viewModel.logout() }
            } else {
                AiHelperLoginForm()
            }
        }
    }
}

// サーバー文字起こしのクオリティ選択肢。値はサーバー API（/api/stt-quality）と共通。
// 将来はプラン（課金）で選べるものを制限する想定だが、現時点では全員どれでも選べる。
private let sttQualityOptions: [(String, String)] = [
    ("light", "軽量（速い・精度低め）"),
    ("standard", "標準（バランス）"),
    ("high", "最高精度（推奨・現在の既定）"),
]

/// アカウントに紐付く音声認識クオリティの選択。
struct SttQualitySection: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("音声認識クオリティ").font(.subheadline.bold())
            Text("サーバーで文字起こしするときの精度と速さの設定です。").font(.caption)
            ForEach(sttQualityOptions, id: \.0) { value, label in
                RadioRow(selected: viewModel.ui.sttQuality == value,
                         enabled: !viewModel.ui.sttQualityBusy) {
                    viewModel.setSttQuality(value)
                } label: {
                    Text(label).font(.subheadline)
                }
            }
            if let msg = viewModel.ui.sttQualityMessage {
                Text(msg).font(.caption)
            }
        }
    }
}

struct AiHelperLoginForm: View {
    @EnvironmentObject var viewModel: MainViewModel
    @State private var baseUrl = ""
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        VStack(spacing: 8) {
            LabeledField(label: "サーバーURL", text: $baseUrl)
            LabeledField(label: "メールアドレス", text: $email)
            LabeledField(label: "パスワード", text: $password, secure: true)
            HStack(spacing: 8) {
                Button {
                    viewModel.login(baseUrl: baseUrl, email: email, password: password)
                } label: {
                    if viewModel.ui.loginInProgress {
                        ProgressView().scaleEffect(0.7)
                    } else {
                        Text("ログイン").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.ui.loginInProgress)
                Button {
                    viewModel.register(baseUrl: baseUrl, email: email, password: password)
                } label: {
                    Text("新規登録").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.ui.loginInProgress)
            }
            if let err = viewModel.ui.loginError {
                Text("認証失敗: \(err)")
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .onAppear {
            if baseUrl.isEmpty { baseUrl = viewModel.ui.account.baseUrl }
        }
    }
}

/// Google カレンダー連携カード。複数アカウントを連携でき、既定の登録先を選んで予定をまとめて表示。
struct GoogleCalendarCard: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        CardView {
            HStack {
                Text("Google カレンダー").font(.headline)
                Spacer()
                if viewModel.ui.googleConnected {
                    Button("更新") { viewModel.loadCalendar() }
                        .buttonStyle(.bordered)
                        .disabled(viewModel.ui.googleBusy)
                }
            }
            if !viewModel.ui.googleConnected {
                Text("連携すると、課題・予定の締切をカレンダーに登録したり、直近の予定を表示できます。")
                    .font(.caption)
                Button("Google と連携") { viewModel.connectGoogle() }
                    .buttonStyle(.borderedProminent)
                // サインイン失敗の理由（OAuth 設定不備・キャンセル等）をここに表示する。
                if let msg = viewModel.ui.googleMessage {
                    Text(msg).foregroundColor(.red).font(.caption)
                }
            } else {
                if viewModel.ui.googleEmails.count > 1 {
                    Text("「カレンダーに追加」の登録先を選んでください。").font(.caption)
                }
                ForEach(viewModel.ui.googleEmails, id: \.self) { email in
                    HStack {
                        RadioRow(selected: email == viewModel.ui.googleDefault, enabled: true) {
                            viewModel.setDefaultGoogle(email)
                        } label: {
                            Text(email).font(.caption).lineLimit(1)
                        }
                        Button("解除") { viewModel.disconnectGoogle(email) }
                            .font(.caption)
                    }
                }
                Button("アカウントを追加") { viewModel.connectGoogle() }
                if viewModel.ui.calendarEvents.isEmpty {
                    Text(viewModel.ui.googleBusy ? "読み込み中…" : "直近の予定はありません。")
                        .font(.caption)
                } else {
                    ForEach(Array(viewModel.ui.calendarEvents.prefix(8).enumerated()), id: \.offset) { _, ev in
                        let owner = (viewModel.ui.googleEmails.count > 1 && !ev.accountEmail.isEmpty)
                            ? "（\(ev.accountEmail.split(separator: "@").first.map(String.init) ?? "")）"
                            : ""
                        Text("・\(ev.whenText)  \(ev.title)\(owner)")
                            .font(.subheadline)
                    }
                }
            }
        }
    }
}

/// Moodle（iCal）連携カード。URL を保存し、提出物・予定を取り込む。
struct MoodleCard: View {
    @EnvironmentObject var viewModel: MainViewModel
    @State private var url = ""

    var body: some View {
        CardView {
            Text("Moodle 連携").font(.headline)
            Text("Moodle のカレンダー → 書き出し →「カレンダーのURLを取得」で得た iCal URL を貼り付けてください。提出物・予定が課題一覧に取り込まれます。")
                .font(.caption)
            LabeledField(label: "Moodle iCal URL", text: $url)
            HStack(spacing: 8) {
                Button("保存") { viewModel.saveMoodleUrl(url) }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.ui.moodleBusy)
                Button("課題・予定を取り込む") { viewModel.syncMoodle() }
                    .buttonStyle(.bordered)
                    .disabled(viewModel.ui.moodleBusy || url.isEmpty)
            }
            if viewModel.ui.moodleBusy {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let msg = viewModel.ui.moodleMessage {
                Text(msg).font(.caption)
            }
        }
        .onAppear {
            viewModel.loadMoodle()
        }
        .onChange(of: viewModel.ui.moodleUrl) { newValue in
            if url.isEmpty { url = newValue }
        }
    }
}

/// Waseda アカウント連携カード。各ユーザーが自分の Waseda ID・パスワードを保存する。
struct WasedaCard: View {
    @EnvironmentObject var viewModel: MainViewModel
    @State private var user = ""
    @State private var password = ""

    var body: some View {
        CardView {
            Text("Waseda アカウント連携").font(.headline)
            Text("MyWaseda のログイン情報を保存すると、科目登録（時間割）を自動取得できます。パスワードは暗号化して保存され、時間割取得にのみ使われます。")
                .font(.caption)
            LabeledField(label: "Waseda ID（例: xxxx@akane.waseda.jp）", text: $user)
            LabeledField(label: viewModel.ui.wasedaHasPassword ? "パスワード（変更時のみ入力）" : "パスワード",
                         text: $password, secure: true)
            HStack(spacing: 8) {
                Button("保存") {
                    viewModel.saveWaseda(user: user, password: password)
                    password = ""
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.ui.wasedaBusy || user.isEmpty
                          || (password.isEmpty && !viewModel.ui.wasedaHasPassword))
                Button("時間割を取り込む") { viewModel.syncWaseda() }
                    .buttonStyle(.bordered)
                    .disabled(!viewModel.ui.wasedaHasPassword || viewModel.ui.wasedaSyncRunning)
                if viewModel.ui.wasedaHasPassword {
                    Text("パスワード保存済み").font(.caption)
                }
            }
            if let msg = viewModel.ui.wasedaMessage {
                Text(msg).font(.caption)
            }
            // 取り込み実行中のステータスバー（サーバー側スクレイパの進行状況を表示）。
            if viewModel.ui.wasedaSyncRunning {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let msg = viewModel.ui.wasedaSyncMessage {
                Text(viewModel.ui.wasedaSyncRunning ? "取り込み中: \(msg)" : msg)
                    .font(.caption)
                    .foregroundColor(viewModel.ui.wasedaSyncRunning ? AppTheme.primary : .secondary)
            }
        }
        .onAppear {
            viewModel.loadWaseda()
        }
        .onChange(of: viewModel.ui.wasedaUser) { newValue in
            if user.isEmpty { user = newValue }
        }
    }
}

/// 「1日のまとめ通知」の時刻設定カード。
/// 設定した時刻（複数可）に今日の授業・予定・課題期限をまとめた通知を出す。
struct DigestCard: View {
    @State private var times = DigestTimeStore().times
    @State private var pickerShown = false
    @State private var pickedTime = Calendar.current.date(from: DateComponents(hour: 8, minute: 0)) ?? Date()

    var body: some View {
        CardView {
            Text("1日のまとめ通知").font(.subheadline.bold())
            Text("設定した時刻に、今日の授業・予定・課題の期限をまとめて通知します（複数設定可）。")
                .font(.caption)
            if times.isEmpty {
                Text("通知時刻は未設定です。").font(.caption)
            }
            ForEach(times, id: \.self) { t in
                HStack {
                    Text(t)
                    Spacer()
                    Button("削除") {
                        let store = DigestTimeStore()
                        store.remove(t)
                        times = store.times
                        DailyDigestScheduler.scheduleAll()
                    }
                    .font(.caption)
                }
            }
            if pickerShown {
                DatePicker("通知時刻", selection: $pickedTime, displayedComponents: .hourAndMinute)
                Button("追加") {
                    let c = Calendar.current.dateComponents([.hour, .minute], from: pickedTime)
                    let store = DigestTimeStore()
                    store.add(String(format: "%02d:%02d", c.hour ?? 8, c.minute ?? 0))
                    times = store.times
                    DailyDigestScheduler.scheduleAll()
                    pickerShown = false
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button("＋ 通知時刻を追加") { pickerShown = true }
                    .buttonStyle(.bordered)
            }
        }
    }
}
