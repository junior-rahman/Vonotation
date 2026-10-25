import Foundation
import Combine
import AVFoundation
import UIKit
import Network

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var notes: [VonoNote] = []
    @Published private(set) var clips: [ClipItem] = [] { didSet { scheduleExpiry() } }
    @Published private(set) var deleted: [DeletedContent] = [] { didSet { scheduleExpiry() } }
    private var expiryTask: Task<Void, Never>?
    @Published private(set) var recaps: [DayRecap] = []
    @Published var settings = AppSettings() {
        didSet {
            guard isLoaded, !applyingSnapshot else { return }
            if isBackingUp { settings = oldValue; return }
            if Self.normalizedEndpoint(settings.endpoint) != Self.normalizedEndpoint(oldValue.endpoint) {
                // Credentials are also tagged with their endpoint in Keychain: even a failed
                // deletion cannot send the old credential to a newly selected provider.
                hasAPIKey = false
                try? KeychainStore.save("", endpoint: "")
                statusMessage = "Provider changed. Save its API key before processing recordings."
            }
            invalidateRecaps()
            persistSoon()
        }
    }
    @Published private(set) var isLoaded = false
    @Published private(set) var isRecording = false
    @Published private(set) var recordingKind: NoteKind?
    @Published private(set) var recordingDuration: TimeInterval = 0
    @Published private(set) var isProcessing = false
    @Published private(set) var isBackingUp = false
    @Published private(set) var hasAPIKey = false
    @Published var errorMessage: String?
    @Published var statusMessage: String?

    let product: AppProduct
    var previousNotes: [VonoNote] { notes.filter { !product.supports($0.kind) } }
    private let store: LocalStore
    private let cloud: CloudAPI
    private let credentialProvider: ((String) -> String?)?
    private let capture = AudioCapture()
    private var session: RecordingSession?
    private var isStarting = false
    private var isFinalizing = false
    private var applyingSnapshot = false
    private var generation: UInt64 = 0
    private var jobs: [UUID: Task<Void, Never>] = [:]
    private var jobTokens: [UUID: UUID] = [:]
    private var recapJobs: Set<String> = []
    private let network = NWPathMonitor()
    private var clipboardDeferred: [String] = []
    private var lastNetworkSatisfied = false
    private var testFixtures = false
    private var storeWriteError = false

    init(rootURL: URL? = nil, cloud: CloudAPI = CloudAPI(), credentialProvider: ((String) -> String?)? = nil, product: AppProduct = .current) {
        self.product = product
        var root = rootURL ?? LocalStore.defaultRoot
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--ui-testing") {
            root = FileManager.default.temporaryDirectory.appendingPathComponent("VonoTation-UI-\(UUID().uuidString)", isDirectory: true)
            testFixtures = args.contains("--ui-fixtures")
        }
        #endif
        self.store = LocalStore(rootURL: root); self.cloud = cloud; self.credentialProvider = credentialProvider
        capture.onDuration = { [weak self] duration in self?.recordingDuration = duration }
        capture.onInterruption = { [weak self] message in
            Task { @MainActor in await self?.finishInterrupted(message) }
        }
        network.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self = self else { return }
                let restored = path.status == .satisfied && !self.lastNetworkSatisfied
                self.lastNetworkSatisfied = path.status == .satisfied
                if restored { self.resumePending() }
            }
        }
        network.start(queue: DispatchQueue(label: "app.voiceflow.network"))
        Task { await load() }
    }
    deinit { network.cancel(); expiryTask?.cancel() }

    private func load() async {
        do {
            let saved = try await store.load()
            applyingSnapshot = true
            settings = saved.settings; notes = saved.notes; clips = saved.clips; recaps = saved.recaps; deleted = saved.deleted ?? []
            applyingSnapshot = false
            let journal = try await store.loadJournal()
            if let journal = journal, let source = store.audioURL(fileName: journal.audioFileName), FileManager.default.fileExists(atPath: source.path) {
                if let index = notes.firstIndex(where: { $0.id == journal.id }) {
                    notes[index].state = .pending
                    notes[index].mayFilterShortThought = false
                    notes[index].error = "Recording was interrupted. The source was kept; tap Retry to transcribe it."
                } else {
                    var recovered = VonoNote(kind: journal.kind, title: "Recovered recording")
                    recovered.id = journal.id; recovered.createdAt = journal.createdAt; recovered.updatedAt = Date()
                    recovered.audioFileName = journal.audioFileName; recovered.state = .pending
                    recovered.error = "Recording was interrupted. The source was kept; tap Retry to transcribe it."
                    notes.append(recovered)
                }
            }
            notes.sort { $0.createdAt > $1.createdAt }
            #if DEBUG
            if testFixtures && notes.isEmpty { seedUITestFixtures() }
            #endif
            isLoaded = true; refreshExpiry()
            refreshKeyState()
            try await persistNow()
            if let journal = journal { try await store.clearJournal(expectedID: journal.id) }
            // Interrupted microphone captures are deliberately left for explicit Retry.
            if journal == nil { resumePending() }
        } catch {
            applyingSnapshot = false
            isLoaded = false
            errorMessage = "Saved data could not be opened. Your existing files were kept. \(safeMessage(error))"
        }
    }

    func startRecording(kind: NoteKind) async {
        guard product.supports(kind) else { errorMessage = "This recording belongs in the other app."; return }
        guard isLoaded else { errorMessage = "Wait for your saved notes to load."; return }
        guard !isRecording, !isStarting, !isFinalizing, !isBackingUp else { errorMessage = "Finish the current action first."; return }
        guard kind != .call else { errorMessage = "Import a call recording to create call notes. iOS does not share system-call audio with this app."; return }
        guard kind != .thought || settings.quickThoughts else { return }
        isStarting = true
        defer { isStarting = false }
        guard await capture.permission() else { errorMessage = "Microphone access is off. Enable it in iOS Settings to record."; return }
        let fixed = RecordingSession(id: UUID(), kind: kind, startedAt: Date(), audioFileName: UUID().uuidString + ".m4a")
        guard let source = store.audioURL(fileName: fixed.audioFileName) else { return }
        do {
            try await store.prepare()
            try await store.saveJournal(RecordingJournal(id: fixed.id, kind: fixed.kind, createdAt: fixed.startedAt, audioFileName: fixed.audioFileName))
            var note = VonoNote(kind: kind, title: "Recording…")
            note.id = fixed.id; note.createdAt = fixed.startedAt; note.audioFileName = fixed.audioFileName
            notes.insert(note, at: 0)
            try await persistNow()
            try capture.start(at: source)
            session = fixed; recordingKind = fixed.kind; recordingDuration = 0; isRecording = true
            statusMessage = nil
        } catch {
            notes.removeAll { $0.id == fixed.id }
            try? await persistNow()
            try? await store.clearJournal(expectedID: fixed.id)
            try? FileManager.default.removeItem(at: source)
            errorMessage = safeMessage(error)
        }
    }
    func finishRecording() async {
        guard let fixed = session, isRecording, !isFinalizing else { return }
        isFinalizing = true
        defer { isFinalizing = false }
        _ = capture.finish()
        clearRecordingState()
        guard let index = notes.firstIndex(where: { $0.id == fixed.id }) else { return }
        notes[index].title = "Waiting for transcription"
        notes[index].state = .pending; notes[index].updatedAt = Date()
        notes[index].mayFilterShortThought = fixed.kind == .thought
        do {
            try await persistNow()
            try await store.clearJournal(expectedID: fixed.id)
            if let current = notes.first(where: { $0.id == fixed.id }) { await retry(current) }
        } catch { errorMessage = "Your audio is kept, but its queue could not be saved. \(safeMessage(error))" }
    }
    func cancelRecording() {
        guard !isBackingUp, !isFinalizing, let fixed = session else { return }
        isFinalizing = true
        // Persist removal before deleting the source. Failure leaves an orphaned recovery
        // source instead of erasing an audio file referenced by the saved queue.
        _ = capture.finish()
        clearRecordingState()
        notes.removeAll { $0.id == fixed.id }
        Task {
            defer { isFinalizing = false }
            do {
                try await persistNow()
                try await store.clearJournal(expectedID: fixed.id)
                if let source = store.audioURL(fileName: fixed.audioFileName) { try? FileManager.default.removeItem(at: source) }
            } catch { errorMessage = "Cancellation could not be saved. The source was kept for recovery." }
        }
    }
    private func finishInterrupted(_ message: String) async {
        guard let fixed = session, !isFinalizing else { return }
        isFinalizing = true
        defer { isFinalizing = false }
        clearRecordingState()
        if let index = notes.firstIndex(where: { $0.id == fixed.id }) {
            notes[index].title = "Interrupted recording"; notes[index].state = .pending
            notes[index].mayFilterShortThought = false
            notes[index].error = message; notes[index].updatedAt = Date()
        }
        do { try await persistNow(); try await store.clearJournal(expectedID: fixed.id) }
        catch { errorMessage = "The recording source was kept. Its recovery status could not be saved." }
        statusMessage = message
    }
    private func clearRecordingState() {
        isRecording = false; recordingKind = nil; recordingDuration = 0; session = nil
    }

    func importAudio(from url: URL, kind: NoteKind) async {
        guard product.supports(kind) else { errorMessage = "This recording belongs in the other app."; return }
        guard isLoaded, !isBackingUp, !isStarting else { return }
        let allowed = url.startAccessingSecurityScopedResource()
        defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        let ext = url.pathExtension.lowercased()
        guard ["m4a", "mp3", "wav", "mp4", "flac", "ogg", "webm"].contains(ext) else {
            errorMessage = "Choose an M4A, MP3, WAV, MP4, FLAC, OGG or WebM audio recording."; return
        }
        let name = UUID().uuidString + "." + ext
        guard let target = store.audioURL(fileName: name) else { return }
        do {
            let size = (try url.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            guard size > 0, size <= 24_000_000 else { throw VonoError.message("Choose a nonempty recording smaller than 24 MB.") }
            try await store.prepare()
            try FileManager.default.copyItem(at: url, to: target)
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
            var note = VonoNote(kind: kind, title: "Waiting for transcription")
            note.audioFileName = name
            notes.insert(note, at: 0)
            try await persistNow()
            await retry(note)
        } catch { errorMessage = safeMessage(error) }
    }

    func retry(_ note: VonoNote) async {
        guard product.supports(note.kind) else { return }
        guard isLoaded, !isBackingUp, !isStarting, jobs[note.id] == nil, session?.id != note.id,
              let current = notes.first(where: { $0.id == note.id }), product.supports(current.kind), current.revision == note.revision else { return }
        guard let key = providerKey() else {
            if let i = notes.firstIndex(where: { $0.id == note.id }) {
                notes[i].state = .pending; notes[i].error = "Audio saved. Add your provider API key in Settings, then tap Retry."
                persistSoon()
            }
            statusMessage = "Your recording is saved on this device. Add an API key to transcribe it."
            return
        }
        let provider = settings
        let revision = current.revision, token = UUID()
        let task = Task { [weak self] in
            guard let self = self else { return }
            await self.process(id: current.id, revision: revision, token: token, provider: provider, key: key)
        }
        jobs[note.id] = task; jobTokens[note.id] = token; isProcessing = true
    }

    private func process(id: UUID, revision: Int, token: UUID, provider: AppSettings, key: String) async {
        defer {
            if jobTokens[id] == token { jobs.removeValue(forKey: id); jobTokens.removeValue(forKey: id) }
            isProcessing = !jobs.isEmpty || !recapJobs.isEmpty
        }
        guard let index = notes.firstIndex(where: { $0.id == id }), canApply(id, revision) else { return }
        notes[index].state = .processing; notes[index].error = nil
        do {
            try await persistNow()
            guard canApply(id, revision), let current = notes.first(where: { $0.id == id }) else { return }
            var source = current.transcript
            if source.isEmpty && current.userEditedTranscript {
                guard let edited = notes.firstIndex(where: { $0.id == id }) else { return }
                notes[edited].state = .ready; notes[edited].error = nil; notes[edited].needsSummary = false
                try await persistNow(); return
            }
            if source.isEmpty {
                guard let name = current.audioFileName, let audio = store.audioURL(fileName: name),
                      FileManager.default.fileExists(atPath: audio.path) else { throw VonoError.message("The recording source is unavailable. Your saved text was kept.") }
                source = try await cloud.transcribe(audioURL: audio, settings: provider, key: key)
                try Task.checkCancellation()
                guard canApply(id, revision), let i = notes.firstIndex(where: { $0.id == id }) else { return }
                if notes[i].kind == .thought && notes[i].mayFilterShortThought && !NotePolicy.thoughtEligible(source) {
                    let audioName = notes[i].audioFileName
                    notes.remove(at: i); invalidateRecaps()
                    try await persistNow()
                    if let name = audioName, let audio = store.audioURL(fileName: name) { try? FileManager.default.removeItem(at: audio) }
                    statusMessage = "This was shorter than two lines, so it was not saved as a Thought."
                    return
                }
                notes[i].rawTranscript = source; notes[i].transcript = source
                if !notes[i].userEditedTitle { notes[i].title = NotePolicy.title(source, fallback: notes[i].kind.title) }
                notes[i].updatedAt = Date()
                try await persistNow() // The complete transcript is durable before optional AI processing.
                if provider.aiCleanup {
                    do {
                        let cleaned = try await cloud.clean(source, settings: provider, key: key)
                        guard canApply(id, revision), let edited = notes.firstIndex(where: { $0.id == id }) else { return }
                        notes[edited].transcript = cleaned
                        try await persistNow()
                    } catch is CancellationError { return }
                    catch { statusMessage = "Transcript saved. AI cleanup was unavailable. The original words were kept." }
                }
            }
            guard canApply(id, revision), let i = notes.firstIndex(where: { $0.id == id }) else { return }
            let kind = notes[i].kind
            if kind == .thought || kind == .voiceNote || kind == .call {
                do {
                    let organized = try await cloud.summarize(source, settings: provider, key: key)
                    guard canApply(id, revision), let target = notes.firstIndex(where: { $0.id == id }) else { return }
                    notes[target].summary = organized.summary
                    if !notes[target].userEditedTitle { notes[target].title = organized.title }
                    notes[target].needsSummary = false
                } catch is CancellationError { return }
                catch {
                    guard canApply(id, revision), let target = notes.firstIndex(where: { $0.id == id }) else { return }
                    notes[target].summary = NotePolicy.localGist(source)
                    notes[target].needsSummary = true
                    notes[target].state = .pending
                    notes[target].error = "Transcript saved. A concise summary is waiting; tap Retry."
                    try await persistNow(); return
                }
            } else if kind == .translation {
                let translated = try await cloud.translate(source, to: provider.translationLanguage, settings: provider, key: key)
                guard canApply(id, revision), let target = notes.firstIndex(where: { $0.id == id }) else { return }
                notes[target].translation = translated
            }
            guard canApply(id, revision), let final = notes.firstIndex(where: { $0.id == id }) else { return }
            notes[final].state = .ready; notes[final].error = nil; notes[final].updatedAt = Date()
            invalidateRecaps()
            try await persistNow()
            if kind == .dictation {
                guard canApply(id, revision), let completed = notes.first(where: { $0.id == id }) else { return }
                let text = completed.transcript
                copyText(text)
                statusMessage = "Dictation copied. Paste it into your input field."
                if !settings.saveDictations {
                    let name = completed.audioFileName
                    notes.removeAll { $0.id == id }
                    try await persistNow()
                    if let name = name, let audio = store.audioURL(fileName: name) { try? FileManager.default.removeItem(at: audio) }
                }
            }
        } catch is CancellationError { return }
        catch {
            guard canApply(id, revision), let index = notes.firstIndex(where: { $0.id == id }) else { return }
            let offline = Self.offline(error)
            notes[index].state = offline ? .pending : .failed
            notes[index].error = offline ? "Audio saved offline. Processing resumes when a connection returns, or tap Retry." : safeMessage(error)
            notes[index].updatedAt = Date()
            try? await persistNow()
        }
    }

    func updateNote(_ note: VonoNote, title: String, transcript: String) {
        guard product.supports(note.kind) else { return }
        guard isLoaded, !isBackingUp, !isStarting, !isFinalizing, session?.id != note.id, let i = notes.firstIndex(where: { $0.id == note.id }), product.supports(notes[i].kind) else { return }
        jobs[note.id]?.cancel(); jobs.removeValue(forKey: note.id); jobTokens.removeValue(forKey: note.id)
        if title != notes[i].title { notes[i].userEditedTitle = true }
        if transcript != notes[i].transcript { notes[i].userEditedTranscript = true }
        notes[i].title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
        if notes[i].title.isEmpty { notes[i].title = NotePolicy.title(transcript) }
        notes[i].transcript = transcript
        notes[i].summary = ""; notes[i].translation = ""
        let needsProcessing = !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && notes[i].kind != .dictation
        notes[i].needsSummary = needsProcessing && notes[i].kind != .translation
        notes[i].mayFilterShortThought = false
        notes[i].revision += 1; notes[i].updatedAt = Date()
        notes[i].state = needsProcessing ? .pending : .ready
        notes[i].error = needsProcessing ? "Edited transcript saved. Tap Retry to update its summary or translation." : nil
        isProcessing = !jobs.isEmpty || !recapJobs.isEmpty
        invalidateRecaps(); persistSoon()
    }
    @discardableResult
    func deleteNote(_ note: VonoNote) -> Bool {
        guard let saved = notes.first(where: { $0.id == note.id }), product.supports(saved.kind) else { return false }
        guard isLoaded else {
            errorMessage = "Wait for your saved notes to load before deleting."
            return false
        }
        guard !isBackingUp else {
            errorMessage = "Wait for backup or restore to finish before deleting this note."
            return false
        }
        guard !isStarting, !isFinalizing else {
            errorMessage = "Wait for the current recording action to finish before deleting this note."
            return false
        }
        if session?.id == note.id { cancelRecording(); return true }
        jobs[note.id]?.cancel(); jobs.removeValue(forKey: note.id); jobTokens.removeValue(forKey: note.id)
        if product == .vonotation {
            var recoverable = saved
            recoverable.revision += 1
            if recoverable.state == .processing { recoverable.state = .pending }
            deleted.insert(DeletedContent(note: recoverable), at: 0)
            statusMessage = "Deleted. Undo is available for 24 hours in Settings → Recently deleted."
        }
        let source = notes.first(where: { $0.id == note.id })?.audioFileName
        notes.removeAll { $0.id == note.id }; invalidateRecaps()
        isProcessing = !jobs.isEmpty || !recapJobs.isEmpty
        Task {
            do {
                try await persistNow()
                if product == .notes, let name = source, let source = store.audioURL(fileName: name) { try? FileManager.default.removeItem(at: source) }
            } catch { errorMessage = "The deletion could not be saved. The source file was kept." }
        }
        return true
    }
    func audioURL(for note: VonoNote) -> URL? {
        guard let name = note.audioFileName, let url = store.audioURL(fileName: name), FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    func captureClipboard(_ text: String) {
        guard product.supportsClipboard else { return }
        guard isLoaded else { return }
        if isBackingUp { clipboardDeferred.append(text); return }
        refreshExpiry()
        guard ClipboardPolicy.shouldAppend(text, to: clips) else { return }
        clips.insert(ClipItem(text: text), at: 0); persistSoon()
    }
    func copyText(_ text: String) {
        guard !text.isEmpty else { return }
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: text]], options: [.localOnly: true])
        captureClipboard(text)
    }
    func deleteClip(_ clip: ClipItem) {
        guard product.supportsClipboard else { return }
        guard !isBackingUp else { return }
        guard let current = clips.first(where: { $0.id == clip.id }), current.expiresAt > Date() else { refreshExpiry(); return }
        deleted.insert(DeletedContent(clip: current), at: 0)
        clips.removeAll { $0.id == clip.id }; persistSoon()
        statusMessage = "Deleted. Undo is available in Settings → Recently deleted."
    }
    func copyClip(_ id: UUID) {
        guard let clip = clips.first(where: { $0.id == id }), clip.expiresAt > Date() else { refreshExpiry(); return }
        // Copy is not a new capture and must not extend the original expiry.
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: clip.text]], options: [.localOnly: true, .expirationDate: clip.expiresAt])
    }
    func restoreDeleted(_ id: UUID) {
        guard isLoaded, !isBackingUp, let item = deleted.first(where: { $0.id == id }), item.expiresAt > Date() else { refreshExpiry(); return }
        if var note = item.note, !notes.contains(where: { $0.id == note.id }) {
            note.revision += 1; note.mayFilterShortThought = false
            notes.append(note); notes.sort { $0.createdAt > $1.createdAt }; invalidateRecaps()
        }
        if let clip = item.clip, !clips.contains(where: { $0.id == clip.id }) {
            clips.append(clip); clips.sort { $0.createdAt > $1.createdAt }
        }
        deleted.removeAll { $0.id == id }; persistSoon()
        statusMessage = "Restored."
    }
    private func scheduleExpiry() {
        expiryTask?.cancel()
        guard isLoaded, let next = (clips.map(\.expiresAt) + deleted.map(\.expiresAt)).min() else { return }
        let delay = max(0.05, min(3600, next.timeIntervalSinceNow))
        expiryTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
            self?.refreshExpiry()
        }
    }
    func refreshExpiry() {
        guard isLoaded, !isBackingUp else { return }
        let now = Date(), retained = ClipboardPolicy.retained(clips)
        let expired = deleted.filter { $0.expiresAt <= now }
        if retained != clips || !expired.isEmpty {
            clips = retained; deleted.removeAll { $0.expiresAt <= now }
            Task {
                do {
                    try await persistNow()
                    for item in expired {
                        if let name = item.note?.audioFileName,
                           !notes.contains(where: { $0.audioFileName == name }),
                           !deleted.contains(where: { $0.note?.audioFileName == name }),
                           let url = store.audioURL(fileName: name) { try? FileManager.default.removeItem(at: url) }
                    }
                } catch { errorMessage = "Expiry could not be saved. Source audio was kept." }
            }
        }
        scheduleExpiry()
    }
    func saveAPIKey(_ key: String) {
        guard isLoaded, !isBackingUp else { return }
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty || BackupPayload.validEndpoint(settings.endpoint) else { errorMessage = "Use a valid HTTPS API endpoint before saving its key."; return }
        do {
            try KeychainStore.save(trimmed, endpoint: Self.normalizedEndpoint(settings.endpoint))
            refreshKeyState()
            statusMessage = trimmed.isEmpty ? "API key removed." : "API key saved securely on this device."
            if hasAPIKey { resumePending() }
        } catch { errorMessage = safeMessage(error) }
    }
    private func providerKey() -> String? {
        #if DEBUG
        if testFixtures { return nil }
        #endif
        if let credentialProvider = credentialProvider { return credentialProvider(Self.normalizedEndpoint(settings.endpoint)) }
        guard let credentials = try? KeychainStore.read(), credentials.endpoint == Self.normalizedEndpoint(settings.endpoint), !credentials.key.isEmpty else { return nil }
        return credentials.key
    }
    private func refreshKeyState() { hasAPIKey = providerKey() != nil }

    func generateRecap(for kind: NoteKind, on day: Date) async {
        guard product.supports(kind) else { return }
        guard isLoaded, settings.showDailyRecaps, !isBackingUp, let key = providerKey() else {
            statusMessage = "Add an API key and enable daily recaps in Settings."; return
        }
        let id = NotePolicy.dayID(day, kind: kind)
        guard !recapJobs.contains(id) else { return }
        let provider = settings
        let text = recapSource(kind: kind, day: day)
        guard !text.isEmpty else { return }
        let fingerprint = recapFingerprint(text, provider: provider)
        if recaps.contains(where: { $0.id == id && $0.fingerprint == fingerprint }) { return }
        recapJobs.insert(id); isProcessing = true
        defer { recapJobs.remove(id); isProcessing = !jobs.isEmpty || !recapJobs.isEmpty }
        do {
            let textResult = try await cloud.recap(text, settings: provider, key: key)
            guard recapFingerprint(recapSource(kind: kind, day: day), provider: settings) == fingerprint,
                  settings.showDailyRecaps, provider == settings else { return }
            recaps.removeAll { $0.id == id }
            recaps.append(DayRecap(id: id, kind: kind, day: NotePolicy.dayStart(day), text: textResult, fingerprint: fingerprint))
            try await persistNow()
        } catch { errorMessage = "Daily recap was unavailable. Your notes are unchanged. \(safeMessage(error))" }
    }
    func recap(for kind: NoteKind, on day: Date) -> String? {
        guard settings.showDailyRecaps else { return nil }
        let id = NotePolicy.dayID(day, kind: kind), text = recapSource(kind: kind, day: day)
        let fingerprint = recapFingerprint(text, provider: settings)
        return recaps.first(where: { $0.id == id && $0.fingerprint == fingerprint })?.text
    }
    private func recapSource(kind: NoteKind, day: Date) -> String {
        notes.filter { $0.kind == kind && Calendar.current.isDate($0.createdAt, inSameDayAs: day) && !$0.transcript.isEmpty }
            .sorted { $0.createdAt < $1.createdAt }.map(\.transcript).joined(separator: "\n\n")
    }
    private func recapFingerprint(_ text: String, provider: AppSettings) -> String {
        NotePolicy.fingerprint(text + "\n" + provider.endpoint + "\n" + provider.noteModel + "\n" + TimeZone.current.identifier)
    }
    private func invalidateRecaps() { recaps.removeAll() }

    func exportBackup(password: String) async throws -> URL {
        guard isLoaded, !isRecording, !isStarting, !isFinalizing, !isProcessing, !isBackingUp else { throw VonoError.message("Finish recording and processing before creating a backup.") }
        isBackingUp = true
        defer { completeBackup() }
        let completed = notes.filter { product.supports($0.kind) && !$0.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.map { note -> VonoNote in
            var saved = note; saved.audioFileName = nil; saved.error = nil
            saved.state = .ready; saved.mayFilterShortThought = false; return saved
        }
        let payload = try BackupPayload(product: product, notes: completed, settings: settings).validated(for: product)
        let json = try LocalStore.encoder().encode(payload)
        let data = try await Task.detached(priority: .userInitiated) { try BackupArchive.encrypt(json: json, password: password) }.value
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("VonoTation-\(UUID().uuidString).vonobackup")
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        statusMessage = "Encrypted backup ready. It contains saved text and settings; recordings and API keys stay on this device."
        return url
    }
    func importBackup(from url: URL, password: String) async throws {
        guard isLoaded, !isRecording, !isStarting, !isFinalizing, !isProcessing, !isBackingUp else { throw VonoError.message("Finish recording and processing before restoring a backup.") }
        isBackingUp = true
        defer { completeBackup() }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= BackupArchive.maximumBytes + 64 else { throw VonoError.message("Choose a complete iOS backup under 8 MB.") }
        let archive = try Data(contentsOf: url, options: .mappedIfSafe)
        let receivingProduct = product
        let imported = try await Task.detached(priority: .userInitiated) {
            let plain = try BackupArchive.decrypt(archive: archive, password: password)
            return try LocalStore.decoder().decode(BackupPayload.self, from: plain).validated(for: receivingProduct)
        }.value
        var candidate = AppSnapshot()
        // Inactive legacy recordings are restored unchanged below. Feeding them
        // through the active merge could clone a conflicting source with a new ID.
        candidate.notes = BackupMergePolicy.merge(imported: imported.notes, local: notes.filter { product.supports($0.kind) })
        // Restoring active-product text must not erase recordings retained from the combined app.
        for legacy in notes where !product.supports(legacy.kind) {
            candidate.notes.removeAll { $0.id == legacy.id }
            candidate.notes.append(legacy)
        }
        candidate.notes.sort { $0.createdAt > $1.createdAt }
        candidate.deleted = deleted; candidate.clips = ClipboardPolicy.retained(clips); candidate.settings = imported.settings; candidate.recaps = []
        let endpointChanged = Self.normalizedEndpoint(settings.endpoint) != Self.normalizedEndpoint(imported.settings.endpoint)
        // One atomic commit precedes publishing restored state. Failure keeps current UI/store.
        generation += 1
        try await store.save(candidate, generation: generation)
        applyingSnapshot = true
        notes = candidate.notes; clips = candidate.clips; settings = candidate.settings; recaps = []
        applyingSnapshot = false
        if endpointChanged { try? KeychainStore.save("", endpoint: "") }
        refreshKeyState()
        statusMessage = "Backup restored. Clipboard, API credentials for the same provider and source recordings were kept."
    }
    private func completeBackup() {
        isBackingUp = false
        let delayed = clipboardDeferred; clipboardDeferred.removeAll()
        delayed.forEach { captureClipboard($0) }
        refreshExpiry()
    }

    func acknowledgeError() { errorMessage = nil }
    func acknowledgeStatus() { statusMessage = nil }
    private func canApply(_ id: UUID, _ revision: Int) -> Bool {
        !Task.isCancelled && ProcessingPolicy.canApply(id: id, revision: revision, to: notes)
    }
    private func resumePending() {
        guard isLoaded, !isBackingUp, !isStarting, !isFinalizing, providerKey() != nil else { return }
        let waiting = notes.filter { product.supports($0.kind) && $0.state == .pending && $0.id != session?.id && !($0.error?.hasPrefix("Recording was interrupted") ?? false) }
        for note in waiting { Task { await retry(note) } }
    }
    private func snapshot() -> AppSnapshot { AppSnapshot(notes: notes, clips: clips, recaps: recaps, settings: settings, deleted: deleted) }
    private func persistNow() async throws {
        guard isLoaded else { return }
        generation += 1
        let revision = generation, value = snapshot()
        do { try await store.save(value, generation: revision); storeWriteError = false }
        catch { storeWriteError = true; throw error }
    }
    private func persistSoon() {
        guard isLoaded else { return }
        generation += 1
        let revision = generation, value = snapshot()
        Task {
            do { try await store.save(value, generation: revision); storeWriteError = false }
            catch { storeWriteError = true; errorMessage = "Your latest change could not be saved. Existing files were kept. \(safeMessage(error))" }
        }
    }
    private func safeMessage(_ error: Error) -> String {
        if let known = error as? LocalizedError, let description = known.errorDescription { return description }
        if Self.offline(error) { return "No connection. Your recording stays saved for retry." }
        return "The action could not finish. Your existing notes and recordings were kept."
    }
    private static func offline(_ error: Error) -> Bool {
        if let error = error as? CloudError {
            switch error { case .offline, .timeout, .connection: return true; default: break }
        }
        let e = error as NSError
        return e.domain == NSURLErrorDomain && [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed].contains(e.code)
    }
    static func normalizedEndpoint(_ text: String) -> String {
        guard var url = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) else { return text }
        url.scheme = url.scheme?.lowercased(); url.host = url.host?.lowercased()
        if url.port == 443 { url.port = nil }
        while url.path.hasSuffix("/") { url.path.removeLast() }
        return url.string ?? text
    }

    #if DEBUG
    private func seedUITestFixtures() {
        var today = VonoNote(kind: .dictation, title: "UI fixture dictation")
        today.transcript = "UI fixture original transcript. This note confirms the native saved timeline, editing and complete text handling."
        today.rawTranscript = today.transcript; today.state = .ready
        var thought = VonoNote(kind: .thought, title: "UI fixture thought")
        thought.transcript = "UI fixture original transcript. I want to organize a useful thought for tomorrow and keep the original words available as its source."
        thought.rawTranscript = thought.transcript; thought.summary = "A concise fixture summary."; thought.state = .ready
        var yesterday = today; yesterday.id = UUID(); yesterday.title = "Yesterday fixture"
        yesterday.createdAt = Calendar.current.date(byAdding: .day, value: -1, to: Date()) ?? Date().addingTimeInterval(-86400)
        yesterday.updatedAt = yesterday.createdAt
        var voice = today; voice.id = UUID(); voice.kind = .voiceNote; voice.title = "UI fixture voice note"
        var call = voice; call.id = UUID(); call.kind = .call; call.title = "UI fixture call"
        notes = product == .notes ? [voice, call] : [thought, today, yesterday, voice, call]
        clips = product.supportsClipboard ? [ClipItem(text: String(repeating: "A long clipboard fixture keeps every word. ", count: 80) + "END OF UI CLIPBOARD FIXTURE")] : []
    }
    #endif
}
