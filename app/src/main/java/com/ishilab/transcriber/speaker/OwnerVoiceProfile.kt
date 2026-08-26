package com.ishilab.transcriber.speaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** オーナー声紋との照合結果。音声が短すぎる場合だけ [speaker] は UNKNOWN になる。 */
data class SpeakerMatch(
    val speaker: Speaker,
    /** 登録声とのコサイン類似度。-1.0..1.0。 */
    val similarity: Float,
) {
    enum class Speaker { OWNER, OTHER, UNKNOWN }

    val label: String
        get() = when (speaker) {
            Speaker.OWNER -> "オーナー"
            Speaker.OTHER -> "他人"
            Speaker.UNKNOWN -> "話者不明"
        }
}

/**
 * 端末内に保持するオーナー声紋。
 *
 * 音声そのものではなく、複数の登録区間から抽出した正規化済み音響特徴だけを持つ。
 * 1つの平均だけにすると発話内容や一時的な声の変化に引っ張られるため、登録時の
 * 複数テンプレートとの上位類似度も照合に使う。
 */
data class OwnerVoiceProfile(
    val templates: List<FloatArray>,
    val threshold: Float,
    val createdAtMillis: Long,
) {
    private val centroid: FloatArray by lazy {
        SpeakerFeatureExtractor.normalizedMean(templates)
    }

    fun identify(samples: FloatArray): SpeakerMatch {
        val feature = SpeakerFeatureExtractor.extract(samples)
            ?: return SpeakerMatch(SpeakerMatch.Speaker.UNKNOWN, 0f)
        val templateScores = templates
            .map { SpeakerFeatureExtractor.cosine(feature, it) }
            .sortedDescending()
        val nearest = templateScores.take(min(3, templateScores.size)).average().toFloat()
        val center = SpeakerFeatureExtractor.cosine(feature, centroid)
        // 平均的な声質と、登録中の具体的な声質の両方へ近いことを要求する。
        val similarity = (center * 0.65f + nearest * 0.35f).coerceIn(-1f, 1f)
        val speaker = if (similarity >= threshold) {
            SpeakerMatch.Speaker.OWNER
        } else {
            SpeakerMatch.Speaker.OTHER
        }
        return SpeakerMatch(speaker, similarity)
    }

    companion object {
        /**
         * 12秒程度の登録音声を3秒ずつに分けて声紋を作る。
         * 十分な発話が3区間に満たない場合は null（環境音だけの誤登録を防ぐ）。
         */
        fun fromEnrollment(samples: FloatArray): OwnerVoiceProfile? {
            val window = SAMPLE_RATE * ENROLLMENT_WINDOW_SECONDS
            val templates = ArrayList<FloatArray>()
            var offset = 0
            while (offset + window <= samples.size) {
                val feature = SpeakerFeatureExtractor.extract(samples.copyOfRange(offset, offset + window))
                if (feature != null) templates += feature
                offset += window
            }
            if (templates.size < MIN_ENROLLMENT_TEMPLATES) return null

            // 登録区間同士のばらつきから、その人の当日の声・マイク環境に合わせてしきい値を調整。
            val leaveOneOut = templates.indices.map { index ->
                val others = templates.filterIndexed { i, _ -> i != index }
                SpeakerFeatureExtractor.cosine(
                    templates[index],
                    SpeakerFeatureExtractor.normalizedMean(others),
                )
            }
            val intraFloor = leaveOneOut.minOrNull() ?: 0.9f
            // 誤って他人をオーナー扱いするより、条件が悪いときに再登録を促せる側へ寄せる。
            val threshold = (intraFloor - 0.03f).coerceIn(0.94f, 0.965f)
            return OwnerVoiceProfile(templates, threshold, System.currentTimeMillis())
        }

        const val SAMPLE_RATE = 16_000
        const val ENROLLMENT_SECONDS = 12
        private const val ENROLLMENT_WINDOW_SECONDS = 3
        private const val MIN_ENROLLMENT_TEMPLATES = 3
    }
}

/**
 * 外部モデルを必要としない軽量な声紋特徴抽出。
 * 25msフレームの MFCC（平均・分散）とスペクトル形状を使い、発話内容より声道の傾向を
 * 比較する。Whisper の前処理と同じ 16kHz/mono の PCM をそのまま受け取れる。
 */
object SpeakerFeatureExtractor {
    private const val SAMPLE_RATE = OwnerVoiceProfile.SAMPLE_RATE
    private const val FRAME_SIZE = 400       // 25ms
    private const val HOP_SIZE = 160         // 10ms
    private const val FFT_SIZE = 512
    private const val MEL_FILTERS = 24
    private const val MFCC_COUNT = 13
    private const val MAX_ANALYZED_FRAMES = 180
    private const val MIN_VOICED_FRAMES = 24
    private const val MIN_FRAME_RMS = 0.008f
    const val FEATURE_DIMENSION = MFCC_COUNT * 2 + 8

