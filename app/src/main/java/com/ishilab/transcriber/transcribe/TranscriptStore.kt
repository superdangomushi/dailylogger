package com.ishilab.transcriber.transcribe

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文字起こし結果をアプリ内ストレージに「1時間=1ファイル」で保存する。
 *
 * 例: filesDir/transcripts/2026-06-14_15.txt （15時台ぶんの文字起こし）
 * チャンク完了ごとに追記し、毎時の境界では時刻からパスが変わるため自動的に
 * 新しいファイルへ切り替わる（＝1時間に1回テキスト出力）。
 */
class TranscriptStore(filesDir: File) {

    private val dir: File = File(filesDir, "transcripts").apply { mkdirs() }

    private val fileFormat = SimpleDateFormat("yyyy-MM-dd_HH", Locale.JAPAN)
    private val lineFormat = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    /** 指定時刻が属する「時」のファイル。 */
    @Synchronized
    fun fileFor(atMillis: Long): File =
        File(dir, "${fileFormat.format(Date(atMillis))}.txt")

    /** タイムスタンプ付きで1行追記する。空テキストは無視。 */
    @Synchronized
    fun append(text: String, atMillis: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        val file = fileFor(atMillis)
        file.appendText("[${lineFormat.format(Date(atMillis))}] ${text.trim()}\n")
    }

    /** 生成済みファイルを新しい順に列挙。 */
    @Synchronized
    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    val directory: File get() = dir
}
