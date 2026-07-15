import Foundation

/// Open-Meteo の天気予報クライアント（APIキー不要・無料。Android の WeatherClient 相当）。
/// 出発・雨アラートと予定タブの天気カードで使う。ブロッキングなのでワーカースレッドから呼ぶ。
/// 同じ地点（小数2桁で丸め）のレスポンスは10分間キャッシュする。
enum WeatherClient {

    private static let cacheTtl: TimeInterval = 10 * 60

    /// 1地点の予報。時刻は "yyyy-MM-dd'T'HH:mm"（端末タイムゾーン）。
    struct Forecast {
        let fetchedAt: Date
        /// 1時間ごと（今日〜明日）。
        let hourlyTimes: [String]
        let hourlyPrecipProb: [Int]
        let hourlyTemp: [Double]
        /// 15分ごとの降水量 mm。
        let quarterTimes: [String]
        let quarterPrecip: [Double]

        private static let fmt: DateFormatter = {
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd'T'HH:mm"
            f.locale = Locale(identifier: "en_US_POSIX")
            return f
        }()

        private func idxAtOrAfter(_ times: [String], _ t: Date, stepMin: Double) -> Int? {
            let i = times.firstIndex {
                guard let parsed = Self.fmt.date(from: $0) else { return false }
                return parsed.addingTimeInterval(stepMin * 60) >= t  // その枠の終端が t 以降
            }
            return i
        }

        /// 現在の気温（直近の時間枠）。
        func currentTemp(_ now: Date = Date()) -> Double? {
            guard let i = idxAtOrAfter(hourlyTimes, now, stepMin: 60), i < hourlyTemp.count else { return nil }
            return hourlyTemp[i]
        }

        /// [from, from+minutes] の最大降水確率（%）。データが無ければ nil。
        func maxPrecipProb(from: Date, minutes: Double) -> Int? {
            guard let start = idxAtOrAfter(hourlyTimes, from, stepMin: 60) else { return nil }
            let end = idxAtOrAfter(hourlyTimes, from.addingTimeInterval(minutes * 60), stepMin: 60)
                ?? hourlyTimes.count - 1
            let probs = (start...max(start, end)).compactMap { $0 < hourlyPrecipProb.count ? hourlyPrecipProb[$0] : nil }
            return probs.max()
        }

        /// 今雨が降っているか（現在の15分枠の降水量 ≥ 0.1mm）。
        func rainingNow(_ now: Date = Date()) -> Bool {
            guard let i = idxAtOrAfter(quarterTimes, now, stepMin: 15), i < quarterPrecip.count else { return false }
            return quarterPrecip[i] >= 0.1
        }

        /// これから withinMinutes 以内に雨が降り始める時刻（"HH:mm"）。今降っている/降らないなら nil。
        func rainStartsAt(_ now: Date = Date(), withinMinutes: Int = 60) -> String? {
            if rainingNow(now) { return nil }
            guard let start = idxAtOrAfter(quarterTimes, now, stepMin: 15) else { return nil }
            let steps = withinMinutes / 15
            for i in start...min(start + steps, quarterPrecip.count - 1) where quarterPrecip[i] >= 0.2 {
                return String(quarterTimes[i].suffix(5))
            }
            return nil
        }

        /// 今降っている雨が withinMinutes 以内に止む時刻（"HH:mm"）。止まなければ nil。
        func rainStopsAt(_ now: Date = Date(), withinMinutes: Int = 90) -> String? {
            if !rainingNow(now) { return nil }
            guard let start = idxAtOrAfter(quarterTimes, now, stepMin: 15) else { return nil }
            let steps = withinMinutes / 15
            for i in start...min(start + steps, quarterPrecip.count - 1) {
                // 2枠（30分）連続で降水ほぼゼロなら「止んだ」とみなす。
                let next = i + 1 < quarterPrecip.count ? quarterPrecip[i + 1] : 0
                if quarterPrecip[i] < 0.1 && next < 0.1 { return String(quarterTimes[i].suffix(5)) }
            }
            return nil
        }
    }

    /// 予報を取得（10分キャッシュ付き）。失敗時は nil。
    static func fetch(lat: Double, lon: Double) -> Forecast? {
        let prefs = UserDefaults.standard
        let key = "\(Int((lat * 100).rounded())),\(Int((lon * 100).rounded()))"
        let cachedAt = Date(timeIntervalSince1970: prefs.double(forKey: "weather.at"))
        if prefs.string(forKey: "weather.key") == key,
           Date().timeIntervalSince(cachedAt) < cacheTtl,
           let json = prefs.data(forKey: "weather.json"),
           let f = parse(json, fetchedAt: cachedAt) {
            return f
        }
        let url = "https://api.open-meteo.com/v1/forecast?latitude=\(lat)&longitude=\(lon)" +
            "&hourly=precipitation_probability,temperature_2m" +
            "&minutely_15=precipitation&forecast_days=2&timezone=auto"
        guard let u = URL(string: url) else { return nil }

        // URLSession を同期実行（AiHelperClient と同じ流儀）。
        let semaphore = DispatchSemaphore(value: 0)
        var body: Data?
        let task = URLSession.shared.dataTask(with: u) { data, response, _ in
            if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                body = data
            }
            semaphore.signal()
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 15)
        guard let data = body else { return nil }
        let now = Date()
        prefs.set(key, forKey: "weather.key")
        prefs.set(now.timeIntervalSince1970, forKey: "weather.at")
        prefs.set(data, forKey: "weather.json")
        return parse(data, fetchedAt: now)
    }

    private static func parse(_ data: Data, fetchedAt: Date) -> Forecast? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func strings(_ obj: [String: Any]?, _ name: String) -> [String] {
            (obj?[name] as? [Any])?.compactMap { $0 as? String } ?? []
        }
        func doubles(_ obj: [String: Any]?, _ name: String) -> [Double] {
            (obj?[name] as? [Any])?.map { ($0 as? NSNumber)?.doubleValue ?? 0 } ?? []
        }
        let hourly = root["hourly"] as? [String: Any]
        let quarter = root["minutely_15"] as? [String: Any]
        return Forecast(
            fetchedAt: fetchedAt,
            hourlyTimes: strings(hourly, "time"),
            hourlyPrecipProb: doubles(hourly, "precipitation_probability").map { Int($0.rounded()) },
            hourlyTemp: doubles(hourly, "temperature_2m"),
            quarterTimes: strings(quarter, "time"),
            quarterPrecip: doubles(quarter, "precipitation")
        )
    }
}
