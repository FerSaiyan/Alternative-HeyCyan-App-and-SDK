// The XCTest that Google Device Run executes inside the host app. Inputs arrive in the app's
// Documents folder (gcloud --other-files-to-push), results are pulled from Documents/bench_out.
// Rows that fail (a config that does not compile, wrong numerics) are data, not test failures; the
// test fails only when nothing could run at all.

import XCTest
@testable import LiteRtGpuBench

final class LiteRtGpuBenchTests: XCTestCase {
  /// Device facts (model id, iOS, GPU, memory, thermal state) into bench_out/device.json.
  func test01_device() throws {
    let runner = BenchRunner()
    let info = try runner.writeDeviceInfo()
    print("LRTBENCH device \(info)")
    XCTAssertTrue(runner.metalAvailable, "no Metal device")
  }

  /// The plan in bench_plan.json: every model under every config, rows in bench_out/results.jsonl.
  func test02_plan() throws {
    let runner = BenchRunner()
    let summary = try runner.runPlan()
    print("LRTBENCH summary rows=\(summary.rows) errors=\(summary.errors) out=\(summary.outPath)")
    XCTAssertGreaterThan(summary.rows, 0, "no (model, config) row ran; see bench_out/results.jsonl")
  }
}
