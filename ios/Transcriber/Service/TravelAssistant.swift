import Foundation
import UserNotifications

/// 出発・雨・終電アラートの本体（Android の TravelAssistant 相当）。
///
///  - 出発アラート: 今日の最初の予定（時間割・Google予定・時刻付きの予定）から
///    所要時間を引いて出発時刻を逆算。雨予報なら10分前倒し＋傘の一言。出発30分前に通知。
///  - 雨アラート: 現在地の15分刻み降水予報で「まもなく降る」「まもなく止む」を通知。
///  - 終電アラート: 夜、自宅から離れた場所にいるときに終電までの残りを通知（60分前・20分前）。
///
/// iOS は任意時刻にコードを実行できないため、チェック時に「未来の発火」をローカル通知として
/// 予約しておく（出発・終電）。雨アラートはチェック時点の即時判定のみ（ベストエフォート）。
/// 出発・雨は NotificationPrefs（通知OFF・おやすみモード）に従う。
/// 終電だけは「帰れなくなる」実害があるため、おやすみモード中でも通知する（マスターOFFには従う）。
enum TravelAssistant {

    /// 自宅からこの距離（m）以上離れていたら「外出中」とみなす。
    private static let awayMeters = 800.0
    /// 出発の何分前に通知するか。
    private static let departureLeadMin = 30.0
    /// 雨予報（降水確率がこの%以上）なら出発を10分前倒し＋傘を促す。
    private static let umbrellaProb = 50
    private static let rainExtraMin = 10.0

    private static let idDeparture = "travel-departure"
    private static let idLastTrain60 = "travel-lasttrain-60"
    private static let idLastTrain20 = "travel-lasttrain-20"

    /// 予定タブの状況カード用スナップショット。
    struct Status {
        var nextEventTitle: String? = nil
        var nextEventStart: String? = nil    // "HH:mm"
        var departureAt: String? = nil       // "HH:mm"
        var departureInMin: Int? = nil       // 負なら出発時刻を過ぎている
        var umbrella = false
        var precipProb: Int? = nil
        var temp: Double? = nil
        var rainingNow = false
        var rainStartsAt: String? = nil
        var rainStopsAt: String? = nil
        var lastTrainAt: String? = nil       // "HH:mm"
        var lastTrainInMin: Int? = nil
        var awayFromHome: Bool? = nil        // nil = 位置不明
    }

    /// 定期チェック。ブロッキング（天気・サーバー通信）なのでワーカースレッドから呼ぶ。
    static func check() {
        let prefs = TravelPrefs()
        guard prefs.departureEnabled || prefs.rainEnabled || prefs.lastTrainEnabled else { return }
        let notif = NotificationPrefs()
        let loc = TravelLocation.shared.currentOrCached()

        if prefs.departureEnabled && !notif.shouldSuppressNow() {
            checkDeparture(prefs, loc)
        }
        if prefs.rainEnabled && !notif.shouldSuppressNow() {
            checkRain(prefs, loc)
        }
        // 終電はおやすみモードより優先（マスターOFFのみ従う）。
        if prefs.lastTrainEnabled && notif.enabled {
            checkLastTrain(prefs, loc)
        }
    }

    // ---- 出発 ----

    private static func checkDeparture(_ prefs: TravelPrefs, _ loc: (lat: Double, lon: Double, at: Date)?) {
        guard prefs.hasHome else { return }
        // すでに家を出ている（自宅から離れている）なら出発通知は不要。
        if let l = loc,
           TravelPrefs.distanceMeters(l.lat, l.lon, prefs.homeLat, prefs.homeLon) > awayMeters {
            cancel([idDeparture])
            return
        }

        let now = Date()
        guard let event = firstEventToday(now) else {
            cancel([idDeparture])
            return
        }
        // 出発時の天気は自宅の予報で判定する。
        let forecast = WeatherClient.fetch(lat: prefs.homeLat, lon: prefs.homeLon)
        let prob = forecast?.maxPrecipProb(from: now, minutes: Double(prefs.commuteMinutes) + 60) ?? 0
        let umbrella = prob >= umbrellaProb
        let departure = event.start.addingTimeInterval(
            -(Double(prefs.commuteMinutes) + (umbrella ? rainExtraMin : 0)) * 60
        )

        let key = "\(YMD(now).isoString)/\(hm(event.start))/\(event.title)"
        let minutesTo = Int(departure.timeIntervalSince(now) / 60)
        let title: String
        if minutesTo <= 0 && minutesTo >= -10 {
            title = "⏰ もう出発の時間です（\(hm(departure))）"
        } else {
            title = "⏰ \(hm(departure)) に出発（あと\(minutesTo)分）"
        }
        var body = "\(event.title) \(hm(event.start)) に間に合う出発目安です。"
        if umbrella { body += "\n☔ 降水確率\(prob)%。傘を持って、少し早めに。" }

        if minutesTo <= Int(departureLeadMin) && minutesTo >= -10 {
            // すでに通知ウィンドウ内: 即時に1回だけ。
            guard prefs.departureNotifiedKey != key else { return }
            prefs.departureNotifiedKey = key
            notify(id: idDeparture, title: title, body: body)
        } else if minutesTo > Int(departureLeadMin) {
            // 未来: 出発30分前のローカル通知を予約（次のチェックが走らなくても届くように）。
            // 内容は予約時点の天気で作る（多少古くなるのは許容）。
            guard prefs.departureNotifiedKey != key else { return }
            prefs.departureNotifiedKey = key
            let fireAt = departure.addingTimeInterval(-departureLeadMin * 60)
            schedule(
                id: idDeparture,
                title: "⏰ \(hm(departure)) に出発（30分前のお知らせ）",
                body: body,
                at: fireAt
            )
        }
    }