    private val hamming = FloatArray(FRAME_SIZE) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
    }
    private val melBins: IntArray = buildMelBins()

    fun extract(samples: FloatArray): FloatArray? {
        if (samples.size < FRAME_SIZE) return null
        val totalFrames = 1 + (samples.size - FRAME_SIZE) / HOP_SIZE
        val frameStride = max(1, (totalFrames + MAX_ANALYZED_FRAMES - 1) / MAX_ANALYZED_FRAMES)
        val cepSum = DoubleArray(MFCC_COUNT)
        val cepSq = DoubleArray(MFCC_COUNT)
        var centroidSum = 0.0
        var centroidSq = 0.0
        var zcrSum = 0.0
        var zcrSq = 0.0
        var flatnessSum = 0.0
        var logPitchSum = 0.0
        var logPitchSq = 0.0
        var pitchStrengthSum = 0.0
        var pitchUsed = 0
        var used = 0

        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2 + 1)
        var frameIndex = 0
        while (frameIndex < totalFrames) {
            val start = frameIndex * HOP_SIZE
            var energy = 0.0
            for (i in 0 until FRAME_SIZE) {
                val s = samples[start + i].toDouble()
                energy += s * s
            }
            val rms = sqrt(energy / FRAME_SIZE)
            if (rms >= MIN_FRAME_RMS) {
                real.fill(0.0)
                imag.fill(0.0)
                var previous = 0.0
                var zeroCrossings = 0
                for (i in 0 until FRAME_SIZE) {
                    val current = samples[start + i].toDouble()
                    if (i > 0 && (current >= 0.0) != (previous >= 0.0)) zeroCrossings++
                    // プリエンファシスで声道の高域成分を比較しやすくする。
                    real[i] = (current - 0.97 * previous) * hamming[i]
                    previous = current
                }
                fft(real, imag)

                var powerSum = 0.0
                var weightedFreq = 0.0
                var logPowerSum = 0.0
                for (bin in power.indices) {
                    val p = real[bin] * real[bin] + imag[bin] * imag[bin] + 1e-12
                    power[bin] = p
                    powerSum += p
                    weightedFreq += p * bin
                    logPowerSum += ln(p)
                }

                val logMel = DoubleArray(MEL_FILTERS)
                for (m in 0 until MEL_FILTERS) {
                    val left = melBins[m]
                    val center = max(left + 1, melBins[m + 1])
                    val right = max(center + 1, melBins[m + 2])
                    var sum = 0.0
                    for (bin in left until min(center, power.size)) {
                        sum += power[bin] * (bin - left).toDouble() / (center - left)
                    }
                    for (bin in center until min(right, power.size)) {
                        sum += power[bin] * (right - bin).toDouble() / (right - center)
                    }
                    logMel[m] = ln(sum + 1e-10)
                }

                // c0 は音量に強く依存するため除外し、c1..c13 を使う。
                for (k in 1..MFCC_COUNT) {
                    var coefficient = 0.0
                    for (m in 0 until MEL_FILTERS) {
                        coefficient += logMel[m] * cos(PI * k * (m + 0.5) / MEL_FILTERS)
                    }
                    val idx = k - 1
                    cepSum[idx] += coefficient
                    cepSq[idx] += coefficient * coefficient
                }

                val centroid = if (powerSum > 0.0) {
                    weightedFreq / powerSum / (FFT_SIZE / 2)
                } else 0.0
                val zcr = zeroCrossings.toDouble() / FRAME_SIZE
                val arithmeticMean = powerSum / power.size
                val flatness = if (arithmeticMean > 0.0) {
                    kotlin.math.exp(logPowerSum / power.size) / arithmeticMean
                } else 0.0
                // 基本周波数は声帯由来の強い話者手掛かり。負荷を抑えるため3フレームに1回、
                // 8kHz相当へ間引いた自己相関で推定する。
                if (used % 3 == 0) {
                    estimatePitch(samples, start)?.let { (pitchHz, strength) ->
                        val logPitch = ln(pitchHz / 160.0)
                        logPitchSum += logPitch
                        logPitchSq += logPitch * logPitch
                        pitchStrengthSum += strength
                        pitchUsed++
                    }
                }
                centroidSum += centroid
                centroidSq += centroid * centroid
                zcrSum += zcr
                zcrSq += zcr * zcr
                flatnessSum += flatness
                used++
            }
            frameIndex += frameStride
        }
        if (used < MIN_VOICED_FRAMES) return null

        val features = FloatArray(FEATURE_DIMENSION)
        for (i in 0 until MFCC_COUNT) {
            val mean = cepSum[i] / used
            val variance = max(0.0, cepSq[i] / used - mean * mean)
            // 固定スケールで成分間の桁を揃えたあと、最後にL2正規化する。
            features[i] = (mean / 20.0).toFloat()
            features[MFCC_COUNT + i] = (sqrt(variance) / 10.0).toFloat()
        }
        val centroidMean = centroidSum / used
        val zcrMean = zcrSum / used
        features[MFCC_COUNT * 2] = (centroidMean * 3.0).toFloat()
        features[MFCC_COUNT * 2 + 1] = (sqrt(max(0.0, centroidSq / used - centroidMean * centroidMean)) * 6.0).toFloat()
        features[MFCC_COUNT * 2 + 2] = (zcrMean * 4.0).toFloat()
        features[MFCC_COUNT * 2 + 3] = (sqrt(max(0.0, zcrSq / used - zcrMean * zcrMean)) * 8.0).toFloat()
        features[MFCC_COUNT * 2 + 4] = (flatnessSum / used * 4.0).toFloat()
        if (pitchUsed > 0) {
            val pitchMean = logPitchSum / pitchUsed
            features[MFCC_COUNT * 2 + 5] = (pitchMean * 2.0).toFloat()
            features[MFCC_COUNT * 2 + 6] = (
                sqrt(max(0.0, logPitchSq / pitchUsed - pitchMean * pitchMean)) * 3.0
            ).toFloat()
            features[MFCC_COUNT * 2 + 7] = (pitchStrengthSum / pitchUsed * 1.5).toFloat()
        }
        return normalize(features)
    }

    /** 25msフレームを2:1で間引き、80〜350Hzの正規化自己相関が最大の基本周波数を返す。 */
    private fun estimatePitch(samples: FloatArray, start: Int): Pair<Double, Double>? {
        val count = FRAME_SIZE / 2
        val downsampled = DoubleArray(count)
        var mean = 0.0
        for (i in 0 until count) {
            val value = samples[start + i * 2].toDouble()
            downsampled[i] = value
            mean += value
        }
        mean /= count
        for (i in downsampled.indices) downsampled[i] -= mean

        val pitchSampleRate = SAMPLE_RATE / 2
        val minLag = pitchSampleRate / 350
        val maxLag = pitchSampleRate / 80
        var bestLag = 0
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for (i in 0 until count - lag) {
                val a = downsampled[i]
                val b = downsampled[i + lag]
                correlation += a * b
                energyA += a * a
                energyB += b * b
            }
            val denominator = sqrt(energyA * energyB)
            val score = if (denominator > 1e-12) correlation / denominator else 0.0
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore < 0.30) return null
        return (pitchSampleRate.toDouble() / bestLag) to bestScore
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa <= 0.0 || bb <= 0.0) return -1f
        return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }

    fun normalizedMean(items: List<FloatArray>): FloatArray {
        if (items.isEmpty()) return FloatArray(0)
        val out = FloatArray(items.first().size)
        for (item in items) {
            if (item.size != out.size) continue
            for (i in item.indices) out[i] += item[i]
        }
        return normalize(out)
    }

    private fun normalize(values: FloatArray): FloatArray {
        var norm = 0.0
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm <= 1e-12) return values
        for (i in values.indices) values[i] = (values[i] / norm).toFloat()
        return values
    }

    private fun buildMelBins(): IntArray {
        fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(7_600.0)
        return IntArray(MEL_FILTERS + 2) { i ->
            val mel = lowMel + (highMel - lowMel) * i / (MEL_FILTERS + 1)
            ((FFT_SIZE + 1) * melToHz(mel) / SAMPLE_RATE)
                .toInt().coerceIn(0, FFT_SIZE / 2)
        }
    }

    /** in-place radix-2 FFT。 */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val even = i + k
                    val odd = even + len / 2
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    val ur = real[even]
                    val ui = imag[even]
                    real[even] = ur + vr
                    imag[even] = ui + vi
                    real[odd] = ur - vr
                    imag[odd] = ui - vi
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** 声紋を自動バックアップ対象外の端末領域へ保存する。登録音声は保存しない。 */
class OwnerVoiceProfileStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun load(): OwnerVoiceProfile? = runCatching {
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        if (json.optInt("version") != VERSION) return null
        val arrays = json.getJSONArray("templates")
        val templates = ArrayList<FloatArray>(arrays.length())
        for (i in 0 until arrays.length()) {
            val values = arrays.getJSONArray(i)
            templates += FloatArray(values.length()) { j -> values.getDouble(j).toFloat() }
        }
        if (templates.size < 3 || templates.any { it.size != SpeakerFeatureExtractor.FEATURE_DIMENSION }) {
            return null
        }
        val threshold = json.getDouble("threshold").toFloat()
        if (threshold !in 0f..1f) return null
        OwnerVoiceProfile(
            templates = templates,
            threshold = threshold,
            createdAtMillis = json.optLong("createdAtMillis"),
        )
    }.getOrNull()

    @Synchronized
    fun save(profile: OwnerVoiceProfile) {
        val json = JSONObject()
            .put("version", VERSION)
            .put("threshold", profile.threshold.toDouble())
            .put("createdAtMillis", profile.createdAtMillis)
        val templates = JSONArray()
        for (template in profile.templates) {
            val values = JSONArray()
            for (value in template) values.put(value.toDouble())
            templates.put(values)
        }
        json.put("templates", templates)

        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
    }

    fun exists(): Boolean = load() != null

    private companion object {
        const val VERSION = 2
        const val FILE_NAME = "owner-voice-profile.json"
    }
}
