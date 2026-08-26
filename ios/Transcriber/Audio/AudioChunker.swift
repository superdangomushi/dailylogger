import Foundation

/// 録音サンプルまわりの共有定数と簡易 VAD。
/// （Android 版 AudioChunker.kt の移植。iOS 版はチャンク蓄積を PcmSegment 側で行うため
/// 定数と isSilent のみ使用する。）
enum AudioChunker {
    static let sampleRate = 16_000
    static let chunkSeconds = 30

    /// 簡易VAD: チャンクの RMS がしきい値未満なら「ほぼ無音」とみなす。
    /// 無音チャンクは文字起こしせず破棄し、CPU/バッテリーを節約する。
    static func isSilent(_ samples: [Float], threshold: Float = 0.012) -> Bool {
        if samples.isEmpty { return true }
        var sumSq = 0.0
        for s in samples { sumSq += Double(s * s) }
        let rms = (sumSq / Double(samples.count)).squareRoot()
        return rms < Double(threshold)
    }
}

enum IdentifiedSpeaker: String, Codable {
    case owner
    case other
    case unknown

    var label: String {
        switch self {
        case .owner: return "オーナー"
        case .other: return "他人"
        case .unknown: return "話者不明"
        }
    }
}

struct SpeakerMatch {
    let speaker: IdentifiedSpeaker
    /// 登録声とのコサイン類似度（-1...1）。
    let similarity: Float
}

/// 登録音声そのものではなく、複数の登録区間から抽出した声紋特徴だけを保持する。
struct OwnerVoiceProfile: Codable {
    let templates: [[Float]]
    let threshold: Float
    let createdAtMillis: Int64

    func identify(_ samples: [Float]) -> SpeakerMatch {
        guard let feature = SpeakerFeatureExtractor.extract(samples) else {
            return SpeakerMatch(speaker: .unknown, similarity: 0)
        }
        let center = SpeakerFeatureExtractor.cosine(
            feature, SpeakerFeatureExtractor.normalizedMean(templates))
        let scores = templates.map { SpeakerFeatureExtractor.cosine(feature, $0) }.sorted(by: >)
        let nearestValues = scores.prefix(min(3, scores.count))
        let nearest = nearestValues.isEmpty ? -1 : nearestValues.reduce(0, +) / Float(nearestValues.count)
        let similarity = min(1, max(-1, center * 0.65 + nearest * 0.35))
        return SpeakerMatch(speaker: similarity >= threshold ? .owner : .other,
                            similarity: similarity)
    }

    /// 12秒の登録音声を3秒ずつに分け、十分な発話が3区間以上あれば声紋を作る。
    static func fromEnrollment(_ samples: [Float]) -> OwnerVoiceProfile? {
        let window = AudioChunker.sampleRate * enrollmentWindowSeconds
        var templates: [[Float]] = []
        var offset = 0
        while offset + window <= samples.count {
            if let feature = SpeakerFeatureExtractor.extract(Array(samples[offset..<(offset + window)])) {
                templates.append(feature)
            }
            offset += window
        }
        guard templates.count >= 3 else { return nil }
        let leaveOneOut: [Float] = templates.indices.map { index in
            let others = templates.enumerated().filter { $0.offset != index }.map { $0.element }
            return SpeakerFeatureExtractor.cosine(
                templates[index], SpeakerFeatureExtractor.normalizedMean(others))
        }
        let intraFloor = leaveOneOut.min() ?? 0.9
        // 誤って他人をオーナー扱いするより、条件が悪いときに再登録を促せる側へ寄せる。
        let threshold = min(0.965, max(0.94, intraFloor - 0.03))
        return OwnerVoiceProfile(
            templates: templates,
            threshold: threshold,
            createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000))
    }

    static let enrollmentSeconds = 12
    private static let enrollmentWindowSeconds = 3
}

/// MFCC（平均・分散）とスペクトル形状を使う、外部モデル不要の軽量声紋抽出。
enum SpeakerFeatureExtractor {
    private static let frameSize = 400       // 25ms
    private static let hopSize = 160         // 10ms
    private static let fftSize = 512
    private static let melFilters = 24
    private static let mfccCount = 13
    private static let maxAnalyzedFrames = 180
    private static let minVoicedFrames = 24
    private static let minFrameRms: Float = 0.008
    static let featureDimension = mfccCount * 2 + 8

    private static let hamming: [Float] = (0..<frameSize).map { i in
        Float(0.54 - 0.46 * cos(2 * Double.pi * Double(i) / Double(frameSize - 1)))
    }
    private static let melBins: [Int] = buildMelBins()

