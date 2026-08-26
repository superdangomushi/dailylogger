import SwiftUI
import AVFoundation

/// 録音・文字起こし関連（状態 / 操作 / モデル）をまとめたタブ。
struct RecordingTabView: View {
    @EnvironmentObject var viewModel: MainViewModel
    @EnvironmentObject var service: AudioCaptureService

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                StatusCard(state: service.state)

                TranscribeModeCard()

                OwnerVoiceCard()

                SegmentIntervalCard()

                if !viewModel.ui.serverTranscribe {
                    ModelCard()
                }

                if viewModel.ui.anyModelReady || viewModel.ui.serverTranscribe {
                    ControlRow()
                }

                if let err = service.state.error {
                    Text("エラー: \(err)")
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let msg = viewModel.ui.sendMessage {
                    Text(msg)
                        .font(.caption)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
        }
    }
}

/// 状態カード。録音時間・処理中区間・直近テキストなどを表示する。
struct StatusCard: View {
    let state: ServiceState
    @State private var now = AudioCaptureService.nowElapsedMs()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        CardView {
            let status: String = {
                if state.draining { return "送信待ち（未送信を送信中）" }
                if state.transcribing { return "音声を文字起こし中" }
                if !state.active { return "停止中" }
                if state.paused { return "一時停止中（マイク解放中）" }
                return "録音中"
            }()
            Text("状態: \(status)").font(.headline)

            if state.active && !state.transcribing {
                Text("録音時間: \(formatDuration(elapsedMs))")
                let intervalLabel = segmentIntervalLabel()
                Text("※ 文字起こしは\(intervalLabel)ごと、または終了時にまとめて実行します。")
                    .font(.caption)
            }
            // 現在どの区間を処理しているかと進捗。
            if state.transcribing {
                Text("処理中の音声: \(state.transcribeLabel ?? "-")")
                ProgressView(value: Double(state.transcribeProgress))
                Text("\(Int(state.transcribeProgress * 100))%").font(.caption)
            }
            if let model = state.modelName {
                Text("モデル: \(model)")
            }
            Text("処理済: \(state.chunksDone) 区間  待機: \(state.queueSize) 区間")
            if let file = state.currentFile {
                Text("最新の出力: \(file)")
            }
            if !state.lastText.isEmpty {
                Text("直近: \(state.lastText)")
                    .font(.caption)
                    .lineLimit(2)
            }
        }
        .onReceive(timer) { _ in
            if state.active { now = AudioCaptureService.nowElapsedMs() }
        }
    }

    /// 録音の合計継続時間(ms)。一時停止中は積算値で止まる。
    private var elapsedMs: Int64 {
        let running = state.recordingStartedElapsed > 0
            ? max(0, now - state.recordingStartedElapsed)
            : 0
        return state.accumulatedRecordMs + running
    }

    private func segmentIntervalLabel() -> String {
        let min = AccountStore().segmentIntervalMinutes
        if min >= 60 && min % 60 == 0 { return "\(min / 60)時間" }
        return "\(min)分"
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSec = ms / 1000
        let h = totalSec / 3600
        let m = (totalSec % 3600) / 60
        let s = totalSec % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

/// オーナーの登録音声をPCクライアントへ渡すためのカード。
struct OwnerVoiceCard: View {
    @EnvironmentObject var viewModel: MainViewModel
    @EnvironmentObject var service: AudioCaptureService
    @ObservedObject private var enrollment = OwnerVoiceEnrollmentController.shared

    var body: some View {
        CardView {
            Text("オーナーの声").font(.headline)
            Text(statusText)
                .foregroundColor(viewModel.ui.ownerVoiceRegistered ? AppTheme.primary : .primary)
            Text("iPhoneでは12秒間の声を録音して送るだけです。PCクライアントが声紋作成と［オーナー］／［他人］の判定を行います。")
                .font(.caption)
            if !viewModel.ui.account.loggedIn {
                Text("利用するには先にAIHelperへログインしてください。")
                    .font(.caption)
            } else if !viewModel.ui.serverTranscribe {
                Text("※ 話者ラベルを付けるには「PCクライアントで処理」を選び、PCクライアントを起動してください。")
                    .font(.caption).foregroundColor(AppTheme.tertiary)
            }

            if enrollment.isRecording {
                Text("普段の声で続けて読んでください：今日は予定を確認して、必要な連絡と買い物を済ませます。")
                    .font(.caption)
                ProgressView(value: Double(enrollment.progress))
                Text("録音中 \(Int(enrollment.progress * Float(OwnerVoiceEnrollmentController.enrollmentSeconds))) / \(OwnerVoiceEnrollmentController.enrollmentSeconds)秒")
                    .font(.caption)
                Button("中止") { enrollment.cancel() }.buttonStyle(.bordered)
            } else if viewModel.ui.ownerVoiceUploading {
                ProgressView()
                Text("登録音声を送信中…").font(.caption)
            } else {
                HStack(spacing: 8) {
                    Button(viewModel.ui.ownerVoiceRegistered ? "声を再登録" : "声を登録") {
                        enrollment.start { samples in viewModel.enrollOwnerVoice(samples) }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.ui.account.loggedIn || service.state.active || service.state.draining)
                    if viewModel.ui.ownerVoiceRegistered {
                        Button("削除") { viewModel.deleteOwnerVoice() }
                            .buttonStyle(.bordered)
                            .disabled(service.state.active || service.state.draining)
                    }
                }
                if service.state.active || service.state.draining {
                    Text("声の登録・削除は通常の録音を終了してから行えます。").font(.caption)
                }
            }
            if let message = enrollment.message ?? viewModel.ui.ownerVoiceMessage {
                Text(message).font(.caption)
            }
        }
    }

    private var statusText: String {
        switch viewModel.ui.ownerVoiceStatus {
        case "queued": return "登録音声の処理待ち"
        case "processing": return "PCクライアントで声紋を作成中"
        case "error": return viewModel.ui.ownerVoiceRegistered ? "再登録失敗（以前の声紋は有効）" : "登録失敗"
        case "ready": return "登録済み（話者識別 ON）"
        default: return "未登録"
        }
    }
}

/// 文字起こし方法の選択カード。
/// 端末処理(Whisper)は遅い端末だと時間がかかるため、音声をサーバーへアップロードして
/// 音声をサーバー経由でPCクライアントへ渡して処理するモード。
struct TranscribeModeCard: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        CardView {
            Text("文字起こしの方法").font(.headline)
            RadioRow(selected: !viewModel.ui.serverTranscribe, enabled: true) {
                viewModel.setServerTranscribe(false)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("端末で処理（オフライン）")
                    Text("Whisper モデルで端末内処理。通信不要だが時間がかかる。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            RadioRow(selected: viewModel.ui.serverTranscribe, enabled: viewModel.ui.account.loggedIn) {
                viewModel.setServerTranscribe(true)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("PCクライアントで処理（音声をアップロード）")
                    Text(viewModel.ui.account.loggedIn
                         ? "録音音声をサーバー経由でPCへ渡し、PCクライアントが文字起こしと話者識別を行います。"
                         : "利用するには先に「AI」タブで AIHelper にログインしてください。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            Text("※ 切り替えは次回の録音開始から反映されます。")
                .font(.caption)
        }
    }
}

/// Android の RadioButton＋ラベル行の代替。
struct RadioRow<Label: View>: View {
    let selected: Bool
    let enabled: Bool
    let action: () -> Void
    @ViewBuilder let label: Label

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundColor(selected ? AppTheme.primary : .secondary)
                label
                    .foregroundColor(.primary)
                Spacer(minLength: 0)
            }
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .buttonStyle(.plain)
    }
}

