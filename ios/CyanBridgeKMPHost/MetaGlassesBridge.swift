import CyanBridgeShared
import Foundation
import UIKit
#if canImport(MWDATCore)
import MWDATCamera
import MWDATCore

/// Meta Ray-Ban glasses through the Meta Wearables Device Access Toolkit, the iOS counterpart of
/// Android's MetaRaybanManager. Pairing is a registration with the Meta AI app (it opens and calls
/// back through the `cyanbridge://` URL); the camera is used for the assistant's image questions,
/// like Android's capturePhotoOnce.
final class MetaGlassesBridge: NSObject, SdkGlassesBridge {
    static let shared = MetaGlassesBridge()

    /// Meta AI on the App Store, for when the app is missing.
    private static let metaAiAppStoreUrl = URL(string: "https://apps.apple.com/app/id1558240027")!

    private var listener: SdkGlassesListener?
    private var observing = false
    private var session: DeviceSession?
    private var camera: MWDATCamera.Camera?
    private let sessionTokens = ListenerTokenBag()
    private let streamTokens = ListenerTokenBag()
    private var pendingConnect: SdkResultCallback?
    private var pendingPhoto: SdkPhotoCallback?
    private var lastError = ""

    private override init() {
        super.init()
    }

    /// Call once at launch, before any other DAT call.
    static func configure() {
        do {
            try Wearables.configure()
        } catch {
            NSLog("[CyanBridge] Meta Wearables configure failed: \(error)")
        }
    }

    /// Meta AI calls back after registration and permission requests.
    static func handle(url: URL) {
        guard
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
        else { return }
        Task { @MainActor in
            do {
                _ = try await Wearables.shared.handleUrl(url)
            } catch {
                shared.report(error: error.localizedDescription)
            }
        }
    }

    var deviceClassName: String { "META_RAYBAN" }

    func setListener(listener: SdkGlassesListener?) {
        self.listener = listener
        guard listener != nil else { return }
        startObserving()
        onRegistration(Wearables.shared.registrationState)
        onDevices(Wearables.shared.devices)
    }

    func connect(identifier: String, name: String, completion: SdkResultCallback) {
        pendingConnect?.onResult(success: false, message: "Replaced by a new connection")
        pendingConnect = nil
        guard Wearables.shared.registrationState == .registered else {
            completion.onResult(success: false, message: "Approve CyanBridge in the Meta AI app, then come back here")
            register()
            return
        }
        if session?.state == .started {
            completion.onResult(success: true, message: deviceClassName)
            return
        }
        pendingConnect = completion
        startSession()
    }

    func disconnect() {
        camera?.stop()
        camera = nil
        session?.stop()
    }

    // DAT 1.0 exposes no battery or gallery counts to third-party apps.
    func requestBattery() {}

    func requestMediaCounts() {}

    func captureAiPhoto(completion: SdkPhotoCallback) {
        pendingPhoto?.onResult(data: nil, message: "Replaced by a new photo request")
        pendingPhoto = completion
        Task { @MainActor in
            do {
                try await self.ensureStartedSession()
                if try await Wearables.shared.checkPermissionStatus(.camera) != .granted {
                    // Opens Meta AI for the user to allow camera access.
                    guard try await Wearables.shared.requestPermission(.camera) == .granted else {
                        return self.finishPhoto(nil, "Camera access for CyanBridge was not allowed in Meta AI")
                    }
                }
                try self.startPhotoStream()
            } catch {
                self.finishPhoto(nil, error.localizedDescription)
            }
        }
    }

    func perform(action: String, argument: String, completion: SdkTextCallback) {
        switch action {
        case SdkGlassesActions.shared.META_REGISTER:
            register()
            completion.onResult(success: true, value: "Opening Meta AI to approve CyanBridge…")
        case SdkGlassesActions.shared.META_UNREGISTER:
            Task { @MainActor in
                do {
                    try await Wearables.shared.startUnregistration()
                    completion.onResult(success: true, value: "")
                } catch {
                    completion.onResult(success: false, value: error.localizedDescription)
                }
            }
        case SdkGlassesActions.shared.META_OPEN_META_AI:
            UIApplication.shared.open(Self.metaAiAppStoreUrl)
            completion.onResult(success: true, value: "")
        case SdkGlassesActions.shared.META_DIAGNOSTICS:
            completion.onResult(success: true, value: diagnostics())
        default:
            completion.onResult(success: false, value: "Meta glasses do not support \(action) on iOS")
        }
    }

    // MARK: - Registration and devices

    private func startObserving() {
        guard !observing else { return }
        observing = true
        Task { @MainActor in
            for await state in Wearables.shared.registrationStateStream() {
                self.onRegistration(state)
            }
        }
        Task { @MainActor in
            for await devices in Wearables.shared.devicesStream() {
                self.onDevices(devices)
            }
        }
    }

    private func register() {
        Task { @MainActor in
            do {
                try await Wearables.shared.startRegistration()
            } catch let error as RegistrationError {
                if case .metaAINotInstalled = error {
                    self.listener?.onStatus(key: SdkGlassesStatus.shared.META_AI_INSTALLED, value: "false")
                }
                self.report(error: error.localizedDescription)
            } catch {
                self.report(error: error.localizedDescription)
            }
        }
    }

