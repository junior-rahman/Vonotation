import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var openSection = "cloud"
    @State private var key = ""
    @State private var removeKey = false
    @State private var showBackup = false
    @State private var recapsConsent = false
    @State private var showPrevious = false

    var body: some View {
        NavigationStack {
            ZStack {
                LinenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        settingsCard("Cloud setup", value: model.hasAPIKey ? "Key saved" : "Needs key", key: "cloud") {
                            Text(model.hasAPIKey ? "A provider key is saved securely in Keychain. Enter a new key to replace it." : "Add your provider key to transcribe and summarize. Offline recordings save first and can be retried later.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            SecureField(model.hasAPIKey ? "•••••••• · replace key" : "Provider API key", text: $key)
                                .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .daybookField().accessibilityIdentifier("settings.apiKey")
                            HStack(spacing: 8) {
                                Button(model.hasAPIKey ? "Replace key" : "Save key") {
                                    model.saveAPIKey(key.trimmingCharacters(in: .whitespacesAndNewlines)); key = ""
                                    Daybook.feedback(model.settings.haptics)
                                }.buttonStyle(DaybookButtonStyle(primary: true)).disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                    .accessibilityIdentifier("settings.apiKey.save")
                                if model.hasAPIKey {
                                    Button("Remove key", role: .destructive) { removeKey = true }
                                        .buttonStyle(DaybookButtonStyle()).accessibilityIdentifier("settings.apiKey.remove")
                                }
                            }
                            labeledField("Provider endpoint", text: $model.settings.endpoint, identifier: "settings.endpoint", url: true)
                        }
                        settingsCard("Models", value: "Transcription & cleanup", key: "models") {
                            Text("Use model IDs supported by your provider. English transcription follows the Android version.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            labeledField("Transcription", text: $model.settings.transcriptionModel, identifier: "settings.model.transcription")
                            if model.product == .vonotation { labeledField("Dictation cleanup", text: $model.settings.cleanupModel, identifier: "settings.model.cleanup") }
                            labeledField(model.product == .notes ? "Notes & calls" : "Quick Thoughts", text: $model.settings.noteModel, identifier: "settings.model.notes")
                            if model.product == .vonotation { Toggle("Clean up dictated text", isOn: $model.settings.aiCleanup).accessibilityIdentifier("settings.cleanup") }
                        }
                        settingsCard("Your Daybook", value: "Capture & feedback", key: "daybook") {
                            if model.product == .vonotation {
                            Toggle(isOn: $model.settings.quickThoughts) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Quick Thoughts")
                                    Text("Shows the Thoughts card and enables the recording shortcut.").font(.caption).foregroundStyle(Daybook.muted)
                                }
                            }.accessibilityIdentifier("settings.quickThoughts")
                            Divider().overlay(Daybook.line)
                            }
                            Toggle("Touch feedback", isOn: $model.settings.haptics).accessibilityIdentifier("settings.haptics")
                                .onChange(of: model.settings.haptics) { _, enabled in Daybook.feedback(enabled) }
                            if model.product == .vonotation {
                            Divider().overlay(Daybook.line)
                            Toggle("Save dictation history", isOn: $model.settings.saveDictations).accessibilityIdentifier("settings.saveDictations")
                            Divider().overlay(Daybook.line)
                            Toggle(isOn: Binding(get: { model.settings.showDailyRecaps }, set: { enabled in
                                if enabled { recapsConsent = true } else { model.settings.showDailyRecaps = false }
                            })) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Daily recaps")
                                    Text("Separate summaries for Dictation and Thoughts.").font(.caption).foregroundStyle(Daybook.muted)
                                }
                            }.accessibilityIdentifier("settings.dailyRecaps")
                            }
                        }
                        if !model.previousNotes.isEmpty {
                            DaybookCard {
                                Text("Previous recordings").font(.headline)
                                Text("Voice notes, calls and translations saved in the combined app stay here. Open them read only or share a copy. Installing Vocading does not move or delete them.").font(.caption).foregroundStyle(Daybook.muted)
                                Button("Open previous recordings") { showPrevious = true }.buttonStyle(DaybookButtonStyle()).accessibilityIdentifier("settings.previous")
                            }
                        }
                        DaybookCard {
                            HStack {
                                Label("Encrypted backup", systemImage: "lock.shield").font(.headline)
                                Spacer()
                                if model.isBackingUp { ProgressView() }
                            }
                            Text("Save a password-protected backup in Files, or restore one. Text and settings are included; audio, Clipboard and provider keys stay on this device.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            Button { showBackup = true } label: { Text("Manage backup").frame(maxWidth: .infinity) }
                                .buttonStyle(DaybookButtonStyle(primary: true)).accessibilityIdentifier("settings.backup")
                        }
                        settingsCard("About & installation", value: "iOS preview", key: "about") {
                            Text(model.product.displayName + " for iOS").font(.subheadline.weight(.semibold))
                            Text(versionText).font(.caption).foregroundStyle(Daybook.muted)
                            Text(model.product == .notes ? "Record voice notes or import call recordings from Files. iOS does not provide access to other apps’ call audio." : "This preview uses native iOS dictation, Quick Thoughts and explicit clipboard paste. iOS does not provide Android’s floating control, background clipboard history, or microphone access from a custom keyboard.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            Text("A GitHub download cannot be installed simply by signing in with an Apple ID. A device build must be signed through Xcode or a compatible signing tool using the owner’s Apple developer identity. No Apple account sign-in is needed inside \(AppProduct.current.displayName).")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            Link(destination: URL(string: "https://github.com/junior-rahman/Vonotation")!) {
                                Label("Source & release instructions", systemImage: "arrow.up.right.square")
                            }.font(.subheadline).frame(minHeight: 44).accessibilityIdentifier("settings.releaseHelp")
                        }
                    }
                    .padding(20).frame(maxWidth: 680).frame(maxWidth: .infinity)
                }
            }
            .foregroundStyle(Daybook.ink).tint(Daybook.action)
            .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Daybook.paper, for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { key = ""; dismiss() }.accessibilityIdentifier("settings.done") } }
            .safeAreaInset(edge: .bottom) { if model.isRecording { ActiveRecordingStrip().padding(.horizontal, 16).background(Daybook.paper) } }
            .sheet(isPresented: $showBackup) { BackupSettingsView().environmentObject(model) }
            .sheet(isPresented: $showPrevious) { PreviousRecordingsView().environmentObject(model) }
            .confirmationDialog("Remove the saved provider key?", isPresented: $removeKey, titleVisibility: .visible) {
                Button("Remove key", role: .destructive) { model.saveAPIKey("") }
                Button("Keep key", role: .cancel) { }
            }
            .alert("Turn on daily recaps?", isPresented: $recapsConsent) {
                Button("Not now", role: .cancel) { }
                Button("Turn on") { model.settings.showDailyRecaps = true }
            } message: {
                Text("Creating a recap sends that day’s saved text to your configured AI provider. Your provider may charge for requests. You choose when to create each recap.")
            }
            .alert(AppProduct.current.displayName, isPresented: Binding(get: { model.errorMessage != nil && !showBackup }, set: { if !$0 && !showBackup { model.acknowledgeError() } })) {
                Button("OK") { model.acknowledgeError() }
            } message: { Text(model.errorMessage ?? "") }
        }
    }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Preview"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "Version \(version) (\(build))"
    }

    private func settingsCard<Content: View>(_ title: String, value: String, key section: String, @ViewBuilder content: () -> Content) -> some View {
        DaybookCard {
            Button {
                Daybook.feedback(model.settings.haptics)
                openSection = openSection == section ? "" : section
            } label: {
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title).font(.headline).foregroundStyle(Daybook.ink)
                        Text(value).font(.caption).foregroundStyle(Daybook.muted)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: openSection == section ? "chevron.up" : "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(Daybook.accent)
                }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("settings.section.\(section)")
            if openSection == section {
                VStack(alignment: .leading, spacing: 14) { content() }.padding(.top, 8)
            }
        }
    }

    private func labeledField(_ title: String, text: Binding<String>, identifier: String, url: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(Daybook.muted)
            TextField(title, text: text).textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(url ? .URL : .asciiCapable).daybookField().accessibilityIdentifier(identifier)
        }
    }
}

