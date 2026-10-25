import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum DayLabels {
    static func key(_ day: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: day)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
    static func title(_ day: Date) -> String {
        if Calendar.current.isDateInToday(day) { return "Today" }
        if Calendar.current.isDateInYesterday(day) { return "Yesterday" }
        return day.formatted(.dateTime.weekday(.wide))
    }
}

struct NotesTimeline: View {
    @EnvironmentObject private var model: AppModel
    let kind: NoteKind
    var query = ""
    var pinnedOnly = false

    private var notes: [VonoNote] { model.notes.filter { $0.kind == kind && $0.deletedAt == nil && (!pinnedOnly || $0.pinned == true) && RecordingSearch.matches(query, note: $0) } }
    private var pinned: [VonoNote] { notes.filter { $0.pinned == true } }
    private var days: [Date] {
        Set(notes.filter { $0.pinned != true }.map { Calendar.current.startOfDay(for: $0.createdAt) }).sorted(by: >)
    }

    var body: some View {
        if notes.isEmpty && model.isLoaded {
            EmptyDaybook(title: query.isEmpty && !pinnedOnly ? emptyTitle : "No matching recordings.", detail: query.isEmpty && !pinnedOnly ? emptyDetail : "Try another search or show all recordings.", symbol: kind == .thought ? "note.text" : "waveform")
        }
        if !pinned.isEmpty {
            Text("Pinned").font(.subheadline.weight(.semibold)).foregroundStyle(Daybook.accent)
            ForEach(pinned) { note in NavigationLink(value: note.id) { NoteTimelineRow(note: note) }.buttonStyle(.plain).accessibilityIdentifier("note.\(note.id.uuidString)") }
        }
        ForEach(days, id: \.self) { day in
            DayTimelineSection(day: day, count: entries(on: day).count, first: day == days.first) {
                VStack(spacing: 0) {
                    ForEach(entries(on: day)) { note in
                        NavigationLink(value: note.id) {
                            NoteTimelineRow(note: note)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("note.\(note.id.uuidString)")
                    }
                }
                if model.settings.showDailyRecaps && (kind == .dictation || kind == .thought) {
                    RecapView(kind: kind, day: day)
                }
            }
        }
    }
    private func entries(on day: Date) -> [VonoNote] {
        notes.filter { $0.pinned != true && Calendar.current.isDate($0.createdAt, inSameDayAs: day) }
    }
    private var emptyTitle: String {
        kind == .thought ? "A place for passing thoughts." : kind == .call ? "No imported calls yet." : "Your words belong here."
    }
    private var emptyDetail: String {
        switch kind {
        case .voiceNote: return "Tap Record to begin. Audio saves on this device first; transcription can wait until you are online."
        case .dictation: return "Your finished dictations are grouped by day. Copy or share them into another app."
        case .thought: return "Quick Thoughts focus on titles, concise summaries and the complete original transcript."
        case .call: return "Use Import call audio above to choose a recording from Files."
        case .translation: return "Choose a target language, then record what you want to translate."
        }
    }
}

struct DayTimelineSection<Content: View>: View {
    let day: Date
    let count: Int
    let first: Bool
    private let content: Content
    @State private var expanded: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var model: AppModel

    init(day: Date, count: Int, first: Bool, @ViewBuilder content: () -> Content) {
        self.day = day; self.count = count; self.first = first; self.content = content()
        _expanded = State(initialValue: Calendar.current.isDateInToday(day))
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                Circle().fill(first ? Daybook.action : Daybook.accent).frame(width: 7, height: 7).padding(.top, 18)
                Rectangle().fill(Daybook.line).frame(width: 0.7).frame(maxHeight: .infinity).padding(.top, 5)
            }
            .frame(width: 9).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 10) {
                Button {
                    Daybook.feedback(model.settings.haptics)
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) { expanded.toggle() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(DayLabels.title(day)).font(.subheadline.weight(.semibold)).foregroundStyle(Daybook.ink)
                            Text("\(day.formatted(.dateTime.month(.abbreviated).day())) · \(count) saved")
                                .font(.caption).foregroundStyle(Daybook.muted)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(expanded ? "Hide" : "Show") \(DayLabels.title(day)), \(count) saved")
                .accessibilityIdentifier("timeline.day.\(DayLabels.key(day))")
                if expanded { content }
            }
            .padding(.bottom, 18)
        }
    }
}

