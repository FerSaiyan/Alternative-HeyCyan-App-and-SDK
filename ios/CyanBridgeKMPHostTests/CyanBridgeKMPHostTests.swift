import XCTest
@testable import CyanBridgeKMPHost

final class CyanBridgeKMPHostTests: XCTestCase {

    func testAppLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.state == .runningForeground, "App should launch to foreground")
    }

    /// Downloads the shared catalog's LiteRT models (YOLO11n, PP-OCRv6 det/rec + dictionary, WeSpeaker) from Hugging
    /// Face and runs each on the CPU and on its accelerator (Metal GPU where the catalog says so). Needs network.
    /// The report is printed with the prefix LITERT_SELFTEST and saved to Documents/litert-selftest.txt.
    func testLiteRtModelsDownloadAndRun() {
        let done = expectation(description: "LiteRT self-test")
        var report = ""
        LiteRtModelBridgeImpl.runSelfTest { text in
            report = text
            done.fulfill()
        }
        wait(for: [done], timeout: 900)
        for line in report.split(separator: "\n") { print("LITERT_SELFTEST \(line)") }
        XCTAssertTrue(report.hasPrefix("PASS"), report)
    }
}
