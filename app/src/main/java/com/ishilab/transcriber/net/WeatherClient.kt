package com.ishilab.transcriber.net

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Open-Meteo の天気予報クライアント（APIキー不要・無料）。
 * 出発・雨アラートと予定タブの天気カードで使う。
 * 通信はブロッキングなので必ずワーカースレッドから呼ぶこと。
 *
 * 15分ごとの定期チェックから呼ばれるため、同じ地点（小数2桁で丸め）の
 * レスポンスは10分間キャッシュして API への負荷と通信量を抑える。
 */
object WeatherClient {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val PREFS = "weather_cache"

    /** 1地点の予報。時刻は "yyyy-MM-dd'T'HH:mm"（端末タイムゾーン）。 */
    data class Forecast(
        val fetchedAt: Long,
        /** 1時間ごと（今日〜明日）。 */
        val hourlyTimes: List<String>,
        val hourlyPrecipProb: List<Int>,
        val hourlyTemp: List<Double>,
        /** 15分ごとの降水量 mm（直近数時間の「雨が来る/止む」判定用）。 */
        val quarterTimes: List<String>,
        val quarterPrecip: List<Double>,
    ) {
        private fun idxAtOrAfter(times: List<String>, t: LocalDateTime, stepMin: Long): Int {
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            return times.indexOfFirst {
                val parsed = runCatching { LocalDateTime.parse(it, fmt) }.getOrNull() ?: return@indexOfFirst false
                !parsed.plusMinutes(stepMin).isBefore(t)  // その枠の終端が t 以降 = t を含む枠
            }
        }

        /** 現在の気温（直近の時間枠）。 */
        fun currentTemp(now: LocalDateTime = LocalDateTime.now()): Double? {
            val i = idxAtOrAfter(hourlyTimes, now, 60)
            return hourlyTemp.getOrNull(i)
        }

        /** [from, from+minutes] の最大降水確率（%）。データが無ければ null。 */
        fun maxPrecipProb(from: LocalDateTime, minutes: Long): Int? {
            val start = idxAtOrAfter(hourlyTimes, from, 60)
            if (start < 0) return null
            val end = idxAtOrAfter(hourlyTimes, from.plusMinutes(minutes), 60)
                .let { if (it < 0) hourlyTimes.lastIndex else it }
            return (start..end).mapNotNull { hourlyPrecipProb.getOrNull(it) }.maxOrNull()
        }

        /** 今雨が降っているか（現在の15分枠の降水量 ≥ 0.1mm）。 */
        fun rainingNow(now: LocalDateTime = LocalDateTime.now()): Boolean {
            val i = idxAtOrAfter(quarterTimes, now, 15)
            return (quarterPrecip.getOrNull(i) ?: 0.0) >= 0.1
        }

        /**
         * これから [withinMinutes] 以内に雨が降り始める時刻（"HH:mm"）。降らなければ null。
         * 今すでに降っている場合も null（「降り出す」通知の対象外）。
         */
        fun rainStartsAt(now: LocalDateTime = LocalDateTime.now(), withinMinutes: Long = 60): String? {
            if (rainingNow(now)) return null
            val start = idxAtOrAfter(quarterTimes, now, 15)
            if (start < 0) return null
            val steps = (withinMinutes / 15).toInt()
            for (i in start..minOf(start + steps, quarterPrecip.lastIndex)) {
                if (quarterPrecip[i] >= 0.2) return quarterTimes[i].takeLast(5)
            }
            return null
        }

        /**
         * 今降っている雨が [withinMinutes] 以内に止む時刻（"HH:mm"）。止まなければ null。
         * 「今出れば濡れない」ではなく「もうすぐ止む」の通知に使う。
         */
        fun rainStopsAt(now: LocalDateTime = LocalDateTime.now(), withinMinutes: Long = 90): String? {
            if (!rainingNow(now)) return null
            val start = idxAtOrAfter(quarterTimes, now, 15)
            if (start < 0) return null
            val steps = (withinMinutes / 15).toInt()
            for (i in start..minOf(start + steps, quarterPrecip.lastIndex)) {
                // 2枠（30分）連続で降水ほぼゼロなら「止んだ」とみなす。
                val next = quarterPrecip.getOrNull(i + 1) ?: 0.0
                if (quarterPrecip[i] < 0.1 && next < 0.1) return quarterTimes[i].takeLast(5)
            }
            return null
        }
    }

    /** 予報を取得（10分キャッシュ付き）。失敗時は null。 */
    fun fetch(context: Context, lat: Double, lon: Double): Forecast? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 座標は小数2桁（約1km）で丸めてキャッシュキーにする。
        val key = "${(lat * 100).roundToInt()},${(lon * 100).roundToInt()}"
        val cachedKey = prefs.getString("key", "")
        val cachedAt = prefs.getLong("at", 0L)
        if (cachedKey == key && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            prefs.getString("json", null)?.let { json ->
                runCatching { return parse(json, cachedAt) }
            }
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=precipitation_probability,temperature_2m" +
            "&minutely_15=precipitation&forecast_days=2&timezone=auto"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val now = System.currentTimeMillis()
            prefs.edit().putString("key", key).putLong("at", now).putString("json", body).apply()
            parse(body, now)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: String, fetchedAt: Long): Forecast {
        val root = JSONObject(json)
        fun strings(obj: JSONObject, name: String): List<String> {
            val arr = obj.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).map { arr.optString(it) }
        }
        fun doubles(obj: JSONObject, name: String): List<Double> {
            val arr = obj.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).map { arr.optDouble(it, 0.0) }
        }
        val hourly = root.optJSONObject("hourly") ?: JSONObject()
        val quarter = root.optJSONObject("minutely_15") ?: JSONObject()
        return Forecast(
            fetchedAt = fetchedAt,
            hourlyTimes = strings(hourly, "time"),
            hourlyPrecipProb = doubles(hourly, "precipitation_probability").map { it.roundToInt() },
            hourlyTemp = doubles(hourly, "temperature_2m"),
            quarterTimes = strings(quarter, "time"),
            quarterPrecip = doubles(quarter, "precipitation"),
        )
    }
}
