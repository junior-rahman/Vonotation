import Foundation
import CryptoKit

enum ClipboardPolicy {
    static let retention: TimeInterval = 30 * 24 * 60 * 60
    static func isExpired(_ item: ClipItem, now: Date = Date()) -> Bool { item.expiresAt <= now }
    static func retained(_ items: [ClipItem], now: Date = Date()) -> [ClipItem] {
        items.filter { !isExpired($0, now: now) }.sorted { $0.createdAt > $1.createdAt }
    }
    static func shouldAppend(_ text: String, to items: [ClipItem], now: Date = Date()) -> Bool {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        // Reopening/Pasting the same current copy does not extend its expiry.
        return retained(items, now: now).first?.text != text
    }
}

enum NotePolicy {
    static func words(_ text: String) -> [String] {
        text.lowercased().replacingOccurrences(of: "’", with: "'")
            .components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "'")).inverted)
            .filter { !$0.isEmpty }
    }
    static func title(_ text: String, fallback: String = "Untitled note") -> String {
        let clean = text.components(separatedBy: .newlines).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) ?? ""
        let first = clean.components(separatedBy: CharacterSet(charactersIn: ".!?。！？")).first ?? clean
        let tokens = first.split(whereSeparator: { $0.isWhitespace })
        guard !tokens.isEmpty else { return fallback }
        return String(tokens.prefix(9).joined(separator: " ").prefix(90))
    }
    static func dayStart(_ date: Date, calendar: Calendar = .current) -> Date { calendar.startOfDay(for: date) }
    static func dayID(_ date: Date, kind: NoteKind, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.era, .year, .month, .day], from: date)
        return "\(kind.rawValue):\(calendar.timeZone.identifier):\(c.era ?? 0)-\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
    }
    static func fingerprint(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    static func thoughtEligible(_ text: String) -> Bool {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Stable canonical two-line rule, independent of phone size or accessibility font.
        // Explicit two-line text still needs substantive content on both lines.
        let lines = normalized.split(separator: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        if lines.count >= 2 && words(normalized).count >= 8 { return true }
        return normalized.count >= 65 && words(normalized).count >= 12
    }
    static func summaryAccepted(source: String, candidate: String) -> Bool {
        CloudTextGuard.summaryAccepted(original: source, candidate: candidate)
    }
    static func localGist(_ text: String) -> String {
        let total = words(text).count
        guard total > 12 else { return "" }
        // Complete extractive sentences only; never relabel the full transcript a summary.
        let expression = try? NSRegularExpression(pattern: "[^.!?。！？\\n]+[.!?。！？]?(?:\\n|$|\\s*)")
        let ns = text as NSString
        let sentences = expression?.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty } ?? []
        let budget = max(8, Int(ceil(Double(total) * 0.35)))
        var selected: [String] = [], count = 0
        for sentence in sentences {
            let size = words(sentence).count
            if size > 0 && count + size <= budget && selected.count < 3 {
                selected.append(sentence); count += size
            }
        }
        let result = selected.joined(separator: "\n\n")
        return summaryAccepted(source: text, candidate: result) ? result : ""
    }
}

/// Imported text is authoritative. Keep a source only when its identity is verifiable;
/// preserve conflicting/unmatched local audio as a separate recoverable note.
enum BackupMergePolicy {
    static func merge(imported: [VonoNote], local: [VonoNote]) -> [VonoNote] {
        var result = imported.map { imported -> VonoNote in
            var note = imported
            note.mayFilterShortThought = false
            if [.thought, .voiceNote, .call].contains(note.kind) && (note.summary.isEmpty || note.needsSummary) {
                note.state = .pending; note.needsSummary = true
                note.error = "Transcript restored. Tap Retry to create its concise summary."
            } else if note.kind == .translation && note.translation.isEmpty {
                note.state = .pending
                note.error = "Transcript restored. Tap Retry to translate it."
            }
            return note
        }
        var ids = Set(result.map(\.id))
        for source in local where source.audioFileName != nil || source.state != .ready || source.needsSummary {
            if let index = result.firstIndex(where: { $0.id == source.id }),
               !source.rawTranscript.isEmpty,
               result[index].rawTranscript == source.rawTranscript,
               result[index].kind == source.kind {
                result[index].audioFileName = source.audioFileName
                result[index].mayFilterShortThought = false
                if source.state != .ready || source.needsSummary {
                    result[index].state = source.state == .processing ? .pending : source.state
                    result[index].needsSummary = source.needsSummary
                    result[index].error = source.error ?? "Restored text saved. Pending processing was kept; tap Retry."
                }
            } else {
                var retained = source
                if ids.contains(retained.id) {
                    repeat { retained.id = UUID() } while ids.contains(retained.id)
                }
                ids.insert(retained.id); result.append(retained)
            }
        }
        return result.sorted { $0.createdAt > $1.createdAt }
    }
}
