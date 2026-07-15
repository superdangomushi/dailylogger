package com.ishilab.transcriber.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ishilab.transcriber.MainActivity
import com.ishilab.transcriber.courseOccursOn
import com.ishilab.transcriber.courseTime
import com.ishilab.transcriber.net.AccountStore
import com.ishilab.transcriber.net.AiHelperClient
import com.ishilab.transcriber.net.WeatherClient
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 出発・雨・終電アラートの本体。15分ごとの定期チェック（ReminderReceiver）から呼ばれる。
 *
 *  - 出発アラート: 今日の最初の予定（時間割・Google予定・時刻付きの予定）から
 *    所要時間を引いて出発時刻を逆算。雨予報なら10分前倒し＋傘の一言。出発30分前に通知。
 *  - 雨アラート: 現在地の15分刻み降水予報で「まもなく降る」「まもなく止む」を通知。
 *  - 終電アラート: 夜、自宅から離れた場所にいるときに終電までの残りを通知（60分前・20分前）。
 *
 * 出発・雨は NotificationPrefs（通知OFF・おやすみモード）に従う。
 * 終電だけは「帰れなくなる」実害があるため、おやすみモード中でも通知する（マスターOFFには従う）。
 */
object TravelAssistant {

    private const val TAG = "TravelAssistant"
    const val CHANNEL_ID = "travel_alerts"
    private const val NOTIF_DEPARTURE = 7001
    private const val NOTIF_RAIN = 7002
    private const val NOTIF_LAST_TRAIN = 7003