private struct DaybookField: ViewModifier {
    func body(content: Content) -> some View {
        content.font(.body).padding(12).frame(minHeight: 46)
            .background(Daybook.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Daybook.fieldOutline, lineWidth: 1))
    }
}
extension View { func daybookField() -> some View { modifier(DaybookField()) } }

struct ActiveRecordingStrip: View {
    @EnvironmentObject private var model: AppModel
    @State private var confirmCancel = false
    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                if model.product == .notes { VocadingSymbol(model.isRecordingPaused ? "pause" : "recording") } else { Image(systemName: "record.circle.fill").foregroundStyle(Daybook.coral) }
                Text(model.isRecordingPaused ? "Recording paused" : "Recording \(model.recordingKind?.title ?? "audio")").font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text(clock(model.recordingDuration)).font(.caption.monospacedDigit())
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { cancelButton; pauseButton; finishButton }.fixedSize(horizontal: true, vertical: false)
                VStack(spacing: 6) {
                    HStack(spacing: 10) { pauseButton; finishButton }
                    cancelButton
                }
            }
        }
        .foregroundStyle(Daybook.ink).padding(14).background(Daybook.soft, in: RoundedRectangle(cornerRadius: 17))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("record.active")
        .accessibilityValue(model.recordingKind?.rawValue ?? "")
        .confirmationDialog("Discard this recording?", isPresented: $confirmCancel, titleVisibility: .visible) {
            Button("Discard recording", role: .destructive) { model.cancelRecording() }
            Button("Keep recording", role: .cancel) { }
        } message: { Text(model.isRecordingPaused ? "The recording is paused. Choose Finish to keep it or Discard recording to remove it." : "Your recording continues until you choose Finish or Discard recording.") }
    }
    private var cancelButton: some View {
        Button("Cancel", role: .destructive) { confirmCancel = true }.buttonStyle(DaybookButtonStyle()).accessibilityIdentifier("record.cancel")
    }
    @ViewBuilder private var pauseButton: some View {
        if model.product == .notes {
            Button { model.toggleRecordingPause() } label: { HStack(spacing: 6) { VocadingSymbol(model.isRecordingPaused ? "play" : "pause", size: 18); Text(model.isRecordingPaused ? "Resume" : "Pause") } }
                .buttonStyle(DaybookButtonStyle()).accessibilityIdentifier("record.pause")
        }
    }
    private var finishButton: some View {
        Button { Daybook.feedback(model.settings.haptics, strong: true); Task { await model.finishRecording() } } label: {
            HStack(spacing: 6) { if model.product == .notes { VocadingSymbol("stop", size: 18) } else { Image(systemName: "stop.fill") }; Text("Finish") }.frame(maxWidth: .infinity)
        }.buttonStyle(DaybookButtonStyle(primary: true)).accessibilityIdentifier("record.finish")
    }
    private func clock(_ seconds: TimeInterval) -> String { String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60) }
}