/// 文字起こしモデルのカード。ダウンロード済みモデルはラジオで選び直せ、
/// 未ダウンロードのモデルはこの場でダウンロードできる。
struct ModelCard: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        CardView {
            Text("文字起こしモデル").font(.headline)
            if !viewModel.ui.anyModelReady {
                Text("初回はモデルのダウンロードが必要です。DL後はオフラインで動作。日本語は base 以上を推奨。")
                    .font(.caption)
            }
            ForEach(WhisperModel.allCases) { model in
                let downloaded = viewModel.ui.downloadedModels.contains(model)
                let selected = viewModel.ui.selectedModel == model
                let isDownloading = viewModel.ui.downloading == model
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.displayName)
                        Text("約\(model.approxMb)MB").font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    if isDownloading {
                        ProgressView().scaleEffect(0.8)
                    } else if downloaded {
                        HStack(spacing: 4) {
                            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(selected ? AppTheme.primary : .secondary)
                            Text(selected ? "使用中" : "使用").font(.caption)
                        }
                        .onTapGesture { viewModel.selectModel(model) }
                    } else {
                        Button("ダウンロード") { viewModel.download(model) }
                            .buttonStyle(.borderedProminent)
                            .disabled(viewModel.ui.downloading != nil)
                    }
                }
                if isDownloading {
                    if viewModel.ui.downloadProgress >= 0 {
                        ProgressView(value: Double(viewModel.ui.downloadProgress))
                    } else {
                        ProgressView()
                    }
                }
            }
            if let err = viewModel.ui.downloadError {
                Text("ダウンロード失敗: \(err)").foregroundColor(.red)
            }
            Text("※ 録音中に変更した場合は次回の録音開始から反映されます。")
                .font(.caption)
        }
    }
}

