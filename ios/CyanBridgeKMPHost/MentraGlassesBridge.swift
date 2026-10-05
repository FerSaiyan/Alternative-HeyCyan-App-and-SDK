import CyanBridgeShared
import Foundation
#if canImport(MentraBluetoothSDK)
import MentraBluetoothSDK
import Network

/// Mentra Live glasses through the Mentra Bluetooth SDK, the iOS counterpart of Android's
/// MentraLiveManager (same SDK version). The camera button becomes the assistant's AI button;
/// photos travel over BLE to a one-shot loopback receiver, as in Android's MentraPhotoCapture,
/// so they never leave the phone.
final class MentraGlassesBridge: NSObject, SdkGlassesBridge {
    static let shared = MentraGlassesBridge()

    private var listener: SdkGlassesListener?
    private var events: MentraEvents?
    private var sdkInstance: MentraBluetoothSDK?
    private var pendingConnect: SdkResultCallback?
    private var connectedId = ""

    private override init() {
        super.init()
    }

    var deviceClassName: String { "MENTRA_LIVE" }

    @MainActor
    private var sdk: MentraBluetoothSDK {
        if let sdkInstance { return sdkInstance }
        let created = MentraBluetoothSDK(
            configuration: MentraBluetoothSDKConfiguration(analytics: .disabled)
        )
        let delegate = MentraEvents(owner: self)
        events = delegate
        created.delegate = delegate
        sdkInstance = created
        return created
    }

    func setListener(listener: SdkGlassesListener?) {
        self.listener = listener
    }

    func connect(identifier: String, name: String, completion: SdkResultCallback) {
        pendingConnect?.onResult(success: false, message: "Replaced by a new connection")
        pendingConnect = completion
        Task { @MainActor in
            do {
                var started = false
                try self.sdk.scan(model: .mentraLive, timeout: 10, onResults: { [weak self] devices in
                    guard let self, !started else { return }
                    let match = devices.first { identifier.isEmpty || $0.id == identifier || $0.identifier == identifier }
                    guard let device = match else { return }
                    started = true
                    self.connectedId = device.id
                    do {
                        try self.sdk.connect(to: device)
                    } catch {
                        self.finishConnect(success: false, message: error.localizedDescription)
                    }
                }, onComplete: { [weak self] _ in
                    guard let self, !started else { return }
                    self.finishConnect(success: false, message: "Mentra Live not found. Turn the glasses on and retry.")
                })
            } catch {
                self.finishConnect(success: false, message: error.localizedDescription)
            }
        }
    }

    func disconnect() {
        Task { @MainActor in self.sdk.disconnect() }
    }

    func requestBattery() {
        Task { @MainActor in self.publish(self.sdk.glasses) }
    }

    func requestMediaCounts() {}

    func captureAiPhoto(completion: SdkPhotoCallback) {
        Task { @MainActor in
            do {
                let photo = try await self.capturePhoto()
                completion.onResult(data: photo, message: "")
            } catch {
                completion.onResult(data: nil, message: error.localizedDescription)
            }
        }
    }

    func perform(action: String, argument: String, completion: SdkTextCallback) {
        switch action {
        case SdkGlassesActions.shared.CAPTURE_PHOTO:
            Task { @MainActor in
                do {
                    _ = try await self.capturePhoto()
                    completion.onResult(success: true, value: "Photo taken")
                } catch {
                    completion.onResult(success: false, value: error.localizedDescription)
                }
            }
        default:
            completion.onResult(success: false, value: "Mentra Live does not support \(action) on iOS")
        }
    }

    // MARK: - SDK events

    @MainActor
    fileprivate func publish(_ glasses: GlassesRuntimeState) {
        if glasses.ready {
            listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTED, label: "Mentra Live connected")
            finishConnect(success: true, message: connectedId)
        } else if glasses.connected {
            listener?.onConnectionChanged(state: SdkConnectionState.shared.CONNECTING, label: "Connecting to Mentra Live")
        } else {
            listener?.onConnectionChanged(state: SdkConnectionState.shared.DISCONNECTED, label: "Mentra Live disconnected")
        }
        if let battery = glasses.battery, let level = battery.level {
            listener?.onBattery(level: Int32(level), charging: battery.charging)
        }
    }

    @MainActor
    fileprivate func onButton(_ event: ButtonPressEvent) {
        listener?.onButton(gesture: event.pressType)
    }

    @MainActor
    fileprivate func onFailure(_ message: String) {
        listener?.onStatus(key: SdkGlassesStatus.shared.ERROR, value: message)
    }

    private func finishConnect(success: Bool, message: String) {
        let completion = pendingConnect
        pendingConnect = nil
        completion?.onResult(success: success, message: message)
    }

    // MARK: - Photo over BLE to a loopback receiver (Android: MentraPhotoCapture)

    @MainActor
    private func capturePhoto() async throws -> Data {
        guard sdk.glasses.ready else { throw MentraBridgeError("Mentra Live is not ready for a photo") }
        let requestId = UUID().uuidString
        let token = UUID().uuidString
        let receiver = try LoopbackPhotoReceiver(token: token, requestId: requestId)
        defer { receiver.stop() }
        let port = try await receiver.start()
        let request = PhotoRequest(
            requestId: requestId,
            size: .medium,
            webhookUrl: "http://127.0.0.1:\(port)/\(token)",
            compress: .medium,
            save: false,
            sound: true,
            transferMethod: "ble"
        )
        _ = try await sdk.requestPhoto(request)
        return try await receiver.photo(timeout: 90)
    }
}