    static func extract(_ samples: [Float]) -> [Float]? {
        guard samples.count >= frameSize else { return nil }
        let totalFrames = 1 + (samples.count - frameSize) / hopSize
        let frameStride = max(1, (totalFrames + maxAnalyzedFrames - 1) / maxAnalyzedFrames)
        var cepSum = [Double](repeating: 0, count: mfccCount)
        var cepSq = [Double](repeating: 0, count: mfccCount)
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

        var real = [Double](repeating: 0, count: fftSize)
        var imag = [Double](repeating: 0, count: fftSize)
        var power = [Double](repeating: 0, count: fftSize / 2 + 1)
        var frameIndex = 0
        while frameIndex < totalFrames {
            let start = frameIndex * hopSize
            var energy = 0.0
            for i in 0..<frameSize {
                let sample = Double(samples[start + i])
                energy += sample * sample
            }
            let rms = sqrt(energy / Double(frameSize))
            if rms >= Double(minFrameRms) {
                real = [Double](repeating: 0, count: fftSize)
                imag = [Double](repeating: 0, count: fftSize)
                var previous = 0.0
                var zeroCrossings = 0
                for i in 0..<frameSize {
                    let current = Double(samples[start + i])
                    if i > 0 && (current >= 0) != (previous >= 0) { zeroCrossings += 1 }
                    real[i] = (current - 0.97 * previous) * Double(hamming[i])
                    previous = current
                }
                fft(real: &real, imag: &imag)

                var powerSum = 0.0
                var weightedFreq = 0.0
                var logPowerSum = 0.0
                for bin in power.indices {
                    let value = real[bin] * real[bin] + imag[bin] * imag[bin] + 1e-12
                    power[bin] = value
                    powerSum += value
                    weightedFreq += value * Double(bin)
                    logPowerSum += log(value)
                }

                var logMel = [Double](repeating: 0, count: melFilters)
                for m in 0..<melFilters {
                    let left = melBins[m]
                    let center = max(left + 1, melBins[m + 1])
                    let right = max(center + 1, melBins[m + 2])
                    var sum = 0.0
                    if left < min(center, power.count) {
                        for bin in left..<min(center, power.count) {
                            sum += power[bin] * Double(bin - left) / Double(center - left)
                        }
                    }
                    if center < min(right, power.count) {
                        for bin in center..<min(right, power.count) {
                            sum += power[bin] * Double(right - bin) / Double(right - center)
                        }
                    }
                    logMel[m] = log(sum + 1e-10)
                }

                // c0 は音量依存が強いため除外する。
                for k in 1...mfccCount {
                    var coefficient = 0.0
                    for m in 0..<melFilters {
                        coefficient += logMel[m] * cos(
                            Double.pi * Double(k) * (Double(m) + 0.5) / Double(melFilters))
                    }
                    let index = k - 1
                    cepSum[index] += coefficient
                    cepSq[index] += coefficient * coefficient
                }

                let centroid = powerSum > 0
                    ? weightedFreq / powerSum / Double(fftSize / 2) : 0
                let zcr = Double(zeroCrossings) / Double(frameSize)
                let arithmeticMean = powerSum / Double(power.count)
                let flatness = arithmeticMean > 0
                    ? exp(logPowerSum / Double(power.count)) / arithmeticMean : 0
                // 基本周波数は声帯由来の強い話者手掛かり。3フレームに1回だけ推定する。
                if used % 3 == 0,
                   let pitch = estimatePitch(samples, start: start) {
                    let logPitch = log(pitch.hz / 160)
                    logPitchSum += logPitch
                    logPitchSq += logPitch * logPitch
                    pitchStrengthSum += pitch.strength
                    pitchUsed += 1
                }
                centroidSum += centroid
                centroidSq += centroid * centroid
                zcrSum += zcr
                zcrSq += zcr * zcr
                flatnessSum += flatness
                used += 1
            }
            frameIndex += frameStride
        }
        guard used >= minVoicedFrames else { return nil }

        var features = [Float](repeating: 0, count: featureDimension)
        for i in 0..<mfccCount {
            let mean = cepSum[i] / Double(used)
            let variance = max(0, cepSq[i] / Double(used) - mean * mean)
            features[i] = Float(mean / 20)
            features[mfccCount + i] = Float(sqrt(variance) / 10)
        }
        let centroidMean = centroidSum / Double(used)
        let zcrMean = zcrSum / Double(used)
        features[mfccCount * 2] = Float(centroidMean * 3)
        features[mfccCount * 2 + 1] = Float(sqrt(max(0, centroidSq / Double(used) - centroidMean * centroidMean)) * 6)
        features[mfccCount * 2 + 2] = Float(zcrMean * 4)
        features[mfccCount * 2 + 3] = Float(sqrt(max(0, zcrSq / Double(used) - zcrMean * zcrMean)) * 8)
        features[mfccCount * 2 + 4] = Float(flatnessSum / Double(used) * 4)
        if pitchUsed > 0 {
            let pitchMean = logPitchSum / Double(pitchUsed)
            features[mfccCount * 2 + 5] = Float(pitchMean * 2)
            features[mfccCount * 2 + 6] = Float(
                sqrt(max(0, logPitchSq / Double(pitchUsed) - pitchMean * pitchMean)) * 3)
            features[mfccCount * 2 + 7] = Float(pitchStrengthSum / Double(pitchUsed) * 1.5)
        }
        return normalize(features)
    }

