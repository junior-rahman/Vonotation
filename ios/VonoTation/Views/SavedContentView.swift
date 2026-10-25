import SwiftUI

struct SavedContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var showDeleted = false
    @State private var query = ""
    private func matches(_ text: String) -> Bool { query.isEmpty || text.localizedStandardContains(query) }
    var body: some View {
        NavigationStack {
            List {
                if showDeleted {
                    Text("Undo lasts 24 hours. Clipboard items keep their original expiry dates.").font(.caption)
                    ForEach(model.deleted.filter { $0.expiresAt > Date() && matches($0.title + ($0.note?.transcript ?? "")) }) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(item.title).lineLimit(3)
                            Button("Undo delete") { model.restoreDeleted(item.id) }.disabled(model.isBackingUp)
                        }
                    }
                } else {
                    ForEach(model.notes.filter { model.product.supports($0.kind) && matches($0.title + "\n" + $0.transcript + "\n" + $0.rawTranscript + "\n" + $0.summary) }) { note in
                        NavigationLink(value: note.id) {
                            VStack(alignment: .leading) {
                                Text(note.title).lineLimit(2)
                                Text(note.kind.title).font(.caption).foregroundStyle(Daybook.muted)
                            }
                        }
                    }
                    ForEach(model.clips.filter { $0.expiresAt > Date() && matches($0.text) }) { clip in
                        ClipboardTimelineRow(clip: clip)
                    }
                }
            }
            .searchable(text: $query, prompt: showDeleted ? "Filter deleted items" : "Search full saved text")
            .navigationTitle(showDeleted ? "Recently deleted" : "Search")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: UUID.self) { NoteDetailView(noteID: $0) }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { model.refreshExpiry() }
            .tint(Daybook.action)
        }
    }
}
