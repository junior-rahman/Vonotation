import SwiftUI
import AVFoundation

struct NoteDetailView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let noteID: UUID
    @State private var editing = false
    @State private var deleting = false
    @State private var transcriptExpanded = false

    private var note: VonoNote? { model.notes.first { $0.id == noteID } }

    var body: some View {
        ZStack {
            LinenBackground()
            if let note {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        VStack(alignment: .leading, spacing: 7) {
                            Text(note.title.isEmpty ? "Saved recording" : note.title).font(.title2.weight(.bold))
                                .accessibilityIdentifier("note.detail.title")
                            Text("\(note.createdAt.formatted(date: .abbreviated, time: .shortened)) · \(note.kind.title)")
                                .font(.caption).foregroundStyle(Daybook.muted)
                        }
                        if note.state != .ready { processingCard(note) }
                        if note.kind == .translation && !note.translation.isEmpty {
                            textSection("Translation", note.translation, identifier: "note.detail.translation")
                        }
                        if !note.summary.isEmpty {
                            textSection("Summary", note.summary, identifier: "note.summary")
                        } else if note.kind == .thought {
                            DaybookCard {
                                Text("Summary").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
                                Text("Your source is saved. A concise summary will appear after transcription and processing.")
                                    .font(.subheadline).foregroundStyle(Daybook.muted).padding(.top, 4)
                            }
                        }
                        if note.kind == .dictation {
                            textSection("Dictation", note.transcript.isEmpty ? "The transcript is pending." : note.transcript, identifier: "note.transcript")
                        } else {
                            DisclosureGroup(isExpanded: $transcriptExpanded) {
                                Text(note.transcript.isEmpty ? "The transcript is pending." : note.transcript)
                                    .font(.body).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 9)
                                    .accessibilityIdentifier("note.transcript")
                            } label: {
                                Text("Full transcript").font(.subheadline.weight(.semibold)).foregroundStyle(Daybook.accent).frame(minHeight: 44)
                                    .accessibilityIdentifier("note.detail.transcript.disclosure")
                            }
                        }
                        if !note.rawTranscript.isEmpty && note.rawTranscript != note.transcript {
                            DisclosureGroup {
                                Text(note.rawTranscript).font(.subheadline).foregroundStyle(Daybook.muted).textSelection(.enabled)
                                    .fixedSize(horizontal: false, vertical: true).padding(.top, 8).accessibilityIdentifier("note.original")
                            } label: {
                                Text("Heard before cleanup").font(.subheadline).foregroundStyle(Daybook.accent).frame(minHeight: 44)
                                    .accessibilityIdentifier("note.detail.original.disclosure")
                            }
                        }
                        if let url = model.audioURL(for: note) {
                            DisclosureGroup {
                                SourceAudioView(url: url).padding(.top, 9)
                            } label: {
                                Label("Source audio", systemImage: "waveform").font(.subheadline).foregroundStyle(Daybook.accent).frame(minHeight: 44)
                                    .accessibilityIdentifier("note.audio")
                            }
                        }
                        actionRow(note)
                    }
                    .padding(22).frame(maxWidth: 680, alignment: .leading).frame(maxWidth: .infinity)
                }
            } else {
                EmptyDaybook(title: "This note is no longer here.", detail: "It may have been removed from this device.", symbol: "note.text")
                    .padding(24)
            }
        }
        .foregroundStyle(Daybook.ink).tint(Daybook.accent)
        .navigationTitle(note?.kind.title ?? "Note").navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbarBackground(Daybook.paper, for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
        .safeAreaInset(edge: .bottom) { if model.isRecording { ActiveRecordingStrip().padding(.horizontal, 16).background(Daybook.paper) } }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if note != nil { Button("Edit") { editing = true }.disabled(model.isRecording || model.isBackingUp).accessibilityIdentifier("note.detail.edit") }
            }
        }
        .sheet(isPresented: $editing) { if let note { EditNoteView(note: note).environmentObject(model) } }
        .confirmationDialog("Delete this note?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete note and source audio", role: .destructive) {
                if let note, model.deleteNote(note) { dismiss() }
            }
            Button("Keep note", role: .cancel) { }
        } message: { Text("The saved text and its source recording will be removed from this device.") }
    }

    private func textSection(_ title: String, _ text: String, identifier: String) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title).font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
            Text(text).font(.body).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier(identifier)
        }
    }

    private func processingCard(_ note: VonoNote) -> some View {
        DaybookCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    if note.state == .processing { ProgressView() }
                    else { Image(systemName: note.state == .failed ? "arrow.clockwise" : "clock") }
                    Text(note.state == .processing ? "Writing it up…" : "Saved on this device").font(.subheadline.weight(.semibold))
                }
                Text(note.error ?? "The audio stays safe here while transcription is waiting. Add your provider key and retry when you are online.")
                    .font(.caption).foregroundStyle(Daybook.muted)
                if note.state != .processing {
                    Button { Task { await model.retry(note) } } label: { Label("Retry transcription", systemImage: "arrow.clockwise") }
                        .buttonStyle(DaybookButtonStyle()).disabled(model.isProcessing || model.isRecording)
                        .accessibilityIdentifier("note.detail.retry")
                }
            }
        }
    }

    private func actionRow(_ note: VonoNote) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { actions(note) }
            VStack(alignment: .leading, spacing: 10) { actions(note) }
        }
    }

    @ViewBuilder private func actions(_ note: VonoNote) -> some View {
        Button {
            model.copyText(shareText(note))
            Daybook.feedback(model.settings.haptics)
        } label: { Label("Copy", systemImage: "doc.on.doc") }
            .buttonStyle(DaybookButtonStyle()).disabled(shareText(note).isEmpty)
            .accessibilityIdentifier("note.detail.copy")
        ShareLink(item: shareText(note)) { Label("Share", systemImage: "square.and.arrow.up") }
            .buttonStyle(DaybookButtonStyle()).disabled(shareText(note).isEmpty)
            .accessibilityIdentifier("note.detail.share")
        Button(role: .destructive) { deleting = true } label: { Label("Delete", systemImage: "trash") }
            .buttonStyle(DaybookButtonStyle()).disabled(model.isBackingUp).accessibilityIdentifier("note.detail.delete")
    }

    private func shareText(_ note: VonoNote) -> String {
        if note.kind == .dictation { return note.transcript }
        let primary = note.kind == .translation && !note.translation.isEmpty ? note.translation : note.summary
        let parts = [note.title, primary, note.transcript.isEmpty ? "" : "Full transcript\n\(note.transcript)"]
        return parts.filter { !$0.isEmpty }.joined(separator: "\n\n")
    }
}