    /// 25msフレームを2:1で間引き、80〜350Hzの正規化自己相関が最大の基本周波数を返す。
    private static func estimatePitch(_ samples: [Float], start: Int) -> (hz: Double, strength: Double)? {
        let count = frameSize / 2
        var downsampled = [Double](repeating: 0, count: count)
        var mean = 0.0
        for i in 0..<count {
            let value = Double(samples[start + i * 2])
            downsampled[i] = value
            mean += value
        }
        mean /= Double(count)
        for i in downsampled.indices { downsampled[i] -= mean }

        let pitchSampleRate = AudioChunker.sampleRate / 2
        let minLag = pitchSampleRate / 350
        let maxLag = pitchSampleRate / 80
        var bestLag = 0
        var bestScore = 0.0
        for lag in minLag...maxLag {
            var correlation = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for i in 0..<(count - lag) {
                let a = downsampled[i]
                let b = downsampled[i + lag]
                correlation += a * b
                energyA += a * a
                energyB += b * b
            }
            let denominator = sqrt(energyA * energyB)
            let score = denominator > 1e-12 ? correlation / denominator : 0
            if score > bestScore {
                bestScore = score
                bestLag = lag
            }
        }
        guard bestLag > 0, bestScore >= 0.30 else { return nil }
        return (Double(pitchSampleRate) / Double(bestLag), bestScore)
    }

    static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        guard !a.isEmpty, a.count == b.count else { return -1 }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for i in a.indices {
            dot += Double(a[i] * b[i])
            aa += Double(a[i] * a[i])
            bb += Double(b[i] * b[i])
        }
        guard aa > 0, bb > 0 else { return -1 }
        return Float(dot / sqrt(aa * bb))
    }

    static func normalizedMean(_ items: [[Float]]) -> [Float] {
        guard let first = items.first else { return [] }
        var output = [Float](repeating: 0, count: first.count)
        for item in items where item.count == output.count {
            for i in item.indices { output[i] += item[i] }
        }
        return normalize(output)
    }

    private static func normalize(_ input: [Float]) -> [Float] {
        var values = input
        let norm = sqrt(values.reduce(0) { $0 + Double($1 * $1) })
        guard norm > 1e-12 else { return values }
        for i in values.indices { values[i] = Float(Double(values[i]) / norm) }
        return values
    }

    private static func buildMelBins() -> [Int] {
        func hzToMel(_ hz: Double) -> Double { 2595 * log10(1 + hz / 700) }
        func melToHz(_ mel: Double) -> Double { 700 * (pow(10, mel / 2595) - 1) }
        let low = hzToMel(80)
        let high = hzToMel(7_600)
        return (0..<(melFilters + 2)).map { i in
            let mel = low + (high - low) * Double(i) / Double(melFilters + 1)
            return min(fftSize / 2, max(0,
                Int(Double(fftSize + 1) * melToHz(mel) / Double(AudioChunker.sampleRate))))
        }
    }

    /// in-place radix-2 FFT。
    private static func fft(real: inout [Double], imag: inout [Double]) {
        let count = real.count
        var j = 0
        if count > 1 {
            for i in 1..<count {
                var bit = count >> 1
                while j & bit != 0 {
                    j ^= bit
                    bit >>= 1
                }
                j ^= bit
                if i < j {
                    real.swapAt(i, j)
                    imag.swapAt(i, j)
                }
            }
        }
        var length = 2
        while length <= count {
            let angle = -2 * Double.pi / Double(length)
            let wLenR = cos(angle)
            let wLenI = sin(angle)
            var start = 0
            while start < count {
                var wr = 1.0
                var wi = 0.0
                for k in 0..<(length / 2) {
                    let even = start + k
                    let odd = even + length / 2
                    let vr = real[odd] * wr - imag[odd] * wi
                    let vi = real[odd] * wi + imag[odd] * wr
                    let ur = real[even]
                    let ui = imag[even]
                    real[even] = ur + vr
                    imag[even] = ui + vi
                    real[odd] = ur - vr
                    imag[odd] = ui - vi
                    let nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += length
            }
            length <<= 1
        }
    }
}

/// 声紋を iCloud / iTunes バックアップ対象外の端末領域へ保存する。登録音声は保存しない。
final class OwnerVoiceProfileStore {
    private let file: URL

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("OwnerVoice", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        file = directory.appendingPathComponent("owner-voice-profile.json")
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try? mutableDirectory.setResourceValues(values)
    }

    func load() -> OwnerVoiceProfile? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        guard let profile = try? JSONDecoder().decode(OwnerVoiceProfile.self, from: data),
              profile.templates.count >= 3,
              profile.templates.allSatisfy({ $0.count == SpeakerFeatureExtractor.featureDimension }),
              profile.threshold >= 0, profile.threshold <= 1 else {
            return nil
        }
        return profile
    }

    func save(_ profile: OwnerVoiceProfile) throws {
        let data = try JSONEncoder().encode(profile)
        try data.write(to: file, options: .atomic)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableFile = file
        try? mutableFile.setResourceValues(values)
    }

    func delete() { try? FileManager.default.removeItem(at: file) }
}
