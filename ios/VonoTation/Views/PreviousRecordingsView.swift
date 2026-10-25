import SwiftUI

/// Read-only access keeps historical combined-app recordings reachable without migrating or deleting them.
struct PreviousRecordingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ZStack {
                LinenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("These recordings stay in the original app. Open one to read, listen or share a copy. Nothing is moved automatically.")
                            .font(.caption).foregroundStyle(Daybook.muted)
                        ForEach(model.previousNotes) { note in
                            NavigationLink(value: note.id) {
                                DaybookCard {
                                    Text(note.title.isEmpty ? "Saved recording" : note.title).font(.headline)
                                    Text(note.kind.title + " · " + note.createdAt.formatted(date: .abbreviated, time: .shortened))
                                        .font(.caption).foregroundStyle(Daybook.muted)
                                }
                            }.buttonStyle(.plain).accessibilityIdentifier("legacy.note.\(note.id.uuidString)")
                        }
                    }.padding(20).frame(maxWidth: 680).frame(maxWidth: .infinity)
                }
            }
            .foregroundStyle(Daybook.ink).tint(Daybook.accent)
            .navigationTitle("Previous recordings").navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Daybook.paper, for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
            .navigationDestination(for: UUID.self) { id in NoteDetailView(noteID: id) }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("legacy.done") } }
        }
    }
}
