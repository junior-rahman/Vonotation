import XCTest
@testable import VonoTation

/// Request/response contract tests with dummy credentials and a local URLProtocol.
/// These do not upload audio, call a live model, or prove recognition quality.
final class CloudAPITests: XCTestCase {
    private var session: URLSession!
    private var directory: URL!
    private let dummyKey = "unit-test-dummy-key"

    override func setUpWithError() throws {
        try super.setUpWithError()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CloudStubProtocol.self]
        session = URLSession(configuration: configuration)
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("CloudAPITests-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        CloudStubProtocol.install(nil)
    }

    override func tearDownWithError() throws {
        session.invalidateAndCancel()
        CloudStubProtocol.install(nil)
        try FileManager.default.removeItem(at: directory)
        directory = nil
        session = nil
        try super.tearDownWithError()
    }

    func testWhisperMultipartCarriesEnglishAudioRouteAndBearer() async throws {
        let capture = CapturedCloudRequest()
        CloudStubProtocol.install { request in
            capture.set(request)
            return .json(["text": "Keep this exact sentence.", "segments": [["text": "Keep this exact sentence.", "avg_logprob": -0.2, "no_speech_prob": 0.01, "compression_ratio": 1.1]]])
        }
        var settings = AppSettings()
        settings.endpoint = "https://unit.test/openai/v1///"
        settings.language = "auto" // A stale setting cannot revive paused auto-detection.
        let audio = try savedAudio()
        let result = try await CloudAPI(session: session).transcribe(audioURL: audio, settings: settings, key: dummyKey)
        XCTAssertEqual(result, "Keep this exact sentence.")
        let request = try XCTUnwrap(capture.get())
        XCTAssertEqual(request.url?.absoluteString, "https://unit.test/openai/v1/audio/transcriptions")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + dummyKey)
        XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=VonoTation-") == true)
        XCTAssertEqual(request.timeoutInterval, 90)
        let body = try XCTUnwrap(capture.body())
        XCTAssertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-large-v3\r\n"))
        XCTAssertTrue(body.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"))
        XCTAssertTrue(body.contains("name=\"language\"\r\n\r\nen\r\n"))
        XCTAssertTrue(body.contains("name=\"temperature\"\r\n\r\n0\r\n"))
        XCTAssertTrue(body.contains("filename=\"recording.m4a\""))
        XCTAssertTrue(body.contains("Content-Type: audio/mp4"))
        XCTAssertTrue(body.contains("fixture-audio-bytes"))
        XCTAssertFalse(body.contains(dummyKey))
    }

    func testCustomSpeechModelCanReturnPlainJSONWithoutSegments() async throws {
        let capture = CapturedCloudRequest()
        CloudStubProtocol.install { request in
            capture.set(request)
            return .json(["text": "This provider has no confidence metadata."])
        }
        var settings = AppSettings()
        settings.transcriptionModel = "custom-speech-model"
        let result = try await CloudAPI(session: session).transcribe(audioURL: savedAudio(), settings: settings, key: dummyKey)
        XCTAssertEqual(result, "This provider has no confidence metadata.")
        let body = try XCTUnwrap(capture.body())
        XCTAssertTrue(body.contains("name=\"response_format\"\r\n\r\njson\r\n"))
        XCTAssertFalse(body.contains("name=\"temperature\""))
    }

    func testSevereLowConfidenceRejectsHallucinatedSpeech() async throws {
        CloudStubProtocol.install { _ in
            .json(["text": "Unreliable model text.", "segments": [["text": "Unreliable model text.", "avg_logprob": -1.2, "no_speech_prob": 0.1]]])
        }
        do {
            _ = try await CloudAPI(session: session).transcribe(audioURL: savedAudio(), settings: AppSettings(), key: dummyKey)
            XCTFail("Low-confidence speech must not become a saved transcript.")
        } catch CloudError.unsureSpeech { }
        catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
    }

    func testLowConfidenceWithReliableSilenceOrRepetitionIsRejected() async throws {
        let audio = try savedAudio()
        for confidence in [["no_speech_prob": 0.95], ["compression_ratio": 3.0]] {
            var segment: [String: Any] = ["text": "Unreliable repeated text.", "avg_logprob": -0.6]
            confidence.forEach { segment[$0.key] = $0.value }
            CloudStubProtocol.install { _ in .json(["text": "Unreliable repeated text.", "segments": [segment]]) }
            do {
                _ = try await CloudAPI(session: session).transcribe(audioURL: audio, settings: AppSettings(), key: dummyKey)
                XCTFail("Confidence and silence/repetition should reject this result.")
            } catch CloudError.unsureSpeech { }
            catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
        }
    }

    func testProviderErrorNeverSurfacesItsBodyOrCredential() async throws {
        let secretBody = "provider diagnostic includes \(dummyKey) and private source text"
        CloudStubProtocol.install { _ in .raw(status: 401, data: Data(secretBody.utf8)) }
        do {
            _ = try await CloudAPI(session: session).transcribe(audioURL: savedAudio(), settings: AppSettings(), key: dummyKey)
            XCTFail("Unauthorized response must fail.")
        } catch {
            guard case CloudError.unauthorized = error else { return XCTFail("Expected a safe authorization error.") }
            XCTAssertFalse(error.localizedDescription.contains(dummyKey))
            XCTAssertFalse(error.localizedDescription.contains("private source text"))
            XCTAssertFalse(error.localizedDescription.contains("provider diagnostic"))
        }
    }