struct NoteTimelineRow: View {
    @EnvironmentObject private var model: AppModel
    let note: VonoNote
    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Daybook.line.opacity(0.6)).frame(height: 0.5)
            HStack(alignment: .top, spacing: 12) {
                Text(note.createdAt, format: .dateTime.hour().minute())
                    .font(.caption).foregroundStyle(Daybook.muted)
                    .frame(width: 58, alignment: .leading).padding(.top, 3)
                VStack(alignment: .leading, spacing: 5) {
                    Text(primaryText).font(.body).foregroundStyle(Daybook.ink).lineLimit(2)
                    if model.product == .notes {
                        Text(model.recordingStatus(note)).font(.caption).foregroundStyle(Daybook.muted)
                    } else if note.state != .ready {
                        Label(statusText, systemImage: note.state == .processing ? "arrow.triangle.2.circlepath" : "clock")
                            .font(.caption).foregroundStyle(Daybook.muted)
                    } else if note.kind != .dictation && !note.summary.isEmpty {
                        Text(note.summary).font(.caption).foregroundStyle(Daybook.muted).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption2).foregroundStyle(Daybook.accent).padding(.top, 5)
            }
            .padding(.vertical, 13).frame(maxWidth: .infinity, minHeight: 51, alignment: .leading).contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
    }
    private var primaryText: String {
        if note.kind == .dictation && !note.transcript.isEmpty { return note.transcript }
        return note.title.isEmpty ? "Saved recording" : note.title
    }
    private var statusText: String {
        switch note.state {
        case .pending: return "Saved on this device · waiting to transcribe"
        case .processing: return "Writing it up…"
        case .failed: return "Saved · retry available"
        case .ready: return "Ready"
        }
    }
}

struct RecapView: View {
    @EnvironmentObject private var model: AppModel
    let kind: NoteKind
    let day: Date
    @State private var working = false
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Label("Daily recap", systemImage: "sparkle").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
            if let text = model.recap(for: kind, on: day), !text.isEmpty {
                Text(text).font(.subheadline).foregroundStyle(Daybook.ink).textSelection(.enabled)
            } else {
                Text("A short summary of this day’s \(kind == .thought ? "thoughts" : "dictations").")
                    .font(.caption).foregroundStyle(Daybook.muted)
                Button {
                    working = true
                    Task { await model.generateRecap(for: kind, on: day); working = false }
                } label: {
                    if working { ProgressView().frame(maxWidth: .infinity) }
                    else { Text("Create daily recap").frame(maxWidth: .infinity) }
                }
                .buttonStyle(DaybookButtonStyle())
                .disabled(working || !model.hasAPIKey)
                .accessibilityIdentifier("recap.create.\(DayLabels.key(day))")
            }
        }
        .padding(12).background(Daybook.soft, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct ClipboardTimeline: View {
    @EnvironmentObject private var model: AppModel
    private var days: [Date] {
        Set(model.clips.map { Calendar.current.startOfDay(for: $0.createdAt) }).sorted(by: >)
    }
    var body: some View {
        if model.clips.isEmpty && model.isLoaded {
            EmptyDaybook(title: "A temporary home for copied text.", detail: "Text you paste here stays for 30 days from its original save time. Copying it again does not restart that timer.", symbol: "doc.on.clipboard")
        }
        ForEach(days, id: \.self) { day in
            let clips = model.clips.filter { Calendar.current.isDate($0.createdAt, inSameDayAs: day) }
            DayTimelineSection(day: day, count: clips.count, first: day == days.first) {
                VStack(spacing: 0) {
                    ForEach(clips) { clip in ClipboardTimelineRow(clip: clip) }
                }
            }
        }
    }
}

struct ClipboardTimelineRow: View {
    @EnvironmentObject private var model: AppModel
    let clip: ClipItem
    @State private var expanded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Rectangle().fill(Daybook.line.opacity(0.6)).frame(height: 0.5)
            DisclosureGroup(isExpanded: $expanded) {
                VStack(alignment: .leading, spacing: 12) {
                    Text(clip.text).font(.body).foregroundStyle(Daybook.ink).textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("clip.fulltext.\(clip.id.uuidString)")
                    Text("Expires \(clip.expiresAt.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption).foregroundStyle(Daybook.muted)
                    HStack(spacing: 8) {
                        Button { model.copyText(clip.text); Daybook.feedback(model.settings.haptics) } label: { Label("Copy", systemImage: "doc.on.doc") }
                            .buttonStyle(DaybookButtonStyle())
                        ShareLink(item: clip.text) { Image(systemName: "square.and.arrow.up").frame(minWidth: 24) }
                            .buttonStyle(DaybookButtonStyle()).accessibilityLabel("Share clipboard text")
                        Button(role: .destructive) { model.deleteClip(clip) } label: { Image(systemName: "trash").frame(minWidth: 24) }
                            .buttonStyle(DaybookButtonStyle()).accessibilityLabel("Delete clipboard text")
                    }
                }.padding(.top, 8).padding(.bottom, 13)
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    Text(clip.createdAt, format: .dateTime.hour().minute()).font(.caption).foregroundStyle(Daybook.muted).frame(width: 58, alignment: .leading).padding(.top, 3)
                    Text(clip.text).font(.body).foregroundStyle(Daybook.ink).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                }.padding(.vertical, 13).frame(minHeight: 51)
                    .accessibilityIdentifier("clip.\(clip.id.uuidString)")
            }
        }
    }
}

