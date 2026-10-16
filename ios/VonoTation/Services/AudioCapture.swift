import Foundation
import AVFoundation
import UIKit

@MainActor
final class AudioCapture: NSObject, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var tick: Timer?
    private(set) var url: URL?
    var onDuration: ((TimeInterval) -> Void)?
    var onInterruption: ((String) -> Void)?
    var isRecording: Bool { recorder?.isRecording == true }

    func permission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in continuation.resume(returning: granted) }
        }
    }
    func start(at url: URL) throws {
        guard recorder == nil else { throw VonoError.message("Finish or cancel the current recording first.") }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default, options: [.allowBluetooth])
        try session.setAllowHapticsAndSystemSoundsDuringRecording(true)
        try session.setActive(true)
        do {
            let capture = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
                                                                   AVSampleRateKey: 44_100,
                                                                   AVNumberOfChannelsKey: 1,
                                                                   AVEncoderBitRateKey: 64_000,
                                                                   AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue])
            capture.delegate = self
            guard capture.prepareToRecord(), capture.record() else { throw VonoError.message("The microphone could not start. No recording was uploaded.") }
            self.url = url; self.recorder = capture
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
            UIApplication.shared.isIdleTimerDisabled = true
            tick = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor in guard let self = self else { return }; self.onDuration?(self.recorder?.currentTime ?? 0) }
            }
            interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
                guard let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      type == AVAudioSession.InterruptionType.began.rawValue else { return }
                Task { @MainActor in self?.interrupted("iOS interrupted the microphone. The captured audio was kept; tap Retry after the interruption.") }
            }
            routeChange = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
                guard let reason = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
                Task { @MainActor in self?.interrupted("The recording microphone disconnected. Your captured audio was kept.") }
            }
        } catch {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            UIApplication.shared.isIdleTimerDisabled = false
            throw error
        }
    }
    func finish() -> URL? {
        guard let capture = recorder else { return nil }
        capture.stop()
        let result = url
        cleanup()
        return result
    }
    func cancel() {
        let source = finish()
        if let source = source { try? FileManager.default.removeItem(at: source) }
    }
    private func interrupted(_ message: String) {
        guard recorder != nil else { return }
        _ = finish()
        onInterruption?(message)
    }
    private func cleanup() {
        tick?.invalidate(); tick = nil
        if let interruption = interruption { NotificationCenter.default.removeObserver(interruption) }
        if let routeChange = routeChange { NotificationCenter.default.removeObserver(routeChange) }
        interruption = nil; routeChange = nil; recorder = nil; url = nil
        UIApplication.shared.isIdleTimerDisabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        Task { @MainActor [weak self] in
            guard let self = self, self.recorder === recorder else { return }
            self.interrupted("The recorder encountered an error. Your captured source was kept for recovery.")
        }
    }
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self = self, self.recorder === recorder else { return }
            self.interrupted(flag ? "iOS ended the recording. Your captured audio was kept for recovery." : "Recording ended unexpectedly. Your captured audio was kept for recovery.")
        }
    }

}