    func testCrossOriginResponseIsRejected() async throws {
        CloudStubProtocol.install { _ in
            .raw(status: 200, data: Data("{\"text\":\"Must not accept this.\"}".utf8), responseURL: URL(string: "https://foreign.test/audio/transcriptions"))
        }
        do {
            _ = try await CloudAPI(session: session).transcribe(audioURL: savedAudio(), settings: AppSettings(), key: dummyKey)
            XCTFail("A response from a different origin must not be accepted.")
        } catch CloudError.invalidEndpoint { }
        catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
    }

    func testRedirectStatusIsNotAcceptedAsSuccess() async throws {
        CloudStubProtocol.install { _ in .raw(status: 302, data: Data("redirect".utf8)) }
        do {
            _ = try await CloudAPI(session: session).transcribe(audioURL: savedAudio(), settings: AppSettings(), key: dummyKey)
            XCTFail("Redirect status must not be decoded as a valid result.")
        } catch CloudError.requestRejected { }
        catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
    }

    func testInvalidEndpointAndOversizeAudioAreRejectedBeforeNetwork() async throws {
        let requests = CapturedCloudRequest()
        CloudStubProtocol.install { request in
            requests.set(request)
            return .json(["text": "Unexpected network request."])
        }
        let audio = try savedAudio()
        for endpoint in ["http://unit.test/v1", "https://user:password@unit.test/v1", "https://unit.test/v1?token=private", "https://unit.test/v1#fragment"] {
            var settings = AppSettings(); settings.endpoint = endpoint
            do {
                _ = try await CloudAPI(session: session).transcribe(audioURL: audio, settings: settings, key: dummyKey)
                XCTFail("Invalid endpoint should fail before upload.")
            } catch CloudError.invalidEndpoint { }
            catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
        }
        let handle = try FileHandle(forWritingTo: audio)
        try handle.truncate(atOffset: 24_000_001)
        try handle.close()
        do {
            _ = try await CloudAPI(session: session).transcribe(audioURL: audio, settings: AppSettings(), key: dummyKey)
            XCTFail("Oversize audio must not upload.")
        } catch CloudError.audioTooLarge { }
        catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
        XCTAssertNil(requests.get())
    }

    func testUnsafeCleanupResponseKeepsExactOriginal() async throws {
        let original = "Hello how are you doing"
        let capture = CapturedCloudRequest()
        CloudStubProtocol.install { request in
            capture.set(request)
            return .chat("I'm fine thank you")
        }
        let result = try await CloudAPI(session: session).clean(original, settings: AppSettings(), key: dummyKey)
        XCTAssertEqual(result, original)
        XCTAssertEqual(capture.get()?.url?.path, "/openai/v1/chat/completions")
        let body = try XCTUnwrap(capture.body()?.data(using: .utf8))
        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(payload["model"] as? String, "openai/gpt-oss-20b")
        XCTAssertEqual((payload["messages"] as? [[String: Any]])?.last?["content"] as? String, original)
    }

    func testLengthLimitedChatResponseIsRejected() async throws {
        CloudStubProtocol.install { _ in .chat("Hello", finish: "length") }
        do {
            _ = try await CloudAPI(session: session).clean("Hello there", settings: AppSettings(), key: dummyKey)
            XCTFail("A truncated provider response is incomplete.")
        } catch CloudError.invalidResponse { }
        catch { XCTFail("Unexpected error: \(error.localizedDescription)") }
    }

    private func savedAudio() throws -> URL {
        let url = directory.appendingPathComponent(UUID().uuidString + ".m4a")
        try Data("fixture-audio-bytes".utf8).write(to: url)
        return url
    }
}

private final class CapturedCloudRequest {
    private let lock = NSLock()
    private var request: URLRequest?
    private var content: String?
    func set(_ value: URLRequest) {
        // URLSession may expose POST data as a stream to URLProtocol.
        var data = value.httpBody
        if data == nil, let stream = value.httpBodyStream {
            stream.open(); defer { stream.close() }
            var collected = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                collected.append(contentsOf: buffer.prefix(count))
            }
            data = collected
        }
        lock.lock(); defer { lock.unlock() }
        request = value
        content = data.flatMap { String(data: $0, encoding: .utf8) }
    }
    func get() -> URLRequest? { lock.lock(); defer { lock.unlock() }; return request }
    func body() -> String? { lock.lock(); defer { lock.unlock() }; return content }
}

private final class CloudStubProtocol: URLProtocol {
    struct Reply {
        let status: Int
        let data: Data
        let responseURL: URL?
        static func json(_ object: [String: Any]) -> Reply {
            Reply(status: 200, data: (try? JSONSerialization.data(withJSONObject: object)) ?? Data(), responseURL: nil)
        }
        static func raw(status: Int, data: Data, responseURL: URL? = nil) -> Reply {
            Reply(status: status, data: data, responseURL: responseURL)
        }
        static func chat(_ text: String, finish: String = "stop") -> Reply {
            .json(["choices": [["message": ["content": text], "finish_reason": finish]]])
        }
    }
    private static let lock = NSLock()
    private static var handler: ((URLRequest) -> Reply)?
    static func install(_ value: ((URLRequest) -> Reply)?) {
        lock.lock(); defer { lock.unlock() }; handler = value
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let installed = Self.handler; Self.lock.unlock()
        guard let installed, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        let reply = installed(request)
        guard let response = HTTPURLResponse(url: reply.responseURL ?? url, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: reply.data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