struct EditNoteView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let note: VonoNote
    @State private var title: String
    @State private var transcript: String
    init(note: VonoNote) {
        self.note = note
        _title = State(initialValue: note.title)
        _transcript = State(initialValue: note.transcript)
    }
    var body: some View {
        NavigationStack {
            ZStack {
                LinenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Title").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
                        TextField("Note title", text: $title).font(.title3.weight(.semibold))
                            .padding(12).background(Daybook.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Daybook.fieldOutline, lineWidth: 1))
                            .accessibilityIdentifier("note.edit.title")
                        Text("Full transcript").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
                        TextEditor(text: $transcript).scrollContentBackground(.hidden).frame(minHeight: 320)
                            .padding(8).background(Daybook.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Daybook.fieldOutline, lineWidth: 1))
                            .accessibilityIdentifier("note.edit.transcript")
                        Text("The original transcription and source audio stay with the note.")
                            .font(.caption).foregroundStyle(Daybook.muted)
                    }.padding(22).frame(maxWidth: 680).frame(maxWidth: .infinity)
                }
            }
            .foregroundStyle(Daybook.ink).tint(Daybook.accent)
            .navigationTitle("Edit note").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.updateNote(note, title: title.trimmingCharacters(in: .whitespacesAndNewlines), transcript: transcript)
                        dismiss()
                    }.accessibilityIdentifier("note.edit.save")
                }
            }
        }
    }
}

@MainActor final class SourceAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var playing = false
    @Published var duration: TimeInterval = 0
    @Published var current: TimeInterval = 0
    @Published var error: String?
    weak var recordingModel: AppModel?
    private var player: AVAudioPlayer?
    private var timer: Timer?

    func toggle(url: URL) {
        if let player {
            if player.isPlaying { player.pause(); playing = false; timer?.invalidate(); timer = nil }
            else { player.play(); playing = true; tick() }
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.prepareToPlay()
            duration = player.duration
            guard player.play() else { error = "The source audio could not start."; return }
            self.player = player
            playing = true
            tick()
        } catch { self.error = "This source audio could not be played: \(error.localizedDescription)" }
    }
    func seek(_ time: TimeInterval) { player?.currentTime = time; current = time }
    func stop(releaseSession: Bool = true) {
        let ownedPlayback = player != nil
        timer?.invalidate(); timer = nil; player?.stop(); player = nil; playing = false; current = 0
        if ownedPlayback && releaseSession && recordingModel?.isRecording != true {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
    private func tick() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.current = self?.player?.currentTime ?? 0 }
        }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

@MainActor struct SourceAudioView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var player = SourceAudioPlayer()
    let url: URL
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("The recording is the source for this note.").font(.caption).foregroundStyle(Daybook.muted)
            if let error = player.error { Text(error).font(.caption).foregroundStyle(Daybook.coral) }
            Button {
                guard !model.isRecording else { return }
                player.toggle(url: url)
                Daybook.feedback(model.settings.haptics)
            } label: { Label(player.playing ? "Pause source" : "Play source", systemImage: player.playing ? "pause.fill" : "play.fill") }
                .buttonStyle(DaybookButtonStyle()).disabled(model.isRecording)
                .accessibilityIdentifier("note.detail.audio.play")
            if player.duration > 0 {
                Slider(value: Binding(get: { player.current }, set: { player.seek($0) }), in: 0...max(1, player.duration))
                    .accessibilityLabel("Source audio position")
                Text("\(clock(player.current)) / \(clock(player.duration))").font(.caption.monospacedDigit()).foregroundStyle(Daybook.muted)
            }
        }
        .onAppear { player.recordingModel = model }
        .onDisappear { player.stop(releaseSession: !model.isRecording) }
        .onChange(of: model.isRecording) { _, recording in if recording { player.stop(releaseSession: false) } }
    }
    private func clock(_ seconds: TimeInterval) -> String { String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60) }
}
