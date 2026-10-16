import XCTest
@testable import VonoTation

/// Real model/persistence/network tasks, with the HTTP boundary held by URLProtocol.
/// No microphone, real provider or Keychain credential is involved in these tests.
@MainActor
final class AppModelRaceTests: XCTestCase {
    private var directory: URL!
    private var transport: URLSession!
    private var model: AppModel?

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("VonoTation-Race-\(UUID().uuidString)", isDirectory: true)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HeldModelProtocol.self]
        transport = URLSession(configuration: configuration)
        HeldModelProtocol.configure(nil)
    }

    override func tearDownWithError() throws {
        transport.invalidateAndCancel()
        model = nil
        HeldModelProtocol.configure(nil)
        try? FileManager.default.removeItem(at: directory)
        directory = nil
    }

    private func loadedModel(note: VonoNote) async throws -> AppModel {
        let store = LocalStore(rootURL: directory)
        try await store.prepare()
        try Data("dummy audio request body".utf8).write(to: XCTUnwrap(store.audioURL(fileName: "source.m4a")))
        var settings = AppSettings(); settings.aiCleanup = false
        settings.endpoint = "https://model-test.invalid/openai/v1"
        try await store.save(AppSnapshot(notes: [note], settings: settings), generation: 1)
        let value = AppModel(rootURL: directory, cloud: CloudAPI(session: transport), credentialProvider: { endpoint in
            endpoint == "https://model-test.invalid/openai/v1" ? "dummy-test-only-key" : nil
        })
        model = value
        for _ in 0..<200 {
            if value.isLoaded { return value }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw VonoError.message("The isolated model did not load.")
    }

    func testLateTranscriptionCannotResurrectDeletedNoteOrWriteIntoNewImport() async throws {
        let requested = expectation(description: "Original transcription reached held HTTP boundary")
        let gate = ModelRequestGate(firstRequest: requested)
        HeldModelProtocol.configure { gate.hold($0) }
        let original = VonoNote(kind: .dictation, title: "Original queued note", audioFileName: "source.m4a", state: .failed)
        let value = try await loadedModel(note: original)
        await value.retry(original)
        await fulfillment(of: [requested], timeout: 5)
        XCTAssertTrue(value.deleteNote(original), "The original deletion must be accepted while its provider request is held.")

        let newAudio = directory.appendingPathComponent("new-import.m4a")
        try Data("different source bytes".utf8).write(to: newAudio)
        await value.importAudio(from: newAudio, kind: .dictation)
        let inserted = try XCTUnwrap(value.notes.first)
        XCTAssertNotEqual(inserted.id, original.id)
        gate.respondFirst(text: "A late response from the deleted original recording.")
        try await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertFalse(value.notes.contains { $0.id == original.id })
        let current = try XCTUnwrap(value.notes.first(where: { $0.id == inserted.id }))
        XCTAssertTrue(current.rawTranscript.isEmpty)
        XCTAssertTrue(current.transcript.isEmpty)
        value.deleteNote(current)
    }

    func testUserEditWinsOverHeldTranscriptionAndRawSourceIsNotRewritten() async throws {
        let requested = expectation(description: "Transcription is waiting")
        let gate = ModelRequestGate(firstRequest: requested)
        HeldModelProtocol.configure { gate.hold($0) }
        let original = VonoNote(kind: .dictation, title: "Original queued note", audioFileName: "source.m4a", state: .failed)
        let value = try await loadedModel(note: original)
        await value.retry(original)
        await fulfillment(of: [requested], timeout: 5)
        let userText = "The user changed the middle of this transcript."
        value.updateNote(original, title: "User title", transcript: userText)
        gate.respondFirst(text: "The older server response must not replace the edit.")
        try await Task.sleep(nanoseconds: 150_000_000)
        let edited = try XCTUnwrap(value.notes.first(where: { $0.id == original.id }))
        XCTAssertEqual(edited.title, "User title")
        XCTAssertEqual(edited.transcript, userText)
        XCTAssertEqual(edited.rawTranscript, original.rawTranscript)
        XCTAssertEqual(edited.revision, 1)
        XCTAssertEqual(edited.state, .ready)
    }
}

private final class ModelRequestGate {
    private let lock = NSLock()
    private let firstRequest: XCTestExpectation
    private var requests: [HeldModelProtocol] = []
    init(firstRequest: XCTestExpectation) { self.firstRequest = firstRequest }
    func hold(_ request: HeldModelProtocol) {
        lock.lock()
        requests.append(request)
        let first = requests.count == 1
        lock.unlock()
        if first { firstRequest.fulfill() }
    }
    func respondFirst(text: String) {
        lock.lock(); let request = requests.first; lock.unlock()
        request?.respond(text: text)
    }
}

private final class HeldModelProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var handler: ((HeldModelProtocol) -> Void)?
    static func configure(_ value: ((HeldModelProtocol) -> Void)?) { lock.lock(); handler = value; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let action = Self.handler; Self.lock.unlock()
        guard let action else { client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return }
        action(self)
    }
    func respond(text: String) {
        guard let url = request.url, let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]) else { return }
        let data = (try? JSONSerialization.data(withJSONObject: ["text": text, "segments": [["text": text, "avg_logprob": -0.1, "no_speech_prob": 0.01]]])) ?? Data()
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
