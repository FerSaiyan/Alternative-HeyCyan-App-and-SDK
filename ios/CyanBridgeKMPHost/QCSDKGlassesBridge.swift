import CoreBluetooth
import CyanBridgeShared
import Foundation
#if canImport(QCSDK)
import QCSDK
#endif

/// Registers the vendor SDK bridge before the shared Compose controller starts.
enum VendorGlassesSetup {
    static func register() {
        #if canImport(QCSDK)
        VendorGlassesRegistry.shared.bridge = QCSDKGlassesBridge.shared
        #endif
    }
}

#if canImport(QCSDK)
/// QCSDK.framework implementation of the shared `VendorGlassesBridge`.
/// QCSDK ships only an arm64 device slice, so simulator builds skip this file's body.
final class QCSDKGlassesBridge: NSObject, VendorGlassesBridge, QCSDKManagerDelegate {
    static let shared = QCSDKGlassesBridge()

    private var eventListener: VendorGlassesEventListener?

    private override init() {
        super.init()
        QCSDKManager.shareInstance().delegate = self
    }

    var serviceUuids: [String] { [QCSDKSERVERUUID1, QCSDKSERVERUUID2] }

    func attach(peripheral: CBPeripheral, completion: VendorResultCallback) {
        let manager = QCSDKManager.shareInstance()
        manager.delegate = self
        manager.remove(peripheral)
        manager.add(peripheral) { success in
            completion.onResult(success: success)
        }
    }

    func detach(peripheral: CBPeripheral) {
        QCSDKManager.shareInstance().remove(peripheral)
    }

    func setEventListener(listener: VendorGlassesEventListener?) {
        eventListener = listener
    }

    func setDeviceMode(mode: Int32, completion: VendorModeCallback) {
        guard let deviceMode = QCOperatorDeviceMode(rawValue: Int(mode)) else {
            completion.onResult(success: false, currentMode: 0)
            return
        }
        QCSDKCmdCreator.setDeviceMode(deviceMode, success: {
            completion.onResult(success: true, currentMode: mode)
        }, fail: { currentMode in
            completion.onResult(success: false, currentMode: Int32(currentMode))
        })
    }

    func requestBattery(completion: VendorBatteryCallback) {
        QCSDKCmdCreator.getDeviceBattery({ level, charging in
            completion.onResult(success: true, level: Int32(level), charging: charging)
        }, fail: {
            completion.onResult(success: false, level: 0, charging: false)
        })
    }

    func requestVersion(completion: VendorVersionCallback) {
        QCSDKCmdCreator.getDeviceVersionInfoSuccess({ hardware, firmware, wifiHardware, wifiFirmware in
            completion.onResult(
                success: true,
                hardware: hardware,
                firmware: firmware,
                wifiHardware: wifiHardware,
                wifiFirmware: wifiFirmware
            )
        }, fail: {
            completion.onResult(success: false, hardware: "", firmware: "", wifiHardware: "", wifiFirmware: "")
        })
    }

    func requestMediaCounts(completion: VendorMediaCountsCallback) {
        QCSDKCmdCreator.getDeviceMedia({ photos, videos, audio, _ in
            completion.onResult(success: true, photos: Int32(photos), videos: Int32(videos), audio: Int32(audio))
        }, fail: {
            completion.onResult(success: false, photos: 0, videos: 0, audio: 0)
        })
    }

    func syncTime(completion: VendorResultCallback) {
        QCSDKCmdCreator.setupDeviceDateTime { success, _ in
            completion.onResult(success: success)
        }
    }

    func openWifi(mode: Int32, completion: VendorWifiCallback) {
        guard let deviceMode = QCOperatorDeviceMode(rawValue: Int(mode)) else {
            completion.onResult(success: false, ssid: "", passphrase: "", currentMode: 0)
            return
        }
        QCSDKCmdCreator.openWifi(with: deviceMode, success: { ssid, passphrase in
            completion.onResult(success: true, ssid: ssid, passphrase: passphrase, currentMode: mode)
        }, fail: { currentMode in
            completion.onResult(success: false, ssid: "", passphrase: "", currentMode: Int32(currentMode))
        })
    }

    func requestWifiIp(completion: VendorTextCallback) {
        QCSDKCmdCreator.getDeviceWifiIPSuccess({ ip in
            let value = ip ?? ""
            completion.onResult(success: !value.isEmpty, value: value)
        }, failed: {
            completion.onResult(success: false, value: "")
        })
    }

