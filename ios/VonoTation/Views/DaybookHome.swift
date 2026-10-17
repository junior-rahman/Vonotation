import SwiftUI
import UniformTypeIdentifiers

enum DaybookPage: String, CaseIterable, Identifiable {
    case voiceNotes, calls, translate, dictations, thoughts, clipboard
    var id: String { rawValue }
    var title: String {
        switch self {
        case .voiceNotes: return "Voice notes"
        case .calls: return "Calls"
        case .translate: return "Translate"
        case .dictations: return "Dictation"
        case .thoughts: return "Thoughts"
        case .clipboard: return "Clipboard"
        }
    }
    var kind: NoteKind? {
        switch self {
        case .voiceNotes: return .voiceNote
        case .calls: return .call
        case .translate: return .translation
        case .dictations: return .dictation
        case .thoughts: return .thought
        case .clipboard: return nil
        }
    }
    var symbol: String {
        switch self {
        case .voiceNotes: return "waveform"
        case .calls: return "phone"
        case .translate: return "character.bubble"
        case .dictations: return "waveform"
        case .thoughts: return "note.text"
        case .clipboard: return "doc.on.clipboard"
        }
    }
    var navigationID: String {
        self == .voiceNotes ? "nav.voicenotes" : "nav.\(rawValue)"
    }
}

