import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct BackupSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var passwordAgain = ""
    @State private var importPassword = ""
    @State private var importing = false
    @State private var selectedFile: URL?
    @State private var confirmRestore = false
    @State private var shareFile: ShareFile?
    @State private var localError: String?
    @State private var localStatus: String?

    var body: some View {
        NavigationStack {
            ZStack {
                LinenBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Encrypted backup").font(.title2.weight(.bold))
                        Text("Save your text and settings to Files. Choose a strong password and keep it somewhere safe; it cannot be recovered for you.")
                            .font(.subheadline).foregroundStyle(Daybook.muted)
                        DaybookCard {
                            Text("Create a backup").font(.headline)
                            Text("Includes saved notes, dictations and settings. Recordings, Clipboard, cached recaps and API keys are excluded.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            SecureField("Backup password · at least 12 characters", text: $password)
                                .textContentType(.newPassword).daybookField().accessibilityIdentifier("settings.backup.password")
                            SecureField("Confirm password", text: $passwordAgain)
                                .textContentType(.newPassword).daybookField().accessibilityIdentifier("settings.backup.passwordConfirm")
                            if !passwordAgain.isEmpty && password != passwordAgain {
                                Text("The passwords do not match.").font(.caption).foregroundStyle(Daybook.coral)
                            }
                            Button {
                                Task {
                                    do {
                                        let url = try await model.exportBackup(password: password)
                                        password = ""; passwordAgain = ""
                                        shareFile = ShareFile(url: url)
                                    } catch { localError = error.localizedDescription }
                                }
                            } label: {
                                HStack {
                                    if model.isBackingUp { ProgressView().tint(Daybook.actionInk) }
                                    Label("Export encrypted backup", systemImage: "square.and.arrow.up")
                                }.frame(maxWidth: .infinity)
                            }
                            .buttonStyle(DaybookButtonStyle(primary: true))
                            .disabled(password.count < 12 || password != passwordAgain || model.isBackingUp || model.isRecording || model.isProcessing)
                            .accessibilityIdentifier("settings.backup.export")
                        }
                        DaybookCard {
                            Text("Restore from Files").font(.headline)
                            Text("Restore replaces saved text and settings after your confirmation. Your current Clipboard, provider key and pending source recordings are kept. iOS backups use their own encrypted format and cannot restore an Android backup.")
                                .font(.caption).foregroundStyle(Daybook.muted)
                            Button { importing = true } label: { Label("Choose backup file", systemImage: "folder").frame(maxWidth: .infinity) }
                                .buttonStyle(DaybookButtonStyle()).disabled(model.isBackingUp || model.isRecording || model.isProcessing)
                                .accessibilityIdentifier("settings.backup.import")
                            if let selectedFile {
                                Text(selectedFile.lastPathComponent).font(.caption).foregroundStyle(Daybook.accent).lineLimit(2)
                                SecureField("Backup password", text: $importPassword)
                                    .textContentType(.password).daybookField().accessibilityIdentifier("settings.backup.importPassword")
                                Button("Review restore") { confirmRestore = true }
                                    .buttonStyle(DaybookButtonStyle(primary: true))
                                    .disabled(importPassword.isEmpty || model.isBackingUp || model.isRecording || model.isProcessing)
                                    .accessibilityIdentifier("settings.backup.restoreReview")
                            }
                        }
                        if model.isRecording || model.isProcessing {
                            Label("Finish the active recording or transcription before exporting or restoring.", systemImage: "clock")
                                .font(.caption).foregroundStyle(Daybook.muted)
                        }
                        if let localStatus {
                            Label(localStatus, systemImage: "checkmark.circle").font(.subheadline).foregroundStyle(Daybook.accent)
                                .accessibilityIdentifier("settings.backup.status")
                        }
                        Text("Files lets you choose available storage providers. This iOS preview does not claim a Google Drive account connection.")
                            .font(.caption).foregroundStyle(Daybook.muted)
                    }
                    .padding(22).frame(maxWidth: 680).frame(maxWidth: .infinity)
                }
            }
            .foregroundStyle(Daybook.ink).tint(Daybook.accent)
            .navigationTitle("Backup").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { clearSecrets(); dismiss() }.accessibilityIdentifier("settings.backup.done") } }
            .safeAreaInset(edge: .bottom) { if model.isRecording { ActiveRecordingStrip().padding(.horizontal, 16).background(Daybook.paper) } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls): selectedFile = urls.first; importPassword = ""
                case .failure(let error): localError = error.localizedDescription
                }
            }
            .sheet(item: $shareFile) { file in
                ActivityShareView(items: [file.url]) { completed in
                    if completed { localStatus = "Backup handed to your chosen destination." }
                }
            }
            .confirmationDialog("Replace saved text and settings?", isPresented: $confirmRestore, titleVisibility: .visible) {
                Button("Restore and replace saved text", role: .destructive) {
                    guard let selectedFile else { return }
                    Task {
                        do {
                            try await model.importBackup(from: selectedFile, password: importPassword)
                            importPassword = ""; self.selectedFile = nil
                            localStatus = "Backup restored. Your Clipboard, provider key and pending audio were kept."
                        } catch { importPassword = ""; localError = error.localizedDescription }
                    }
                }
                Button("Keep current data", role: .cancel) { }
            } message: {
                Text("The backup password is checked before any data changes. Current Clipboard, provider key and pending source recordings are kept.")
            }
            .alert("Backup could not finish", isPresented: Binding(get: { localError != nil }, set: { if !$0 { localError = nil } })) {
                Button("OK") { localError = nil }
            } message: { Text(localError ?? "") }
            .onDisappear { clearSecrets() }
        }
    }

    private func clearSecrets() { password = ""; passwordAgain = ""; importPassword = "" }
}

private struct ShareFile: Identifiable { let id = UUID(); let url: URL }

struct ActivityShareView: UIViewControllerRepresentable {
    let items: [Any]
    var completion: ((Bool) -> Void)?
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, completed, _, _ in completion?(completed) }
        return controller
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) { }
}