    func requestVolume(completion: VendorVolumeCallback) {
        QCSDKCmdCreator.getVolumeWithFinished { success, _, result in
            guard success, let info = result as? QCVolumeInfoModel else {
                completion.onResult(success: false, musicCurrent: 0, musicMax: 0, callCurrent: 0, callMax: 0, systemCurrent: 0, systemMax: 0)
                return
            }
            completion.onResult(
                success: true,
                musicCurrent: Int32(info.musicCurrent),
                musicMax: Int32(info.musicMax),
                callCurrent: Int32(info.callCurrent),
                callMax: Int32(info.callMax),
                systemCurrent: Int32(info.systemCurrent),
                systemMax: Int32(info.systemMax)
            )
        }
    }

    func requestWearingDetection(completion: VendorToggleCallback) {
        QCSDKCmdCreator.getWearingDetection { success, _, result in
            guard success, let enabled = Self.boolValue(result) else {
                completion.onResult(success: false, enabled: false)
                return
            }
            completion.onResult(success: true, enabled: enabled)
        }
    }

    func setWearingDetection(enabled: Bool, completion: VendorResultCallback) {
        QCSDKCmdCreator.setWearingDetection(enabled) { success, _, _ in
            completion.onResult(success: success)
        }
    }

    func requestVideoSettings(completion: VendorRecordingSettingsCallback) {
        QCSDKCmdCreator.getVideoInfoSuccess({ angle, duration in
            completion.onResult(success: true, angle: Int32(angle), durationSeconds: Int32(duration))
        }, fail: {
            completion.onResult(success: false, angle: 0, durationSeconds: 0)
        })
    }

    func setVideoSettings(angle: Int32, durationSeconds: Int32, completion: VendorResultCallback) {
        QCSDKCmdCreator.setVideoInfo(Int(angle), duration: Int(durationSeconds), success: {
            completion.onResult(success: true)
        }, fail: {
            completion.onResult(success: false)
        })
    }

    func requestAudioSettings(completion: VendorRecordingSettingsCallback) {
        QCSDKCmdCreator.getAudioInfoSuccess({ angle, duration in
            completion.onResult(success: true, angle: Int32(angle), durationSeconds: Int32(duration))
        }, fail: {
            completion.onResult(success: false, angle: 0, durationSeconds: 0)
        })
    }

    func setAudioSettings(angle: Int32, durationSeconds: Int32, completion: VendorResultCallback) {
        QCSDKCmdCreator.setAudioInfo(Int(angle), duration: Int(durationSeconds), success: {
            completion.onResult(success: true)
        }, fail: {
            completion.onResult(success: false)
        })
    }

    func deleteMedia(filename: String, completion: VendorResultCallback) {
        QCSDKCmdCreator.deleleteMedia(filename, success: {
            completion.onResult(success: true)
        }, fail: {
            completion.onResult(success: false)
        })
    }

    func requestThumbnail(pocket: Int32, completion: VendorThumbnailCallback) {
        QCSDKCmdCreator.getThumbnail(Int(pocket), success: { data, width, height in
            completion.onResult(success: true, data: data, width: Int32(width), height: Int32(height))
        }, fail: {
            completion.onResult(success: false, data: nil, width: 0, height: 0)
        })
    }

    func setAiSpeakMode(mode: Int32, completion: VendorResultCallback) {
        guard let speakMode = QGAISpeakMode(rawValue: Int(mode)) else {
            completion.onResult(success: false)
            return
        }
        QCSDKCmdCreator.setAISpeekModel(speakMode) { success, _ in
            completion.onResult(success: success)
        }
    }

    /// QCSDK returns toggle states as an untyped `id`; accept the shapes it is known to use.
    private static func boolValue(_ result: Any?) -> Bool? {
        switch result {
        case let value as Bool:
            return value
        case let value as NSNumber:
            return value.boolValue
        case let value as [AnyHashable: Any]:
            return value.values.lazy.compactMap { ($0 as? NSNumber)?.boolValue }.first
        default:
            return nil
        }
    }

    // MARK: QCSDKManagerDelegate

    func didUpdateBatteryLevel(_ battery: Int, charging: Bool) {
        eventListener?.onBatteryChanged(level: Int32(battery), charging: charging)
    }

    func didUpdateMedia(withPhotoCount photo: Int, videoCount video: Int, audioCount audio: Int, type: Int) {
        eventListener?.onMediaCountsChanged(photos: Int32(photo), videos: Int32(video), audio: Int32(audio))
    }

    func didReceiveAIChatImageData(_ imageData: Data) {
        eventListener?.onAiImage(data: imageData)
    }
}
#endif
