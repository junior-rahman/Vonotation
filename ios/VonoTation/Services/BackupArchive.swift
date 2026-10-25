import Foundation
import CryptoKit
import CommonCrypto
import Security

/// iOS-only password-encrypted archive. Distinct magic prevents accidental Android restore.
/// AES-256-GCM; 210,000-round PBKDF2-HMAC-SHA256; 16-byte salt; 12-byte nonce.
enum BackupArchive {
    static let maximumBytes = 8 * 1024 * 1024
    private static let magic = Data("VONOIOSB".utf8)
    private static let iterations: UInt32 = 210_000
    private static let headerSize = 48
    static func encrypt(json: Data, password: String) throws -> Data {
        guard password.count >= 8 else { throw VonoError.message("Use a backup password with at least 8 characters.") }
        guard !json.isEmpty, json.count <= maximumBytes else { throw VonoError.message("This backup is larger than the supported 8 MB limit.") }
        let salt = try random(count: 16), nonceData = try random(count: 12)
        var header = magic
        header.append(bigEndian(1)); header.append(bigEndian(iterations))
        header.append(salt); header.append(nonceData); header.append(bigEndian(UInt32(json.count + 16)))
        let key = try derive(password: password, salt: salt)
        let box = try AES.GCM.seal(json, using: key, nonce: AES.GCM.Nonce(data: nonceData), authenticating: header)
        return header + box.ciphertext + box.tag
    }
    static func decrypt(archive: Data, password: String) throws -> Data {
        guard archive.count >= headerSize + 16, archive.count <= maximumBytes + headerSize + 16 else {
            throw VonoError.message("Choose a complete \(AppProduct.current.displayName) iOS backup under 8 MB.")
        }
        let bytes = [UInt8](archive)
        guard Data(bytes[0..<8]) == magic else { throw VonoError.message("Choose a \(AppProduct.current.displayName) iOS backup. Android backups use a different format.") }
        guard integer(bytes, at: 8) == 1, integer(bytes, at: 12) == iterations,
              integer(bytes, at: 44) == UInt32(archive.count - headerSize) else {
            throw VonoError.message("This backup header is invalid or unsupported.")
        }
        let header = Data(bytes[0..<headerSize]), salt = Data(bytes[16..<32]), nonce = Data(bytes[32..<44])
        let key = try derive(password: password, salt: salt)
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce),
                                            ciphertext: Data(bytes[headerSize..<(bytes.count - 16)]),
                                            tag: Data(bytes[(bytes.count - 16)..<bytes.count]))
            return try AES.GCM.open(box, using: key, authenticating: header)
        } catch { throw VonoError.message("The password is incorrect, or this backup is damaged. Your local data was not changed.") }
    }
    private static func random(count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else {
            throw VonoError.message("Secure backup encryption is unavailable. Try again.")
        }
        return Data(bytes)
    }
    private static func derive(password: String, salt: Data) throws -> SymmetricKey {
        var result = [UInt8](repeating: 0, count: 32)
        let pass = Array(password.utf8)
        let status = pass.withUnsafeBytes { passBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2),
                                     passBytes.bindMemory(to: Int8.self).baseAddress, pass.count,
                                     saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), iterations,
                                     &result, 32)
            }
        }
        guard status == kCCSuccess else { throw VonoError.message("The backup encryption key could not be created.") }
        return SymmetricKey(data: Data(result))
    }
    private static func bigEndian(_ value: UInt32) -> Data {
        Data([UInt8((value >> 24) & 255), UInt8((value >> 16) & 255), UInt8((value >> 8) & 255), UInt8(value & 255)])
    }
    private static func integer(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        bytes[offset..<(offset + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}

struct BackupPayload: Codable {
    var format = "VonoTation-iOS"
    var schema = 1
    var exportedAt = Date()
    var product: AppProduct? = nil
    var notes: [VonoNote]
    var settings: AppSettings

    func validated(now: Date = Date()) throws -> BackupPayload {
        guard format == "VonoTation-iOS", schema == 1 else { throw VonoError.message("This iOS backup version is not supported.") }
        guard notes.count <= 10_000, exportedAt.timeIntervalSince1970.isFinite,
              exportedAt.timeIntervalSince1970 > 0, exportedAt <= now.addingTimeInterval(24 * 60 * 60) else {
            throw VonoError.message("The backup contains invalid dates or too many notes.")
        }
        guard Set(notes.map(\.id)).count == notes.count else { throw VonoError.message("The backup contains duplicate note identifiers.") }
        guard Self.validEndpoint(settings.endpoint), settings.language == "en",
              !settings.transcriptionModel.isEmpty, !settings.cleanupModel.isEmpty,
              !settings.noteModel.isEmpty, !settings.translationModel.isEmpty,
              [settings.transcriptionModel, settings.cleanupModel, settings.noteModel, settings.translationModel].allSatisfy({ $0.count <= 160 && !$0.contains("\n") && !$0.contains("\r") }),
              settings.translationLanguage.count <= 80 else { throw VonoError.message("The backup contains invalid provider or model settings.") }
        for note in notes {
            guard note.state == .ready, note.audioFileName == nil,
                  (!note.needsSummary || [.thought, .voiceNote, .call].contains(note.kind)),
                  !note.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  note.title.count <= 300, note.transcript.utf8.count <= 2 * 1024 * 1024,
                  note.rawTranscript.utf8.count <= 2 * 1024 * 1024, note.summary.utf8.count <= 512 * 1024,
                  note.translation.utf8.count <= 2 * 1024 * 1024,
                  note.createdAt.timeIntervalSince1970.isFinite, note.updatedAt.timeIntervalSince1970.isFinite,
                  note.createdAt.timeIntervalSince1970 > 0,
                  note.createdAt <= now.addingTimeInterval(24 * 60 * 60), note.updatedAt >= note.createdAt,
                  note.updatedAt <= now.addingTimeInterval(24 * 60 * 60), note.revision >= 0,
                  note.error == nil else { throw VonoError.message("The backup contains invalid or unfinished notes. Your local data was not changed.") }
        }
        return self
    }
    func validated(for receivingProduct: AppProduct) throws -> BackupPayload {
        let payload = try validated()
        guard product == receivingProduct || (product == nil && receivingProduct == .vonotation) else {
            throw VonoError.message("This backup belongs to a different app. Your local data was not changed.")
        }
        if product != nil && notes.contains(where: { !receivingProduct.supports($0.kind) }) {
            throw VonoError.message("This backup contains another app’s note types. Your local data was not changed.")
        }
        return payload
    }
    static func validEndpoint(_ value: String) -> Bool {
        guard let url = URLComponents(string: value), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { return false }
        return !value.contains("\r") && !value.contains("\n")
    }
}