    /** 自宅からこの距離（m）以上離れていたら「外出中」とみなす。 */
    private const val AWAY_METERS = 800f
    /** 出発の何分前に通知するか。 */
    private const val DEPARTURE_LEAD_MIN = 30L
    /** 雨予報（降水確率がこの%以上）なら出発を10分前倒し＋傘を促す。 */
    private const val UMBRELLA_PROB = 50
    private const val RAIN_EXTRA_MIN = 10L

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "出発・雨・終電アラート", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "出発時刻の目安、雨の降り出し/止み、終電までの残り時間を通知する" }
        nm.createNotificationChannel(channel)
    }

    /** 予定タブの状況カード用スナップショット。通信を含むためワーカースレッドで作ること。 */
    data class Status(
        val nextEventTitle: String? = null,
        val nextEventStart: String? = null,   // "HH:mm"
        val departureAt: String? = null,      // "HH:mm"
        val departureInMin: Long? = null,     // 負なら出発時刻を過ぎている
        val umbrella: Boolean = false,
        val precipProb: Int? = null,
        val temp: Double? = null,
        val rainingNow: Boolean = false,
        val rainStartsAt: String? = null,
        val rainStopsAt: String? = null,
        val lastTrainAt: String? = null,      // "HH:mm"（設定済みで今夜が対象のときのみ）
        val lastTrainInMin: Long? = null,
        val awayFromHome: Boolean? = null,    // null = 位置不明
    )

    /** 定期チェック。ブロッキング（天気・サーバー通信）なのでワーカースレッドから呼ぶ。 */
    fun check(context: Context) {
        val prefs = TravelPrefs(context)
        if (!prefs.departureEnabled && !prefs.rainEnabled && !prefs.lastTrainEnabled) return
        ensureChannel(context)
        val notif = NotificationPrefs(context)
        val loc = TravelPrefs.currentOrCachedLocation(context)

        if (prefs.departureEnabled && !notif.shouldSuppressNow()) {
            runCatching { checkDeparture(context, prefs, loc) }
                .onFailure { Log.w(TAG, "departure check failed: ${it.message}") }
        }
        if (prefs.rainEnabled && !notif.shouldSuppressNow()) {
            runCatching { checkRain(context, prefs, loc) }
                .onFailure { Log.w(TAG, "rain check failed: ${it.message}") }
        }
        // 終電はおやすみモードより優先（マスターOFFのみ従う）。
        if (prefs.lastTrainEnabled && notif.enabled) {
            runCatching { checkLastTrain(context, prefs, loc) }
                .onFailure { Log.w(TAG, "last-train check failed: ${it.message}") }
        }
    }

    // ---- 出発 ----

    private fun checkDeparture(context: Context, prefs: TravelPrefs, loc: Triple<Double, Double, Long>?) {
        if (!prefs.hasHome) return
        // すでに家を出ている（自宅から離れている）なら出発通知は不要。
        if (loc != null &&
            TravelPrefs.distanceMeters(loc.first, loc.second, prefs.homeLat, prefs.homeLon) > AWAY_METERS
        ) return

        val now = LocalDateTime.now()
        val event = firstEventToday(context, now) ?: return
        // 出発時の天気は自宅の予報で判定する。
        val forecast = WeatherClient.fetch(context, prefs.homeLat, prefs.homeLon)
        val prob = forecast?.maxPrecipProb(now, prefs.commuteMinutes.toLong() + 60) ?: 0
        val umbrella = prob >= UMBRELLA_PROB
        val departure = event.second
            .minusMinutes(prefs.commuteMinutes.toLong())
            .minusMinutes(if (umbrella) RAIN_EXTRA_MIN else 0)

        val minutesToDeparture = Duration.between(now, departure).toMinutes()
        if (minutesToDeparture > DEPARTURE_LEAD_MIN || minutesToDeparture < -10) return

        val key = "${now.toLocalDate()}/${event.second.toLocalTime()}/${event.first}"
        if (prefs.departureNotifiedKey == key) return
        prefs.departureNotifiedKey = key

        val hm = "%02d:%02d".format(departure.hour, departure.minute)
        val evHm = "%02d:%02d".format(event.second.hour, event.second.minute)
        val title = if (minutesToDeparture <= 0) "⏰ もう出発の時間です（$hm）"
            else "⏰ $hm に出発（あと${minutesToDeparture}分）"
        val body = buildString {
            append("${event.first} $evHm に間に合う出発目安です。")
            if (umbrella) append("\n☔ 降水確率${prob}%。傘を持って、少し早めに。")
        }
        notify(context, NOTIF_DEPARTURE, title, body)
        Log.i(TAG, "departure alert: $key")
    }

    /**
     * 今日のこれから始まる最初の予定 (名前, 開始時刻)。
     * 時間割の授業・Googleカレンダー予定・時刻付きの「予定」タスクから最も早いものを選ぶ。
     */
    private fun firstEventToday(context: Context, now: LocalDateTime): Pair<String, LocalDateTime>? {
        val store = AccountStore(context)
        val today = now.toLocalDate()
        val candidates = mutableListOf<Pair<String, LocalDateTime>>()

        if (store.loggedIn) {
            val client = AiHelperClient()
            // 時間割の授業。
            client.fetchCourses(store.baseUrl, store.email, store.token).getOrNull().orEmpty()
                .filter { courseOccursOn(it, today) }
                .forEach { c ->
                    parseTime(courseTime(c).substringBefore("〜"))?.let {
                        candidates += c.name to today.atTime(it)
                    }
                }
            // 時刻付きの「予定」タスク。
            client.fetchTasks(store.baseUrl, store.email, store.token, includeDone = false)
                .getOrNull().orEmpty()
                .filter { it.type == "yotei" && !it.dateOnly && it.deadline?.take(10) == today.toString() }
                .forEach { t ->
                    parseTime(t.deadline.orEmpty().replace('T', ' ').drop(11).take(5))?.let {
                        candidates += t.content to today.atTime(it)
                    }
                }
        }
        // Googleカレンダーの今日の予定（バックグラウンドでは再認可が必要な場合があり、失敗は無視）。
        runCatching {
            val googleStore = com.ishilab.transcriber.google.GoogleAccountStore(context)
            for (email in googleStore.emails) {
                val token = com.ishilab.transcriber.google.GoogleCalendarClient.accessToken(context, email)
                com.ishilab.transcriber.google.GoogleCalendarClient.listUpcomingEvents(token)
                    .getOrNull().orEmpty()
                    .filter { it.startMillis > 0 }
                    .forEach { ev ->
                        val dt = java.time.Instant.ofEpochMilli(ev.startMillis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        if (dt.toLocalDate() == today) candidates += ev.title to dt
                    }
            }
        }
        return candidates.filter { it.second.isAfter(now) }.minByOrNull { it.second }
    }

    // ---- 雨 ----

    private fun checkRain(context: Context, prefs: TravelPrefs, loc: Triple<Double, Double, Long>?) {
        // 現在地が取れなければ自宅で代用（どちらも無ければ何もできない）。
        val (lat, lon) = when {
            loc != null -> loc.first to loc.second
            prefs.hasHome -> prefs.homeLat to prefs.homeLon
            else -> return
        }
        val forecast = WeatherClient.fetch(context, lat, lon) ?: return
        val now = LocalDateTime.now()
        val startsAt = forecast.rainStartsAt(now, withinMinutes = 60)
        val stopsAt = forecast.rainStopsAt(now, withinMinutes = 90)
        val (key, title, body) = when {
            startsAt != null -> Triple(
                "start-${now.toLocalDate()}-$startsAt",
                "☔ ${startsAt}ごろから雨",
                "現在地のあたりでまもなく降り出しそうです。洗濯物・傘に注意。",
            )
            stopsAt != null -> Triple(
                "stop-${now.toLocalDate()}-$stopsAt",
                "☂ ${stopsAt}ごろに雨が止みそう",
                "移動するならそのタイミングがおすすめです。",
            )
            else -> return
        }
        if (prefs.rainNotifiedKey == key) return
        prefs.rainNotifiedKey = key
        notify(context, NOTIF_RAIN, title, body)
        Log.i(TAG, "rain alert: $key")
    }

    // ---- 終電 ----

    private fun checkLastTrain(context: Context, prefs: TravelPrefs, loc: Triple<Double, Double, Long>?) {
        val target = nextLastTrain(prefs.lastTrainTime, LocalDateTime.now()) ?: return
        // 自宅にいる（または位置が分からない）なら通知しない。
        if (!prefs.hasHome || loc == null) return
        if (TravelPrefs.distanceMeters(loc.first, loc.second, prefs.homeLat, prefs.homeLon) <= AWAY_METERS) return

        val now = LocalDateTime.now()
        val remaining = Duration.between(now, target).toMinutes()
        val stage = when {
            remaining in 0..20 -> "20"
            remaining in 21..60 -> "60"
            else -> return
        }
        val key = "${target.toLocalDate()}/${target.toLocalTime()}/$stage"
        if (prefs.lastTrainNotifiedKey == key) return
        prefs.lastTrainNotifiedKey = key

        val hm = "%02d:%02d".format(target.hour, target.minute)
        notify(
            context, NOTIF_LAST_TRAIN,
            "🚃 終電 $hm まであと${remaining}分",
            "自宅から離れた場所にいます。帰るならそろそろ駅へ。",
        )
        Log.i(TAG, "last-train alert: $key")
    }

    /**
     * 設定された終電時刻 "HH:MM" の「次の発車日時」。
     * 0:24 のような深夜時刻は日をまたいで今夜の終電として扱う。
     * 発車まで12時間より先（＝まだ夜ではない）なら null。
     */
    fun nextLastTrain(hhmm: String, now: LocalDateTime): LocalDateTime? {
        val time = parseTime(hhmm) ?: return null
        var target = now.toLocalDate().atTime(time)
        while (!target.isAfter(now)) target = target.plusDays(1)
        return if (Duration.between(now, target).toHours() >= 12) null else target
    }

    // ---- 状況カード用 ----

    /** 予定タブのカードに出す現在の状況。ブロッキングなのでワーカースレッドから呼ぶ。 */
    fun status(context: Context): Status {
        val prefs = TravelPrefs(context)
        val now = LocalDateTime.now()
        val loc = TravelPrefs.currentOrCachedLocation(context)
        val (lat, lon) = when {
            loc != null -> loc.first to loc.second
            prefs.hasHome -> prefs.homeLat to prefs.homeLon
            else -> return Status()
        }
        val forecast = WeatherClient.fetch(context, lat, lon)

        var s = Status(
            temp = forecast?.currentTemp(now),
            rainingNow = forecast?.rainingNow(now) ?: false,
            rainStartsAt = forecast?.rainStartsAt(now, withinMinutes = 120),
            rainStopsAt = forecast?.rainStopsAt(now, withinMinutes = 120),
            awayFromHome = if (loc != null && prefs.hasHome) {
                TravelPrefs.distanceMeters(loc.first, loc.second, prefs.homeLat, prefs.homeLon) > AWAY_METERS
            } else null,
        )

        if (prefs.departureEnabled) {
            firstEventToday(context, now)?.let { (title, start) ->
                val prob = forecast?.maxPrecipProb(now, prefs.commuteMinutes.toLong() + 60) ?: 0
                val umbrella = prob >= UMBRELLA_PROB
                val departure = start.minusMinutes(prefs.commuteMinutes.toLong())
                    .minusMinutes(if (umbrella) RAIN_EXTRA_MIN else 0)
                s = s.copy(
                    nextEventTitle = title,
                    nextEventStart = "%02d:%02d".format(start.hour, start.minute),
                    departureAt = "%02d:%02d".format(departure.hour, departure.minute),
                    departureInMin = Duration.between(now, departure).toMinutes(),
                    umbrella = umbrella,
                    precipProb = prob,
                )
            }
        }
        if (prefs.lastTrainEnabled) {
            nextLastTrain(prefs.lastTrainTime, now)?.let { target ->
                s = s.copy(
                    lastTrainAt = "%02d:%02d".format(target.hour, target.minute),
                    lastTrainInMin = Duration.between(now, target).toMinutes(),
                )
            }
        }
        return s
    }

    // ---- 共通 ----

    private fun parseTime(hhmm: String): LocalTime? {
        val p = hhmm.trim().split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return runCatching { LocalTime.of(h, m) }.getOrNull()
    }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