    private func onRegistration(_ state: RegistrationState) {
        let label: String
        switch state {
        case .registered: label = "Registered"
        case .registering: label = "Registering"
        case .available: label = "Not registered"
        case .unavailable: label = "Unavailable"
        @unknown default: label = "Unknown"
        }
        listener?.onStatus(key: SdkGlassesStatus.shared.META_REGISTRATION, value: label)
        if state == .registered {
            listener?.onStatus(key: SdkGlassesStatus.shared.META_AI_INSTALLED, value: "true")
            // Registration finished in Meta AI: open the device session right away.
            if session == nil { startSession() }
        } else if session != nil {
            disconnect()
        }
    }

    private func onDevices(_ devices: [DeviceIdentifier]) {
        let name = devices.lazy.compactMap { Wearables.shared.deviceForIdentifier($0)?.nameOrId() }.first ?? ""
        listener?.onStatus(key: SdkGlassesStatus.shared.META_DEVICE_COUNT, value: String(devices.count))
        listener?.onStatus(key: SdkGlassesStatus.shared.META_DEVICE_NAME, value: name)
    }

    // MARK: - Device session

    private func startSession() {
        guard session == nil else { return }
        do {
            let newSession = try Wearables.shared.createSession(
                deviceSelector: AutoDeviceSelector(wearables: Wearables.shared)
            )
            session = newSession
            newSession.statePublisher.listen { [weak self] state in
                Task { @MainActor in self?.onSessionState(state) }
            }.store(in: sessionTokens)
            newSession.errorPublisher.listen { [weak self] error in
                Task { @MainActor in self?.report(error: error.localizedDescription) }
            }.store(in: sessionTokens)
            listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTING, label: "Connecting to Meta glasses")
            try newSession.start()
        } catch {
            session = nil
            sessionTokens.clear()
            finishConnect(success: false, message: error.localizedDescription)
        }
    }

    private func onSessionState(_ state: DeviceSessionState) {
        switch state {
        case .started:
            listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTED, label: "Meta glasses connected")
            finishConnect(success: true, message: deviceClassName)
        case .stopped:
            sessionTokens.clear()
            session = nil
            camera = nil
            listener?.onConnectionChanged(state: SdkConnectionState.shared.DISCONNECTED, label: "Meta glasses disconnected")
            finishConnect(success: false, message: lastError.isEmpty ? "The Meta glasses session stopped" : lastError)
        default:
            break
        }
    }

    private func ensureStartedSession() async throws {
        guard Wearables.shared.registrationState == .registered else {
            register()
            throw BridgeError("Approve CyanBridge in the Meta AI app first")
        }
        if session == nil { startSession() }
        for _ in 0..<60 where session?.state != .started {
            try await Task.sleep(nanoseconds: 250_000_000)
        }
        guard session?.state == .started else { throw BridgeError("The Meta glasses are not connected") }
    }

    // MARK: - Photo

    /// Starts a short stream, captures one JPEG and stops the camera again.
    private func startPhotoStream() throws {
        guard let session else { throw BridgeError("The Meta glasses are not connected") }
        camera?.stop()
        streamTokens.clear()
        let config = StreamConfiguration(videoCodec: .raw, resolution: .medium, frameRate: 15)
        guard let newCamera = try session.addCamera(config: config) else {
            throw BridgeError("Could not open the Meta glasses camera")
        }
        camera = newCamera
        let stream = newCamera.stream
        var requested = false
        stream.statePublisher.listen { [weak self] state in
            Task { @MainActor in
                guard let self, state == .streaming, !requested else { return }
                requested = true
                if !stream.capturePhoto(format: .jpeg) {
                    self.finishPhoto(nil, "The Meta glasses did not take the photo")
                }
            }
        }.store(in: streamTokens)
        stream.photoDataPublisher.listen { [weak self] photo in
            let data = photo.data
            Task { @MainActor in self?.finishPhoto(data, "") }
        }.store(in: streamTokens)
        stream.errorPublisher.listen { [weak self] error in
            Task { @MainActor in self?.finishPhoto(nil, error.localizedDescription) }
        }.store(in: streamTokens)
        stream.start()
    }

    private func finishPhoto(_ data: Data?, _ message: String) {
        let completion = pendingPhoto
        pendingPhoto = nil
        streamTokens.clear()
        camera?.stop()
        camera = nil
        completion?.onResult(data: data, message: message)
    }

    private func finishConnect(success: Bool, message: String) {
        let completion = pendingConnect
        pendingConnect = nil
        completion?.onResult(success: success, message: message)
    }

    private func report(error: String) {
        lastError = error
        listener?.onStatus(key: SdkGlassesStatus.shared.ERROR, value: error)
    }

    private func diagnostics() -> String {
        let devices = Wearables.shared.devices.compactMap { Wearables.shared.deviceForIdentifier($0)?.nameOrId() }
        return [
            "Registration: \(Wearables.shared.registrationState)",
            "Devices: \(devices.isEmpty ? "none" : devices.joined(separator: ", "))",
            "Session: \(session.map { "\($0.state)" } ?? "none")",
            "Last error: \(lastError.isEmpty ? "none" : lastError)",
        ].joined(separator: "\n")
    }
}

private struct BridgeError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
#endif