/// 録音開始/終了＋一時停止/再開。
/// Android は一時停止/再開を通知バーのボタンで行うが、iOS は常駐通知が無いためここに置く。
struct ControlRow: View {
    @EnvironmentObject var service: AudioCaptureService
    @ObservedObject private var enrollment = OwnerVoiceEnrollmentController.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Button("録音開始") { AudioCaptureService.shared.start() }
                    .buttonStyle(.borderedProminent)
                    .disabled(service.state.active || enrollment.isBusy)
                if service.state.active {
                    if service.state.paused {
                        Button("再開") { AudioCaptureService.shared.resumeMic() }
                            .buttonStyle(.bordered)
                    } else {
                        Button("一時停止") { AudioCaptureService.shared.pauseMic() }
                            .buttonStyle(.bordered)
                    }
                }
                Button("終了") { AudioCaptureService.shared.stop() }
                    .buttonStyle(.bordered)
                    .disabled(!service.state.active)
            }
            Text("※ 一時停止はマイクを完全に解放し、再開で再取得します。")
                .font(.caption)
            if enrollment.isBusy {
                Text("声の登録中は通常録音を開始できません。").font(.caption)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 12秒のオーナー音声をメモリ上だけに録音し、送信用のPCMを渡す。
final class OwnerVoiceEnrollmentController: ObservableObject {
    static let shared = OwnerVoiceEnrollmentController()
    static let enrollmentSeconds = 12

    @Published private(set) var isRecording = false
    @Published private(set) var progress: Float = 0
    @Published private(set) var message: String?

    var isBusy: Bool { isRecording }

    private var audioEngine: AVAudioEngine?
    private var tapInstalled = false
    private let sampleLock = NSLock()
    private var samples: [Int16] = []
    private var finishing = false
    private var onCaptured: (([Int16]) -> Void)?

    private init() {}

    func start(onCaptured: @escaping ([Int16]) -> Void) {
        guard !isBusy else { return }
        let session = AVAudioSession.sharedInstance()
        guard session.recordPermission == .granted else {
            message = "声を登録するにはマイク権限が必要です"
            return
        }
        do {
            try session.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
            try session.setActive(true)

            let engine = AVAudioEngine()
            let input = engine.inputNode
            let inputFormat = input.outputFormat(forBus: 0)
            guard let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Double(AudioChunker.sampleRate),
                channels: 1,
                interleaved: true),
                let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
                throw EnrollmentError.audioFormat
            }

            sampleLock.lock()
            samples.removeAll(keepingCapacity: true)
            samples.reserveCapacity(AudioChunker.sampleRate * Self.enrollmentSeconds)
            sampleLock.unlock()
            progress = 0
            message = nil
            finishing = false
            self.onCaptured = onCaptured
            isRecording = true
            audioEngine = engine

            input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
                guard let self else { return }
                let ratio = outputFormat.sampleRate / buffer.format.sampleRate
                let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 16
                guard let converted = AVAudioPCMBuffer(
                    pcmFormat: outputFormat, frameCapacity: capacity) else { return }
                var consumed = false
                var conversionError: NSError?
                converter.convert(to: converted, error: &conversionError) { _, status in
                    if consumed {
                        status.pointee = .noDataNow
                        return nil
                    }
                    consumed = true
                    status.pointee = .haveData
                    return buffer
                }
                guard conversionError == nil,
                      let channel = converted.int16ChannelData else { return }
                let target = AudioChunker.sampleRate * Self.enrollmentSeconds
                self.sampleLock.lock()
                let remaining = max(0, target - self.samples.count)
                let count = min(remaining, Int(converted.frameLength))
                if count > 0 {
                    self.samples.append(contentsOf: UnsafeBufferPointer(start: channel[0], count: count))
                }
                let recorded = self.samples.count
                self.sampleLock.unlock()
                DispatchQueue.main.async {
                    self.progress = min(1, Float(recorded) / Float(target))
                    if recorded >= target { self.finishCapture() }
                }
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
        } catch {
            stopCapture()
            isRecording = false
            onCaptured = nil
            message = "マイクを初期化できませんでした: \(error.localizedDescription)"
        }
    }

    func cancel() {
        guard isRecording else { return }
        stopCapture()
        isRecording = false
        progress = 0
        onCaptured = nil
        message = "声の登録を中止しました"
    }

    private func finishCapture() {
        guard isRecording, !finishing else { return }
        finishing = true
        stopCapture()
        isRecording = false
        message = nil
        sampleLock.lock()
        let captured = samples
        samples.removeAll()
        sampleLock.unlock()
        let callback = onCaptured
        onCaptured = nil
        finishing = false
        progress = 1
        callback?(captured)
    }

    private func stopCapture() {
        if tapInstalled {
            audioEngine?.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        audioEngine?.stop()
        audioEngine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private enum EnrollmentError: LocalizedError {
        case audioFormat
        var errorDescription: String? { "16kHz音声への変換を初期化できません" }
    }
}

/// 録音区間の長さ（何分ごとに文字起こし/アップロードするか）を設定するカード。
/// UserDefaults に保存し、次の区間から即時反映される。
struct SegmentIntervalCard: View {
    private let store = AccountStore()
    @State private var minutes: Int = 60

    var body: some View {
        CardView {
            Text("録音区間の長さ").font(.headline)
            Text("この分数ごとに音声を文字起こし（またはサーバーへアップロード）します。短くするとほぼリアルタイムで処理されますが処理回数が増えます。録音中でも次の区間から反映されます。")
                .font(.caption)
            HStack {
                Text("区間の長さ")
                Spacer()
                Stepper(intervalLabel, value: $minutes, in: 1...240)
                    .fixedSize()
                    .onChange(of: minutes) { v in
                        store.segmentIntervalMinutes = v
                    }
            }
        }
        .onAppear { minutes = store.segmentIntervalMinutes }
    }

    private var intervalLabel: String {
        if minutes >= 60 && minutes % 60 == 0 { return "\(minutes / 60)時間" }
        return "\(minutes) 分"
    }
}
