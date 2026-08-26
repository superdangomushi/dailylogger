package com.ishilab.transcriber.speaker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OwnerVoiceProfileTest {

    @Test
    fun silenceCannotBeRegisteredOrIdentified() {
        val silence = FloatArray(OwnerVoiceProfile.SAMPLE_RATE * OwnerVoiceProfile.ENROLLMENT_SECONDS)
        assertEquals(null, OwnerVoiceProfile.fromEnrollment(silence))
        assertEquals(null, SpeakerFeatureExtractor.extract(silence))
    }

    @Test
    fun registeredVoiceMatchesAndDifferentTimbreDoesNot() {
        val enrollment = syntheticVoice(
            seconds = OwnerVoiceProfile.ENROLLMENT_SECONDS,
            basePitch = 118.0,
            formants = doubleArrayOf(650.0, 1_180.0, 2_450.0),
        )
        val profile = assertNotNull(OwnerVoiceProfile.fromEnrollment(enrollment))

        val sameSpeaker = syntheticVoice(
            seconds = 10,
            basePitch = 126.0,
            formants = doubleArrayOf(650.0, 1_180.0, 2_450.0),
        )
        val otherSpeaker = syntheticVoice(
            seconds = 10,
            basePitch = 205.0,
            formants = doubleArrayOf(390.0, 2_050.0, 3_250.0),
        )

        assertEquals(SpeakerMatch.Speaker.OWNER, profile.identify(sameSpeaker).speaker)
        assertEquals(SpeakerMatch.Speaker.OTHER, profile.identify(otherSpeaker).speaker)
    }

    /** 声帯の倍音を3つのフォルマント包絡で重み付けした、テスト用の疑似音声。 */
    private fun syntheticVoice(seconds: Int, basePitch: Double, formants: DoubleArray): FloatArray {
        val sampleRate = OwnerVoiceProfile.SAMPLE_RATE
        return FloatArray(sampleRate * seconds) { i ->
            val time = i.toDouble() / sampleRate
            // 区間ごとに基本周波数を少し変え、同じ音が続くだけの不自然なテンプレートを避ける。
            val pitch = basePitch * (1.0 + 0.035 * sin(2.0 * PI * time / 1.7))
            var value = 0.0
            for (harmonic in 1..28) {
                val frequency = pitch * harmonic
                if (frequency >= sampleRate / 2) break
                val envelope = formants.sumOf { formant ->
                    val distance = (frequency - formant) / 180.0
                    kotlin.math.exp(-0.5 * distance * distance)
                }
                value += sin(2.0 * PI * frequency * time) * envelope / harmonic
            }
            // 50msごとに振幅を変えて、有声音フレームの統計も現実の音声に近づける。
            val amplitude = 0.18 + 0.08 * abs(sin(2.0 * PI * time * 3.1))
            (value * amplitude).toFloat().coerceIn(-0.9f, 0.9f)
        }
    }
}
