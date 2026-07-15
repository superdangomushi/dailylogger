import Foundation

/// 通知の受け取り設定（Android の NotificationPrefs 相当）。
///  - `enabled`: 通知のマスタースイッチ。false ならリマインド・まとめ通知を一切出さない。
///  - `quietEnabled` / `quietStart` / `quietEnd`: おやすみモード。指定した時間帯は通知しない。
///    開始 > 終了なら日をまたぐ夜間帯（例: 23:00〜07:00）として扱う。
///
/// 対象は締切リマインド（ReminderNotifier）と1日のまとめ（DailyDigest）だけ。
struct NotificationPrefs {

    private let prefs = UserDefaults.standard

    var enabled: Bool {
        get { prefs.object(forKey: Key.enabled) as? Bool ?? true }
        nonmutating set { prefs.set(newValue, forKey: Key.enabled) }
    }

    var quietEnabled: Bool {
        get { prefs.bool(forKey: Key.quietEnabled) }
        nonmutating set { prefs.set(newValue, forKey: Key.quietEnabled) }
    }

    /// "HH:MM"。既定は一般的な就寝〜起床帯。
    var quietStart: String {
        get { prefs.string(forKey: Key.quietStart) ?? "23:00" }
        nonmutating set { prefs.set(newValue, forKey: Key.quietStart) }
    }

    var quietEnd: String {
        get { prefs.string(forKey: Key.quietEnd) ?? "07:00" }
        nonmutating set { prefs.set(newValue, forKey: Key.quietEnd) }
    }

    /// 今この瞬間、通知を抑制すべきか。マスターOFF、またはおやすみ時間帯なら true。
    func shouldSuppressNow(_ now: Date = Date()) -> Bool {
        if !enabled { return true }
        if quietEnabled && isQuiet(at: now) { return true }
        return false
    }

    /// 指定時刻がおやすみ時間帯に入るか。開始 > 終了なら日またぎ（夜間）とみなす。
    func isQuiet(at date: Date) -> Bool {
        guard let start = minutes(quietStart), let end = minutes(quietEnd), start != end else { return false }
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        let now = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        if start < end {
            return now >= start && now < end          // 同日内
        } else {
            return now >= start || now < end          // 日またぎ
        }
    }

    /// "HH:MM" のような時刻が丸ごとおやすみ帯に含まれるか（digest のスケジュール判定用）。
    func isQuiet(timeString hhmm: String) -> Bool {
        guard quietEnabled, let mins = minutes(hhmm) else { return false }
        guard let start = minutes(quietStart), let end = minutes(quietEnd), start != end else { return false }
        if start < end {
            return mins >= start && mins < end
        } else {
            return mins >= start || mins < end
        }
    }

    private func minutes(_ hhmm: String) -> Int? {
        let p = hhmm.split(separator: ":").compactMap { Int($0) }
        guard p.count == 2 else { return nil }
        return p[0] * 60 + p[1]
    }

    private enum Key {
        static let enabled = "notif.enabled"
        static let quietEnabled = "notif.quietEnabled"
        static let quietStart = "notif.quietStart"
        static let quietEnd = "notif.quietEnd"
    }
}
