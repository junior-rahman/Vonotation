import Foundation

actor LocalStore {
    nonisolated let rootURL: URL
    nonisolated var audioDirectory: URL { rootURL.appendingPathComponent("Audio", isDirectory: true) }
    private var savedGeneration: UInt64 = 0
    init(rootURL: URL) { self.rootURL = rootURL }
    static var defaultRoot: URL {
        (FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent(AppProduct.current.storageDirectory, isDirectory: true)
    }
    func prepare() throws {
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: audioDirectory, withIntermediateDirectories: true)
        var resource = rootURL
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try resource.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: rootURL.path)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: audioDirectory.path)
        #endif
    }
    func load() throws -> AppSnapshot {
        try prepare()
        let url = rootURL.appendingPathComponent("daybook.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return AppSnapshot() }
        // A corrupt store must not be silently replaced by an empty one.
        let data = try Data(contentsOf: url)
        var value = try Self.decoder().decode(AppSnapshot.self, from: data)
        guard value.formatVersion == 1 else { throw VonoError.message("This saved data needs a newer version of VonoTation.") }
        value.clips = ClipboardPolicy.retained(value.clips)
        for index in value.notes.indices where value.notes[index].state == .processing {
            value.notes[index].state = .pending
            value.notes[index].error = "Processing was interrupted. Tap Retry to continue."
        }
        value.notes.sort { $0.createdAt > $1.createdAt }
        return value
    }
    func save(_ snapshot: AppSnapshot, generation: UInt64) throws {
        guard generation >= savedGeneration else { return }
        try prepare()
        let data = try Self.encoder().encode(snapshot)
        try data.write(to: rootURL.appendingPathComponent("daybook.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        savedGeneration = generation
    }
    nonisolated func audioURL(fileName: String) -> URL? {
        guard !fileName.isEmpty, fileName == URL(fileURLWithPath: fileName).lastPathComponent,
              !fileName.hasPrefix("."), !fileName.contains("/"), !fileName.contains("\\") else { return nil }
        return audioDirectory.appendingPathComponent(fileName)
    }
    func saveJournal(_ journal: RecordingJournal) throws {
        try prepare()
        try Self.encoder().encode(journal).write(to: rootURL.appendingPathComponent("recording.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func loadJournal() throws -> RecordingJournal? {
        let source = rootURL.appendingPathComponent("recording.json")
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let journal = try Self.decoder().decode(RecordingJournal.self, from: Data(contentsOf: source))
        guard audioURL(fileName: journal.audioFileName) != nil else { throw VonoError.message("The interrupted recording has an invalid source path.") }
        return journal
    }
    func clearJournal(expectedID: UUID) throws {
        guard let current = try loadJournal(), current.id == expectedID else { return }
        let source = rootURL.appendingPathComponent("recording.json")
        if FileManager.default.fileExists(atPath: source.path) { try FileManager.default.removeItem(at: source) }
    }
    static func encoder() -> JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; e.outputFormatting = [.sortedKeys]; return e }
    static func decoder() -> JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }
}
