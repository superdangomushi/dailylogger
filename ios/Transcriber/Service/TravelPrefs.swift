import Foundation
import CoreLocation

/// 出発・雨・終電アラートの設定と位置情報キャッシュ（Android の TravelPrefs 相当）。
///  - 自宅位置: 「現在地を自宅にする」でGPS座標を保存。出発判定・終電の「外出中」判定に使う。
///  - 所要時間: 自宅→学校（最初の予定の場所）までの分数。出発時刻の逆算に使う。
///  - 終電時刻: 自宅最寄り駅の終電 "HH:MM"。時刻表APIは使わず手動登録。
///
/// 位置情報は常時追跡せず、取れた最後の位置をキャッシュしてバックグラウンド判定に使う。
struct TravelPrefs {

    private let prefs = UserDefaults.standard

    // ---- 機能ごとのON/OFF ----
    var departureEnabled: Bool {
        get { prefs.bool(forKey: "travel.departureEnabled") }
        nonmutating set { prefs.set(newValue, forKey: "travel.departureEnabled") }
    }
    var rainEnabled: Bool {
        get { prefs.bool(forKey: "travel.rainEnabled") }
        nonmutating set { prefs.set(newValue, forKey: "travel.rainEnabled") }
    }
    var lastTrainEnabled: Bool {
        get { prefs.bool(forKey: "travel.lastTrainEnabled") }
        nonmutating set { prefs.set(newValue, forKey: "travel.lastTrainEnabled") }
    }

    // ---- 自宅位置 ----
    var hasHome: Bool { prefs.object(forKey: "travel.homeLat") != nil }
    var homeLat: Double { prefs.double(forKey: "travel.homeLat") }
    var homeLon: Double { prefs.double(forKey: "travel.homeLon") }

    func setHome(lat: Double, lon: Double) {
        prefs.set(lat, forKey: "travel.homeLat")
        prefs.set(lon, forKey: "travel.homeLon")
    }

    // ---- 通学・終電 ----
    /** 自宅→最初の予定の場所までの所要時間（分）。 */
    var commuteMinutes: Int {
        get { let v = prefs.integer(forKey: "travel.commuteMinutes"); return v == 0 ? 60 : v }
        nonmutating set { prefs.set(min(max(newValue, 1), 600), forKey: "travel.commuteMinutes") }
    }

    /** 終電時刻 "HH:MM"。空なら未設定。 */
    var lastTrainTime: String {
        get { prefs.string(forKey: "travel.lastTrainTime") ?? "" }
        nonmutating set { prefs.set(newValue, forKey: "travel.lastTrainTime") }
    }

    // ---- 通知の重複防止キー ----
    var departureNotifiedKey: String {
        get { prefs.string(forKey: "travel.departureNotified") ?? "" }
        nonmutating set { prefs.set(newValue, forKey: "travel.departureNotified") }
    }
    var rainNotifiedKey: String {
        get { prefs.string(forKey: "travel.rainNotified") ?? "" }
        nonmutating set { prefs.set(newValue, forKey: "travel.rainNotified") }
    }
    var lastTrainNotifiedKey: String {
        get { prefs.string(forKey: "travel.lastTrainNotified") ?? "" }
        nonmutating set { prefs.set(newValue, forKey: "travel.lastTrainNotified") }
    }

    // ---- 位置キャッシュ ----
    func cacheLocation(lat: Double, lon: Double, at: Date) {
        prefs.set(lat, forKey: "travel.locLat")
        prefs.set(lon, forKey: "travel.locLon")
        prefs.set(at.timeIntervalSince1970, forKey: "travel.locAt")
    }

    func cachedLocation() -> (lat: Double, lon: Double, at: Date)? {
        guard prefs.object(forKey: "travel.locLat") != nil else { return nil }
        return (
            prefs.double(forKey: "travel.locLat"),
            prefs.double(forKey: "travel.locLon"),
            Date(timeIntervalSince1970: prefs.double(forKey: "travel.locAt"))
        )
    }

    /** 2点間の距離（メートル）。 */
    static func distanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        CLLocation(latitude: lat1, longitude: lon1)
            .distance(from: CLLocation(latitude: lat2, longitude: lon2))
    }
}

/// 位置情報の取得役。アプリ利用中に一度だけ現在地を取り、キャッシュを更新する。
/// 常時追跡はしない（when-in-use 権限のみ）。
final class TravelLocation: NSObject, CLLocationManagerDelegate {

    static let shared = TravelLocation()

    private let manager = CLLocationManager()
    private var freshCallbacks: [(CLLocation?) -> Void] = []

    override private init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// 「現在地を自宅にする」用の一発取得。権限が無ければ要求してから取る。メインスレッドから呼ぶ。
    func fetchFresh(_ callback: @escaping (CLLocation?) -> Void) {
        let status = manager.authorizationStatus
        if status == .denied || status == .restricted {
            callback(nil)
            return
        }
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        freshCallbacks.append(callback)
        manager.requestLocation()
    }

    /// 直近の位置（マネージャの最終位置 or UserDefaults キャッシュ）。バックグラウンド判定用。
    func currentOrCached() -> (lat: Double, lon: Double, at: Date)? {
        let prefs = TravelPrefs()
        if let l = manager.location {
            if let cached = prefs.cachedLocation(), cached.at > l.timestamp {
                return cached
            }
            prefs.cacheLocation(lat: l.coordinate.latitude, lon: l.coordinate.longitude, at: l.timestamp)
            return (l.coordinate.latitude, l.coordinate.longitude, l.timestamp)
        }
        return prefs.cachedLocation()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last {
            TravelPrefs().cacheLocation(lat: l.coordinate.latitude, lon: l.coordinate.longitude, at: l.timestamp)
        }
        let cbs = freshCallbacks
        freshCallbacks = []
        cbs.forEach { $0(locations.last) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let cbs = freshCallbacks
        freshCallbacks = []
        cbs.forEach { $0(nil) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // 権限ダイアログで許可された直後、保留中の取得を再試行する。
        let status = manager.authorizationStatus
        if !freshCallbacks.isEmpty && (status == .authorizedWhenInUse || status == .authorizedAlways) {
            manager.requestLocation()
        }
    }
}
