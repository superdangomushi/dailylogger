import SwiftUI

/// AIタブ。チャットを主役にし、今日の要約・予定/課題は折りたたみで上部に、
/// AIHelper ログインや各種連携（Google/Moodle/Waseda/通知）は右上⚙の設定画面に分離した。
struct AiTabView: View {
    @EnvironmentObject var viewModel: MainViewModel
    @State private var settingsOpen = false
    @State private var summaryExpanded = false
    @State private var tasksExpanded = false

    var body: some View {
        VStack(spacing: 0) {
            // ヘッダー: タイトルと設定（⚙）ボタン。
            HStack {
                Text("AIアシスタント").font(.headline)
                Spacer()
                Button {
                    settingsOpen = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.title3)
                        .foregroundColor(AppTheme.primary)
                }
                .accessibilityLabel("連携・設定")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            if viewModel.ui.account.loggedIn {
                loggedInBody
            } else {
                loggedOutBody
            }
        }
        .background(AppTheme.background)
        .sheet(isPresented: $settingsOpen) {
            AiSettingsView().environmentObject(viewModel)
        }
        .onAppear {
            guard viewModel.ui.account.loggedIn else { return }
            viewModel.loadChatHistory()
            viewModel.loadSummary()
            viewModel.loadTasks()
        }
    }

    /// 未ログイン時: チャットは使えないので、設定へ誘導するだけのシンプルな画面。
    private var loggedOutBody: some View {
        ScrollView {
            CardView {
                Text("AIアシスタントを使うには").font(.headline)
                Text("AIHelper にログインすると、今日の要約や予定・課題を見ながらAIに相談・登録を頼めます。")
                    .font(.caption)
                Button("ログイン / 新規登録") { settingsOpen = true }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
            }
            .padding(16)
        }
    }

    /// ログイン時: 折りたたみの要約・予定課題を上に、残りをチャットが占める。
    private var loggedInBody: some View {
        VStack(spacing: 10) {
            CollapsibleSection(title: "今日の要約", expanded: $summaryExpanded) {
                SummaryContent()
            }
            CollapsibleSection(
                title: "予定・課題",
                badge: viewModel.ui.tasks.isEmpty ? nil : "\(viewModel.ui.tasks.count)",
                expanded: $tasksExpanded
            ) {
                TasksContent()
            }
            Divider()
            AiChatPanel(expandMessages: true)
        }
        .padding(16)
    }
}

/// 見出しをタップで開閉するカード。既定は閉じた状態でチャットを広く見せる。
struct CollapsibleSection<Content: View>: View {
    let title: String
    var badge: String? = nil
    @Binding var expanded: Bool
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text(title).font(.subheadline.bold())
                    if let badge {
                        Text(badge)
                            .font(.caption2.bold())
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .background(AppTheme.primaryContainer)
                            .foregroundColor(AppTheme.onPrimaryContainer)
                            .clipShape(Capsule())
                    }
                    Spacer()
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption).foregroundColor(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                content.padding(.top, 10)
            }
        }
        .padding(12)
        .background(Color(.systemBackground))
        .cornerRadius(12)
        .shadow(color: .black.opacity(0.06), radius: 3, y: 1)
    }
}

/// 折りたたみ内に入れる「今日の要約」の中身（見出しは CollapsibleSection 側）。
private struct SummaryContent: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Spacer()
                Button("生成") { viewModel.generateSummary() }
                    .disabled(viewModel.ui.summaryLoading)
                Button("更新") { viewModel.loadSummary() }
                    .buttonStyle(.bordered)
                    .disabled(viewModel.ui.summaryLoading)
            }
            if let err = viewModel.ui.summaryError {
                Text("エラー: \(err)").foregroundColor(.red).font(.caption)
            }
            if viewModel.ui.summaryLoading && (viewModel.ui.summary ?? "").isEmpty {
                Text("読み込み中…").font(.caption)
            } else if (viewModel.ui.summary ?? "").isEmpty {
                Text("まだ今日の要約はありません。録音がたまるか「生成」で作成できます。")
                    .font(.caption)
            } else {
                ScrollView {
                    Text(viewModel.ui.summary ?? "")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 180)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 折りたたみ内に入れる「予定・課題」の中身。
private struct TasksContent: View {
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button(viewModel.ui.showDoneTasks ? "未完了のみ" : "完了も表示") {
                    viewModel.loadTasks(includeDone: !viewModel.ui.showDoneTasks)
                }
                .font(.caption)
                Spacer()
                Button("更新") { viewModel.loadTasks() }
                    .buttonStyle(.bordered)
                    .font(.caption)
            }
            if let err = viewModel.ui.tasksError {
                Text("取得エラー: \(err)").foregroundColor(.red).font(.caption)
            }
            if viewModel.ui.tasksLoading && viewModel.ui.tasks.isEmpty {
                Text("読み込み中…").font(.caption)
            } else if viewModel.ui.tasks.isEmpty {
                Text("表示できる予定・課題はありません。").font(.caption)
            } else {
                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(viewModel.ui.tasks) { task in
                            TaskCardView(task: task)
                        }
                    }
                }
                .frame(maxHeight: 300)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 予定・課題1件のカード。チェックで完了/未完了を切替。Google 連携済みなら登録ボタンを出す。
struct TaskCardView: View {
    @EnvironmentObject var viewModel: MainViewModel
    let task: AiHelperClient.Task
    @State private var editing = false

    var body: some View {
        let actionInProgress = viewModel.ui.taskActionInProgressId == task.id
        let isYotei = task.type == "yotei"
        let label = isYotei ? "予定" : "課題"
        let labelColor = isYotei ? AppTheme.tertiary : AppTheme.primary
        CardView {
            HStack(alignment: .top, spacing: 8) {
                Button {
                    viewModel.toggleTaskDone(task)
                } label: {
                    Image(systemName: task.done ? "checkmark.square.fill" : "square")
                        .font(.title3)
                        .foregroundColor(task.done ? AppTheme.primary : .secondary)
                }
                .disabled(actionInProgress)
                VStack(alignment: .leading, spacing: 4) {
                    Text("[\(label)]")
                        .font(.caption.bold())
                        .foregroundColor(labelColor)
                    Text(task.content)
                        .strikethrough(task.done)
                    Text("期限: \(formatDeadline(task.deadline, dateOnly: task.dateOnly))")
                        .font(.caption)
                    if !task.details.isEmpty {
                        Text(task.details)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    if viewModel.ui.googleConnected, let dl = task.deadline, !dl.isEmpty {
                        Button("カレンダーに追加") { viewModel.addTaskToCalendar(task) }
                            .font(.caption)
                            .disabled(actionInProgress)
                    }
                    HStack(spacing: 8) {
                        Button("編集") { editing = true }
                            .font(.caption)
                            .disabled(actionInProgress)
                        if actionInProgress {
                            ProgressView().scaleEffect(0.6)
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $editing) {
            TaskEditView(
                task: task,
                saving: viewModel.ui.taskActionInProgressId == task.id,
                onSave: { type, content, details, deadline in
                    viewModel.updateTask(task, type: type, content: content, details: details, deadline: deadline)
                    editing = false
                },
                onDelete: {
                    viewModel.deleteTask(task)
                    editing = false
                },
                onDismiss: { editing = false }
            )
        }
    }
}