    /// 今日のこれから始まる最初の予定 (名前, 開始時刻)。
    /// 時間割の授業・Googleカレンダー予定・時刻付きの「予定」タスクから最も早いものを選ぶ。
    private static func firstEventToday(_ now: Date) -> (title: String, start: Date)? {
        let store = AccountStore()
        let today = YMD(now)
        var candidates: [(String, Date)] = []

        if store.loggedIn {
            let client = AiHelperClient()
            // 時間割の授業。
            let courses = (try? client.fetchCourses(baseUrl: store.baseUrl, email: store.email, token: store.token).get()) ?? []
            for c in courses where courseOccursOn(c, today) {
                let start = String(courseTime(c).split(separator: "〜").first ?? "")
                if let d = dateAt(today, hhmm: start) { candidates.append((c.name, d)) }
            }
            // 時刻付きの「予定」タスク。
            let tasks = (try? client.fetchTasks(baseUrl: store.baseUrl, email: store.email, token: store.token, includeDone: false).get()) ?? []
            for t in tasks where t.type == "yotei" && !t.dateOnly && (t.deadline ?? "").prefix(10) == today.isoString {
                let norm = (t.deadline ?? "").replacingOccurrences(of: "T", with: " ")
                if norm.count >= 16, let d = dateAt(today, hhmm: String(norm.dropFirst(11).prefix(5))) {
                    candidates.append((t.content, d))
                }
            }
        }
        // Googleカレンダーの今日の予定（バックグラウンドでは再認可が必要な場合があり、失敗は無視）。
        let googleStore = GoogleAccountStore()
        for email in googleStore.emails {
            guard let token = try? GoogleCalendarClient.accessToken(googleStore, email: email) else { continue }
            guard case .success(let events) = GoogleCalendarClient.listUpcomingEvents(token: token) else { continue }
            for ev in events where ev.startMillis > 0 {
                let d = Date(timeIntervalSince1970: Double(ev.startMillis) / 1000)
                if YMD(d) == today { candidates.append((ev.title, d)) }
            }
        }
        return candidates.filter { $0.1 > now }.min { $0.1 < $1.1 }
            .map { (title: $0.0, start: $0.1) }
    }

    // ---- 雨 ----

    private static func checkRain(_ prefs: TravelPrefs, _ loc: (lat: Double, lon: Double, at: Date)?) {
        // 現在地が取れなければ自宅で代用（どちらも無ければ何もできない）。
        let lat: Double, lon: Double
        if let l = loc { lat = l.lat; lon = l.lon }
        else if prefs.hasHome { lat = prefs.homeLat; lon = prefs.homeLon }
        else { return }

        guard let forecast = WeatherClient.fetch(lat: lat, lon: lon) else { return }
        let now = Date()
        let key: String, title: String, body: String
        if let startsAt = forecast.rainStartsAt(now, withinMinutes: 60) {
            key = "start-\(YMD(now).isoString)-\(startsAt)"
            title = "☔ \(startsAt)ごろから雨"
            body = "現在地のあたりでまもなく降り出しそうです。洗濯物・傘に注意。"
        } else if let stopsAt = forecast.rainStopsAt(now, withinMinutes: 90) {
            key = "stop-\(YMD(now).isoString)-\(stopsAt)"
            title = "☂ \(stopsAt)ごろに雨が止みそう"
            body = "移動するならそのタイミングがおすすめです。"
        } else {
            return
        }
        guard prefs.rainNotifiedKey != key else { return }
        prefs.rainNotifiedKey = key
        notify(id: "travel-rain", title: title, body: body)
    }

    // ---- 終電 ----