/// UIPasteControl performs the privacy-authorized paste. No general pasteboard reads.
struct ClipboardPasteControl: UIViewRepresentable {
    let onPaste: (String) -> Void
    func makeUIView(context: Context) -> PasteTargetView { PasteTargetView(onPaste: onPaste) }
    func updateUIView(_ uiView: PasteTargetView, context: Context) { uiView.onPaste = onPaste }

    final class PasteTargetView: UIView {
        var onPaste: (String) -> Void
        private let control: UIPasteControl
        init(onPaste: @escaping (String) -> Void) {
            self.onPaste = onPaste
            let configuration = UIPasteControl.Configuration()
            configuration.displayMode = .iconAndLabel
            configuration.cornerStyle = .capsule
            configuration.baseBackgroundColor = UIColor(Daybook.action)
            configuration.baseForegroundColor = UIColor(Daybook.actionInk)
            control = UIPasteControl(configuration: configuration)
            super.init(frame: .zero)
            pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: [UTType.plainText.identifier])
            control.target = self
            control.accessibilityLabel = "Paste text into Clipboard"
            addSubview(control)
            control.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                control.leadingAnchor.constraint(equalTo: leadingAnchor),
                control.topAnchor.constraint(equalTo: topAnchor),
                control.bottomAnchor.constraint(equalTo: bottomAnchor),
                control.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor)
            ])
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override var intrinsicContentSize: CGSize { control.intrinsicContentSize }
        override func paste(itemProviders: [NSItemProvider]) {
            guard let provider = itemProviders.first(where: { $0.canLoadObject(ofClass: NSString.self) }) else { return }
            provider.loadObject(ofClass: NSString.self) { [weak self] object, _ in
                guard let text = object as? String else { return }
                DispatchQueue.main.async { self?.onPaste(text) }
            }
        }
        override func canPaste(_ itemProviders: [NSItemProvider]) -> Bool {
            itemProviders.contains { $0.canLoadObject(ofClass: NSString.self) }
        }
    }
}


struct RecentlyDeletedView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var deleting: VonoNote?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Recordings stay here until you restore or permanently delete them. They still use device storage.")
                        .font(.caption).foregroundStyle(Daybook.muted)
                    if model.deletedNotes.isEmpty { Text("No deleted recordings.").accessibilityIdentifier("deleted.empty") }
                    ForEach(model.deletedNotes) { note in
                        DaybookCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(note.title).font(.headline)
                                Text(note.createdAt, style: .date).font(.caption)
                                ViewThatFits(in: .horizontal) {
                                    HStack { restore(note); remove(note) }
                                    VStack(alignment: .leading) { restore(note); remove(note) }
                                }
                            }
                        }
                    }
                    if let error = model.errorMessage { Text(error).foregroundStyle(Daybook.coral) }
                }.padding(20)
            }
            .background(Daybook.paper).foregroundStyle(Daybook.ink).tint(Daybook.accent)
            .navigationTitle("Recently deleted").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Delete permanently?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                if let note = deleting { Button("Delete permanently", role: .destructive) { Task { _ = await model.permanentlyDelete(note); deleting = nil } } }
                Button("Keep", role: .cancel) { deleting = nil }
            } message: { Text("The text and source audio will be removed. This cannot be undone.") }
        }
    }
    private func restore(_ note: VonoNote) -> some View {
        Button("Restore") { Task { _ = await model.restoreNote(note) } }.buttonStyle(DaybookButtonStyle()).disabled(model.libraryBusy).accessibilityIdentifier("deleted.restore.\(note.id.uuidString)")
    }
    private func remove(_ note: VonoNote) -> some View {
        Button("Delete permanently", role: .destructive) { deleting = note }.buttonStyle(DaybookButtonStyle()).disabled(model.libraryBusy)
    }
}
