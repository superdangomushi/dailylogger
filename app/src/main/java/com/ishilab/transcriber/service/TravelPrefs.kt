package com.ishilab.transcriber.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 出発・雨・終電アラートの設定と、位置情報のキャッシュ。
 *  - 自宅位置: 「現在地を自宅にする」でGPS座標を保存。出発判定・終電の「外出中」判定に使う。
 *  - 所要時間: 自宅→学校（最初の予定の場所）までの分数。出発時刻の逆算に使う。
 *  - 終電時刻: 自宅最寄り駅の終電 "HH:MM"。時刻表APIは使わず手動登録（シンプル優先）。
 *
 * 位置情報は常時追跡せず、アプリ利用中に取れた最後の位置をキャッシュして
 * バックグラウンドの判定に使う（バッテリーと権限の負担を避けるため）。
 */
class TravelPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 機能ごとのON/OFF ----
    var departureEnabled: Boolean
        get() = prefs.getBoolean("departure_enabled", false)
        set(v) = prefs.edit().putBoolean("departure_enabled", v).apply()

    var rainEnabled: Boolean
        get() = prefs.getBoolean("rain_enabled", false)
        set(v) = prefs.edit().putBoolean("rain_enabled", v).apply()

    var lastTrainEnabled: Boolean
        get() = prefs.getBoolean("last_train_enabled", false)
        set(v) = prefs.edit().putBoolean("last_train_enabled", v).apply()

    // ---- 自宅位置 ----
    val hasHome: Boolean get() = prefs.contains("home_lat")

    var homeLat: Double
        get() = Double.fromBits(prefs.getLong("home_lat", 0L))
        private set(v) = prefs.edit().putLong("home_lat", v.toRawBits()).apply()

    var homeLon: Double
        get() = Double.fromBits(prefs.getLong("home_lon", 0L))
        private set(v) = prefs.edit().putLong("home_lon", v.toRawBits()).apply()

    fun setHome(lat: Double, lon: Double) {
        homeLat = lat
        homeLon = lon
    }

    fun clearHome() {
        prefs.edit().remove("home_lat").remove("home_lon").apply()
    }

    // ---- 通学・終電 ----
    /** 自宅→最初の予定の場所までの所要時間（分）。 */
    var commuteMinutes: Int
        get() = prefs.getInt("commute_minutes", 60)
        set(v) = prefs.edit().putInt("commute_minutes", v.coerceIn(1, 600)).apply()

    /** 終電時刻 "HH:MM"。空なら未設定。 */
    var lastTrainTime: String
        get() = prefs.getString("last_train_time", "") ?: ""
        set(v) = prefs.edit().putString("last_train_time", v).apply()

    // ---- 通知の重複防止（1イベント1回だけ通知するためのキー） ----
    var departureNotifiedKey: String
        get() = prefs.getString("departure_notified", "") ?: ""
        set(v) = prefs.edit().putString("departure_notified", v).apply()

    var rainNotifiedKey: String
        get() = prefs.getString("rain_notified", "") ?: ""
        set(v) = prefs.edit().putString("rain_notified", v).apply()

    var lastTrainNotifiedKey: String
        get() = prefs.getString("last_train_notified", "") ?: ""
        set(v) = prefs.edit().putString("last_train_notified", v).apply()

    // ---- 位置キャッシュ ----
    fun cacheLocation(lat: Double, lon: Double, atMillis: Long) {
        prefs.edit()
            .putLong("loc_lat", lat.toRawBits())
            .putLong("loc_lon", lon.toRawBits())
            .putLong("loc_at", atMillis)
            .apply()
    }

    /** キャッシュ済みの位置 (lat, lon, 取得時刻millis)。無ければ null。 */
    fun cachedLocation(): Triple<Double, Double, Long>? {
        if (!prefs.contains("loc_lat")) return null
        return Triple(
            Double.fromBits(prefs.getLong("loc_lat", 0L)),
            Double.fromBits(prefs.getLong("loc_lon", 0L)),
            prefs.getLong("loc_at", 0L),
        )
    }

    companion object {
        private const val PREFS = "travel_prefs"

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * 現在地（各プロバイダの最終既知位置のうち最新）を返し、取れたらキャッシュも更新する。
         * バックグラウンドで取れない・権限が無いときはキャッシュを返す。どちらも無ければ null。
         */
        fun currentOrCachedLocation(context: Context): Triple<Double, Double, Long>? {
            val prefs = TravelPrefs(context)
            var best: Location? = null
            if (hasLocationPermission(context)) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    for (provider in lm.allProviders) {
                        val loc = try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
                        if (loc != null && (best == null || loc.time > best!!.time)) best = loc
                    }
                } catch (_: Exception) {
                }
            }
            val cached = prefs.cachedLocation()
            val b = best
            return when {
                b != null && (cached == null || b.time >= cached.third) -> {
                    prefs.cacheLocation(b.latitude, b.longitude, b.time)
                    Triple(b.latitude, b.longitude, b.time)
                }
                cached != null -> cached
                else -> null
            }
        }

        /**
         * 「現在地を自宅にする」用に、なるべく新しい位置を1回だけ取得して callback に返す。
         * R+ は getCurrentLocation、それ未満は最終既知位置で代用。取れなければ null。
         */
        fun fetchFreshLocation(context: Context, callback: (Location?) -> Unit) {
            if (!hasLocationPermission(context)) { callback(null); return }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> LocationManager.PASSIVE_PROVIDER
                    }
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                        if (loc != null) TravelPrefs(context).cacheLocation(loc.latitude, loc.longitude, loc.time)
                        callback(loc)
                    }
                } else {
                    val loc = currentOrCachedLocation(context)
                    callback(loc?.let { Location("").apply { latitude = it.first; longitude = it.second } })
                }
            } catch (_: SecurityException) {
                callback(null)
            }
        }

        /** 2点間の距離（メートル）。終電の「外出中」判定などに使う。 */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, out)
            return out[0]
        }
    }
}