/// The SDK's delegate is main-actor isolated; this forwards to the bridge.
@MainActor
private final class MentraEvents: MentraBluetoothSDKDelegate {
    private weak var owner: MentraGlassesBridge?

    init(owner: MentraGlassesBridge) {
        self.owner = owner
    }

    func mentraBluetoothSDK(_ sdk: MentraBluetoothSDK, didUpdateGlasses glasses: GlassesRuntimeState) {
        owner?.publish(glasses)
    }

    func mentraBluetoothSDK(_ sdk: MentraBluetoothSDK, didReceive event: BluetoothEvent) {
        if case let .buttonPress(press) = event {
            owner?.onButton(press)
        }
    }

    func mentraBluetoothSDK(_ sdk: MentraBluetoothSDK, didFail error: BluetoothSdkError) {
        owner?.onFailure(error.localizedDescription)
    }
}

/// Accepts exactly one multipart POST on 127.0.0.1 and returns the JPEG in its "photo" field.
private final class LoopbackPhotoReceiver {
    private static let maxUploadBytes = 6 * 1024 * 1024

    private let token: String
    private let requestId: String
    private let listener: NWListener
    private let queue = DispatchQueue(label: "cyanbridge.mentra.photo")
    private var buffer = Data()
    private var continuation: CheckedContinuation<Data, Error>?
    private var result: Result<Data, Error>?

    init(token: String, requestId: String) throws {
        self.token = token
        self.requestId = requestId
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    /// Starts listening and returns the chosen port.
    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { (ready: CheckedContinuation<UInt16, Error>) in
            var resumed = false
            listener.stateUpdateHandler = { [weak self] state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    ready.resume(returning: self?.listener.port?.rawValue ?? 0)
                case let .failed(error):
                    resumed = true
                    ready.resume(throwing: error)
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.start(queue: queue)
        }
    }

    func photo(timeout seconds: Double) async throws -> Data {
        try await withThrowingTaskGroup(of: Data.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { continuation in
                    self.queue.async {
                        if let result = self.result {
                            continuation.resume(with: result)
                        } else {
                            self.continuation = continuation
                        }
                    }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw MentraBridgeError("The Mentra photo did not arrive")
            }
            let photo = try await group.next()!
            group.cancelAll()
            return photo
        }
    }

    func stop() {
        listener.cancel()
        queue.async {
            if self.result == nil { self.finish(.failure(MentraBridgeError("Photo receiver stopped"))) }
        }
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(on: connection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data { self.buffer.append(data) }
            if let photo = self.parseIfComplete() {
                self.respond(on: connection, status: "200 OK", body: #"{"ok":true}"#)
                self.finish(photo)
            } else if isComplete || error != nil || self.buffer.count > Self.maxUploadBytes + 16 * 1024 {
                self.respond(on: connection, status: "400 Bad Request", body: "")
                self.finish(.failure(MentraBridgeError("Incomplete photo upload")))
            } else {
                self.receive(on: connection)
            }
        }
    }

    /// nil while more bytes are needed.
    private func parseIfComplete() -> Result<Data, Error>? {
        guard let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let headers = String(decoding: buffer[..<headerEnd.lowerBound], as: UTF8.self)
        let lines = headers.components(separatedBy: "\r\n")
        guard lines.first == "POST /\(token) HTTP/1.1" else {
            return .failure(MentraBridgeError("Unexpected photo upload request"))
        }
        func header(_ name: String) -> String? {
            lines.first { $0.lowercased().hasPrefix(name + ":") }
                .map { String($0.dropFirst(name.count + 1)).trimmingCharacters(in: .whitespaces) }
        }
        guard let length = header("content-length").flatMap(Int.init), length > 0, length <= Self.maxUploadBytes,
              let contentType = header("content-type"), contentType.lowercased().hasPrefix("multipart/form-data"),
              let boundaryRange = contentType.range(of: "boundary=")
        else { return .failure(MentraBridgeError("Malformed photo upload")) }
        let boundary = contentType[boundaryRange.upperBound...].trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
        let bodyStart = headerEnd.upperBound
        guard buffer.count - bodyStart >= length else { return nil }
        let body = buffer.subdata(in: bodyStart..<(bodyStart + length))
        guard let receivedId = field("requestId", in: body, boundary: boundary),
              String(decoding: receivedId, as: UTF8.self) == requestId
        else { return .failure(MentraBridgeError("Photo request ID mismatch")) }
        guard let jpeg = field("photo", in: body, boundary: boundary), jpeg.count >= 4,
              jpeg.prefix(2) == Data([0xFF, 0xD8]), jpeg.suffix(2) == Data([0xFF, 0xD9])
        else { return .failure(MentraBridgeError("Invalid JPEG in the photo upload")) }
        return .success(jpeg)
    }

    private func field(_ name: String, in body: Data, boundary: String) -> Data? {
        guard let nameRange = body.range(of: Data("name=\"\(name)\"".utf8)),
              let separator = body.range(of: Data("\r\n\r\n".utf8), in: nameRange.upperBound..<body.endIndex),
              let end = body.range(of: Data("\r\n--\(boundary)".utf8), in: separator.upperBound..<body.endIndex)
        else { return nil }
        return body.subdata(in: separator.upperBound..<end.lowerBound)
    }

    private func respond(on connection: NWConnection, status: String, body: String) {
        let response = "HTTP/1.1 \(status)\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
    }

    private func finish(_ outcome: Result<Data, Error>) {
        guard result == nil else { return }
        result = outcome
        continuation?.resume(with: outcome)
        continuation = nil
        listener.cancel()
    }
}

private struct MentraBridgeError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
#endif