    private static func checkLastTrain(_ prefs: TravelPrefs, _ loc: (lat: Double, lon: Double, at: Date)?) {
        guard let target = nextLastTrain(prefs.lastTrainTime, now: Date()) else {
            cancel([idLastTrain60, idLastTrain20])
            return
        }
        // 自宅にいる（または位置が分からない）なら予約も取り消す。
        guard prefs.hasHome, let l = loc,
              TravelPrefs.distanceMeters(l.lat, l.lon, prefs.homeLat, prefs.homeLon) > awayMeters else {
            cancel([idLastTrain60, idLastTrain20])
            return
        }

        let now = Date()
        let remaining = Int(target.timeIntervalSince(now) / 60)
        let bodyText = "自宅から離れた場所にいます。帰るならそろそろ駅へ。"

        // 未来の 60分前・20分前をローカル通知として予約（次のチェックが走らなくても届くように）。
        for (id, lead) in [(idLastTrain60, 60), (idLastTrain20, 20)] where remaining > lead {
            schedule(
                id: id,
                title: "🚃 終電 \(hm(target)) まであと\(lead)分",
                body: bodyText,
                at: target.addingTimeInterval(-Double(lead) * 60)
            )
        }
        // すでにウィンドウ内なら即時に1回だけ。
        let stage: String
        switch remaining {
        case 0...20: stage = "20"
        case 21...60: stage = "60"
        default: return
        }
        let key = "\(YMD(target).isoString)/\(hm(target))/\(stage)"
        guard prefs.lastTrainNotifiedKey != key else { return }
        prefs.lastTrainNotifiedKey = key
        cancel([stage == "20" ? idLastTrain20 : idLastTrain60])
        notify(
            id: "travel-lasttrain-now",
            title: "🚃 終電 \(hm(target)) まであと\(remaining)分",
            body: bodyText
        )
    }

    /// 設定された終電時刻 "HH:MM" の「次の発車日時」。
    /// 0:24 のような深夜時刻は日をまたいで今夜の終電として扱う。
    /// 発車まで12時間より先（＝まだ夜ではない）なら nil。
    static func nextLastTrain(_ hhmm: String, now: Date) -> Date? {
        let parts = hhmm.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        var target = dateAt(YMD(now), hhmm: hhmm) ?? now
        while target <= now { target.addTimeInterval(24 * 3600) }
        return target.timeIntervalSince(now) >= 12 * 3600 ? nil : target
    }

    // ---- 状況カード用 ----

    /// 予定タブのカードに出す現在の状況。ブロッキングなのでワーカースレッドから呼ぶ。
    static func status() -> Status {
        let prefs = TravelPrefs()
        let now = Date()
        let loc = TravelLocation.shared.currentOrCached()
        let lat: Double, lon: Double
        if let l = loc { lat = l.lat; lon = l.lon }
        else if prefs.hasHome { lat = prefs.homeLat; lon = prefs.homeLon }
        else { return Status() }

        let forecast = WeatherClient.fetch(lat: lat, lon: lon)
        var s = Status(
            temp: forecast?.currentTemp(now),
            rainingNow: forecast?.rainingNow(now) ?? false,
            rainStartsAt: forecast?.rainStartsAt(now, withinMinutes: 120),
            rainStopsAt: forecast?.rainStopsAt(now, withinMinutes: 120)
        )
        if let l = loc, prefs.hasHome {
            s.awayFromHome = TravelPrefs.distanceMeters(l.lat, l.lon, prefs.homeLat, prefs.homeLon) > awayMeters
        }

        if prefs.departureEnabled, let event = firstEventToday(now) {
            let prob = forecast?.maxPrecipProb(from: now, minutes: Double(prefs.commuteMinutes) + 60) ?? 0
            let umbrella = prob >= umbrellaProb
            let departure = event.start.addingTimeInterval(
                -(Double(prefs.commuteMinutes) + (umbrella ? rainExtraMin : 0)) * 60
            )
            s.nextEventTitle = event.title
            s.nextEventStart = hm(event.start)
            s.departureAt = hm(departure)
            s.departureInMin = Int(departure.timeIntervalSince(now) / 60)
            s.umbrella = umbrella
            s.precipProb = prob
        }
        if prefs.lastTrainEnabled, let target = nextLastTrain(prefs.lastTrainTime, now: now) {
            s.lastTrainAt = hm(target)
            s.lastTrainInMin = Int(target.timeIntervalSince(now) / 60)
        }
        return s
    }

    // ---- 共通 ----

    private static func hm(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private static func dateAt(_ ymd: YMD, hhmm: String) -> Date? {
        let parts = hhmm.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return Calendar.current.date(from: DateComponents(
            year: ymd.year, month: ymd.month, day: ymd.day, hour: parts[0], minute: parts[1]
        ))
    }

    private static func notify(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: nil)
        )
    }

    private static func schedule(id: String, title: String, body: String, at date: Date) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: trigger)
        )
    }

    private static func cancel(_ ids: [String]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }
}
