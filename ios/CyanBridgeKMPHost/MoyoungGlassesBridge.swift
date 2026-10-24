import CoreBluetooth
import CyanBridgeShared
import Foundation
#if canImport(CRPSmartGlasses)
import CRPSmartGlasses

/// MoYoung / W620 glasses through CRPSmartGlasses.framework, the iOS counterpart of Android's
/// MoyoungW620Manager. The SDK scans and connects on its own; the identifier from the shared
/// pairing screen is the CoreBluetooth UUID, which is stable for the same glasses on this phone.
/// The framework is arm64 iPhone-only (ios/scripts/fetch_moyoung_sdk.sh), so simulator builds
/// compile this file empty.
final class MoyoungGlassesBridge: NSObject, SdkGlassesBridge, CRPGlassesManagerDelegate {
    static let shared = MoyoungGlassesBridge()

    private let sdk = CRPSmartGlassesSDK.sharedInstance
    private var listener: SdkGlassesListener?
    private var pendingConnect: SdkResultCallback?
    private var pendingPhoto: SdkPhotoCallback?
    private var connectedIdentifier = ""
    private var discovery: CRPGlassesDiscovery?

    private override init() {
        super.init()
        sdk.delegate = self
    }

    var deviceClassName: String { "MOYOUNG_W620" }

    func setListener(listener: SdkGlassesListener?) {
        self.listener = listener
    }

    func connect(identifier: String, name: String, completion: SdkResultCallback) {
        pendingConnect?.onResult(success: false, message: "Replaced by a new connection")
        pendingConnect = completion
        sdk.interruptScan()
        var found = false
        sdk.scan(10, progressHandler: { [weak self] discoveries in
            guard let self, !found,
                  let match = discoveries.first(where: { Self.matches($0, identifier: identifier, name: name) })
            else { return }
            found = true
            self.sdk.interruptScan()
            self.discovery = match
            self.connectedIdentifier = match.remotePeripheral.identifier.uuidString
            self.sdk.connect(match)
        }, completionHandler: { [weak self] _, _ in
            guard let self, !found else { return }
            self.finishConnect(success: false, message: "MoYoung glasses not found. Wake them up and retry.")
        })
    }

    func disconnect() {
        sdk.interruptScan()
        sdk.remove()
        discovery = nil
    }

    func requestBattery() {
        sdk.getBattery { [weak self] info in
            DispatchQueue.main.async {
                self?.listener?.onBattery(level: Int32(min(info.lvl, 100)), charging: info.charging)
            }
        }
    }

    func requestMediaCounts() {
        sdk.getFileCount { [weak self] count in
            DispatchQueue.main.async {
                self?.listener?.onMediaCounts(
                    photos: Int32(count.pictureCount),
                    videos: Int32(count.videoCount),
                    audio: Int32(count.audioCount)
                )
            }
        }
    }

    /// The SDK's AI-recognition photo arrives through receiveAirecognitionImageData.
    func captureAiPhoto(completion: SdkPhotoCallback) {
        pendingPhoto?.onResult(data: nil, message: "Replaced by a new photo request")
        pendingPhoto = completion
        sdk.setTakePhoto(takePhoto: CRPTakePhoto(mode: .modeAirecognition, param: Data()))
    }

    func perform(action: String, argument: String, completion: SdkTextCallback) {
        switch action {
        case SdkGlassesActions.shared.CAPTURE_PHOTO:
            sdk.setTakePhoto(takePhoto: CRPTakePhoto(mode: .modeNormal, param: Data()))
            completion.onResult(success: true, value: "")
        case SdkGlassesActions.shared.SYNC_TIME:
            syncTime()
            completion.onResult(success: true, value: "Glasses clock synced")
        case SdkGlassesActions.shared.VERSION:
            sdk.getJLSoftver { [weak self] firmware in
                self?.sdk.getQZSoftver { camera in
                    DispatchQueue.main.async {
                        completion.onResult(success: true, value: "Firmware: \(firmware.ver)\nCamera: \(camera.ver)")
                    }
                }
            }
        default:
            completion.onResult(success: false, value: "MoYoung glasses do not support \(action) on iOS")
        }
    }

    private func syncTime() {
        let timezoneMinutes = TimeZone.current.secondsFromGMT() / 60
        sdk.setTime(time: CRPSetTimeInfo(timestamp: UInt32(Date().timeIntervalSince1970), timezone: Int32(timezoneMinutes)))
    }

    private func finishConnect(success: Bool, message: String) {
        let completion = pendingConnect
        pendingConnect = nil
        completion?.onResult(success: success, message: message)
    }

    private static func matches(_ discovery: CRPGlassesDiscovery, identifier: String, name: String) -> Bool {
        if !identifier.isEmpty {
            return discovery.remotePeripheral.identifier.uuidString == identifier
        }
        let localName = (discovery.localName ?? "").lowercased()
        if !name.isEmpty { return localName == name.lowercased() }
        return localName.contains("w620") || localName.contains("moyoung") || localName.contains("da echo")
    }

    // MARK: - CRPGlassesManagerDelegate (main thread is not guaranteed)

    func didGlassesState(_ state: CRPGlassesState) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            switch state {
            case .connected, .syncSuccess:
                self.syncTime()
                self.listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTED, label: "MoYoung / W620 connected")
                self.finishConnect(success: true, message: self.connectedIdentifier)
            case .connecting, .syncing:
                self.listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTING, label: "Connecting to MoYoung / W620")
            case .unbind, .disconnected, .disconnecting, .syncError:
                self.listener?.onConnectionChanged(state: SdkConnectionState.shared.DISCONNECTED, label: "MoYoung / W620 disconnected")
                if state == .syncError { self.finishConnect(success: false, message: "MoYoung connection failed") }
            @unknown default:
                break
            }
        }
    }

    func didGlassesBluetoothState(_ state: CRPGlassesBluetoothState) {}

    func receiveOTA(state: CRPOTAState) {}

    func receiveAudioState(_ state: CRPAudioCtrl) {}

    func receiveAudioData(frameIndex: Int, data: Data) {}

    func receiveTranslateAudioData(frameIndex: Int, data: Data) {}

    func receiveActionResult(actionResult: CRPActionResult) {}

    func receiveFileBaseUrl(fileUrlBase: CRPFileUrlBase) {}

    func receiveAirecognitionImageData(data: Data) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let completion = self.pendingPhoto {
                self.pendingPhoto = nil
                completion.onResult(data: data, message: "")
            } else {
                // The glasses' own AI-photo gesture.
                self.listener?.onAiImage(data: data)
            }
        }
    }

    func receiveACKError(errorCode: CRPACKError) {}

    func receiveRunningStatus(status: CRPRunningStatus) {}
}
#endif