struct DaybookHome: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var page: DaybookPage = .voiceNotes
    @State private var showSettings = false
    @State private var importAudio = false

    private var pages: [DaybookPage] {
        DaybookPage.allCases.filter { $0 != .thoughts || model.settings.quickThoughts }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                LinenBackground()
                VStack(spacing: 0) {
                    cardNavigation
                    ZStack(alignment: .top) {
                        RoundedRectangle(cornerRadius: 28)
                            .fill(Daybook.soft)
                            .rotationEffect(.degrees(reduceMotion ? 0 : -3))
                            .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 12)
                            .accessibilityHidden(true).allowsHitTesting(false)
                        RoundedRectangle(cornerRadius: 28)
                            .fill(Daybook.surface.opacity(0.75))
                            .rotationEffect(.degrees(reduceMotion ? 0 : 2))
                            .padding(.horizontal, 12).padding(.top, 4).padding(.bottom, 6)
                            .accessibilityHidden(true).allowsHitTesting(false)
                        selectedCard
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 14)
                    .padding(.bottom, 10)
                    footer
                }
                .frame(maxWidth: 720)
            }
            .foregroundStyle(Daybook.ink)
            .tint(Daybook.action)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: UUID.self) { id in NoteDetailView(noteID: id) }
            .sheet(isPresented: $showSettings) {
                SettingsView().environmentObject(model)
            }
            .fileImporter(isPresented: $importAudio, allowedContentTypes: [.audio], allowsMultipleSelection: false) { result in
                guard case .success(let urls) = result, let url = urls.first else { return }
                let kind: NoteKind = page == .calls ? .call : page.kind ?? .voiceNote
                Task { await model.importAudio(from: url, kind: kind) }
            }
            .alert("VonoTation", isPresented: Binding(get: { model.errorMessage != nil && !showSettings }, set: { if !$0 && !showSettings { model.acknowledgeError() } })) {
                Button("OK") { model.acknowledgeError() }
            } message: { Text(model.errorMessage ?? "") }
            .onChange(of: model.settings.quickThoughts) { _, enabled in
                if !enabled && page == .thoughts { page = .voiceNotes }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { model.refreshExpiry() }
            }
            .task {
                #if DEBUG
                let arguments = ProcessInfo.processInfo.arguments
                if arguments.contains("--ui-testing"), let marker = arguments.firstIndex(of: "--ui-screen"), arguments.indices.contains(marker + 1) {
                    let screen = arguments[marker + 1]
                    if screen == "settings" { showSettings = true }
                    else if let target = DaybookPage(rawValue: screen) { page = target }
                }
                #endif
            }
            .onOpenURL { url in
                guard url.scheme == "vonotation" else { return }
                let action = url.host ?? ""
                if action == "thought" || action == "record-thought" {
                    Task {
                        do {
                            try await VonoRuntime.begin(kind: .thought)
                            if model.settings.quickThoughts { page = .thoughts }
                        }
                        catch { model.errorMessage = error.localizedDescription }
                    }
                } else if action == "dictate" {
                    Task {
                        do {
                            try await VonoRuntime.begin(kind: .dictation)
                            page = .dictations
                        }
                        catch { model.errorMessage = error.localizedDescription }
                    }
                }
            }
        }
        .tint(Daybook.accent)
    }

    private var cardNavigation: some View {
        ScrollViewReader { position in
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(pages) { candidate in
                        Button {
                            Daybook.feedback(model.settings.haptics)
                            withAnimation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.85)) { page = candidate }
                        } label: {
                            HStack(spacing: 6) {
                                if candidate == .thoughts {
                                    ThoughtMark().stroke(style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round)).frame(width: 16, height: 17)
                                } else if candidate == .dictations {
                                    DaybookMark(size: 16, color: page == candidate ? Daybook.actionInk : Daybook.accent)
                                } else { Image(systemName: candidate.symbol).font(.caption) }
                                Text(candidate.title).font(.subheadline.weight(page == candidate ? .semibold : .regular))
                            }
                            .foregroundStyle(page == candidate ? Daybook.actionInk : Daybook.accent)
                            .padding(.horizontal, 13).padding(.vertical, 12)
                            .background(page == candidate ? Daybook.action : Daybook.surface, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .id(candidate)
                        .accessibilityIdentifier(candidate.navigationID)
                        .accessibilityAddTraits(page == candidate ? [.isSelected] : [])
                    }
                }
                .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 2)
            }
            .scrollIndicators(.hidden)
            .accessibilityLabel("Daybook cards")
            .onChange(of: page, initial: true) { _, selected in
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    position.scrollTo(selected, anchor: .center)
                }
            }
        }
    }

    private var selectedCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(page.title).font(.title2.weight(.bold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("page.title")
                Spacer(minLength: 12)
                if page == .calls || page == .voiceNotes {
                    Button { importAudio = true } label: { Image(systemName: "square.and.arrow.down").frame(width: 44, height: 44) }
                        .accessibilityLabel(page == .calls ? "Import call audio" : "Import audio")
                        .accessibilityIdentifier("audio.import")
                }
            }
            .padding(.horizontal, 18).padding(.top, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if !model.isLoaded { ProgressView("Opening your Daybook…").padding(.vertical, 20) }
                    if let message = model.statusMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Text(message).font(.caption).foregroundStyle(Daybook.muted)
                            Spacer(minLength: 0)
                            Button { model.acknowledgeStatus() } label: { Image(systemName: "xmark").frame(width: 32, height: 32) }
                                .accessibilityLabel("Dismiss status")
                        }
                    }
                    pageIntroduction
                    if page == .clipboard {
                        ClipboardTimeline()
                    } else if let kind = page.kind {
                        NotesTimeline(kind: kind)
                    }
                }
                .padding(.horizontal, 18).padding(.top, 10).padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            if model.isRecording { ActiveRecordingStrip().padding(12) }
            else if page == .voiceNotes || page == .dictations || page == .translate { recordButton }
        }
        .background(Daybook.surface, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(Daybook.line.opacity(0.85), lineWidth: 0.5))
        .shadow(color: Daybook.shadow.opacity(0.055), radius: 14, x: 0, y: 7)
    }

    @ViewBuilder private var pageIntroduction: some View {
        switch page {
        case .calls:
            Text("Import a recording to keep its summary and full transcript together. iOS does not let VonoTation record other apps’ calls.")
                .font(.caption).foregroundStyle(Daybook.muted)
        case .translate:
            VStack(alignment: .leading, spacing: 8) {
                Text("Speak, finish, then get the translation. The original words stay with it.").font(.caption).foregroundStyle(Daybook.muted)
                HStack {
                    Text("Translate to").font(.subheadline)
                    TextField("Language", text: $model.settings.translationLanguage)
                        .textInputAutocapitalization(.words).multilineTextAlignment(.trailing)
                        .accessibilityIdentifier("translation.language")
                }.padding(12).background(Daybook.soft, in: RoundedRectangle(cornerRadius: 12))
            }
        case .thoughts:
            if model.notes.filter({ $0.kind == .thought }).isEmpty {
                Text("Start a Quick Thought with the Record Quick Thought shortcut, Siri, or vonotation://thought. Your summary will be here, with the original transcript and source audio tucked inside.")
                    .font(.caption).foregroundStyle(Daybook.muted)
            }
        case .dictations:
            Text("Record here, then copy or share the text into the app you need. Recording continues until you choose Finish or Cancel.")
                .font(.caption).foregroundStyle(Daybook.muted)
        case .clipboard:
            VStack(alignment: .leading, spacing: 10) {
                Text("Paste text here to keep it for 30 days. iOS requires an explicit paste; VonoTation does not monitor other apps’ clipboard.")
                    .font(.caption).foregroundStyle(Daybook.muted)
                ClipboardPasteControl { model.captureClipboard($0) }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("clipboard.paste")
            }
        case .voiceNotes: EmptyView()
        }
    }

    private var recordButton: some View {
        HStack {
            Spacer()
            Button {
                guard let kind = page.kind else { return }
                Daybook.feedback(model.settings.haptics, strong: true)
                Task { await model.startRecording(kind: kind) }
            } label: {
                Label(page == .dictations ? "Dictate" : page == .translate ? "Record translation" : "Record", systemImage: "waveform")
                    .frame(minWidth: 130)
            }
            .buttonStyle(DaybookButtonStyle(primary: true))
            .disabled(model.isBackingUp)
            .accessibilityIdentifier("record.toggle")
        }
        .padding(.horizontal, 18).padding(.bottom, 14).padding(.top, 8)
    }

    private var footer: some View {
        HStack(spacing: 9) {
            DaybookMark()
            Text("VonoTation").font(.headline)
            Spacer()
            Button { Daybook.feedback(model.settings.haptics); showSettings = true } label: {
                Image(systemName: "slider.horizontal.3").font(.title3).frame(width: 44, height: 44)
                    .background(Daybook.surface, in: RoundedRectangle(cornerRadius: 14))
            }
            .accessibilityLabel("Settings").accessibilityIdentifier("nav.settings")
        }
        .foregroundStyle(Daybook.accent)
        .padding(.horizontal, 22).padding(.bottom, 8)
    }
}
