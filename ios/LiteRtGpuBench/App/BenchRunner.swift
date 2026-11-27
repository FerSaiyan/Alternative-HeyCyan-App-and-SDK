// CPU vs Metal GPU sweep over LiteRT models, the iOS port of the Android GpuSweepTest
// (android/CyanBridge/app/src/androidTest/java/com/fersaiyan/cyanbridge/hil/GpuSweepTest.kt).
//
// Reads bench_plan.json from the bench directory (the app's Documents folder, or $BENCH_DIR when
// set, which the simulator smoke test uses), runs every model under every config and writes one
// JSON row per (model, config) to <bench dir>/bench_out/results.jsonl. Every row is also logged
// with the prefix "LRTBENCH " so it reaches the device syslog even if the pull fails.
//
// bench_plan.json:
//   {
//     "configs": ["cpu", "gpu:FP32", "gpu:FP16", "gpu:FP16:cache"],
//     "iters": 20, "warmup": 3, "count": 4, "save_outputs": false, "synthetic_inputs": 2,
//     "models": [ {"file": "m.tflite", "inputs": ["a.f32", "b.rgb"], "ref": "m_fp32.tflite",
//                  "configs": [...optional per-model override...]} ]
//   }
//   File names are relative to the bench directory. An input file is used as float32 when its size
//   is elements * 4 bytes, or as uint8 pixels / 255 when its size is the element count (.rgb). A
//   model without inputs gets `synthetic_inputs` deterministic uniform [0, 1) inputs.
//
// Config tokens (":"-separated):
//   cpu                 CPU (XNNPACK); "t4" sets 4 threads.
//   gpu                 Metal GPU only (non-delegated ops still run on the CPU kernels);
//                       "gc" asks for [gpu, cpu] like Google's sample.
//   DEFAULT|FP16|FP32|FP16_WITH_FP32_ACCUM   GPU precision.
//   tex | buf           buffer_storage_type texture2D / buffer.
//   cache               program cache in a fresh dir: compiled twice (cold create_ms, warm create_warm_ms).
//   nores               enable_metal_residency_set = false.
//
// Per row: create_ms, first_ms, median/p90 of write+run+read and of run alone, the input buffer
// types the compiled model asks for, fully_accelerated, thermal state, and per output vs this
// model's own CPU run (and vs the "ref" model's CPU run): min cosine, max abs diff, Jaccard of the
// values >= 0.35 for outputs in [0, 1]. Same keys as the Android sweep so its table script works.

import Foundation
import LiteRT
import Metal

struct BenchPlanModel: Decodable {
  let file: String
  let inputs: [String]?
  let ref: String?
  let configs: [String]?
}

struct BenchPlan: Decodable {
  let models: [BenchPlanModel]
  let configs: [String]
  let iters: Int?
  let warmup: Int?
  let count: Int?
  let save_outputs: Bool?
  let synthetic_inputs: Int?
}

struct BenchSummary {
  var rows = 0
  var errors = 0
  var outPath = ""
}

enum BenchError: Error, CustomStringConvertible {
  case noPlan(String)
  case badModel(String)
  case badInput(String)

  var description: String {
    switch self {
    case .noPlan(let s): return "no plan: \(s)"
    case .badModel(let s): return "bad model: \(s)"
    case .badInput(let s): return "bad input: \(s)"
    }
  }
}

private struct OutputMeta {
  let name: String
  let elements: Int
  let elementType: ElementType
}

private struct ModelMeta {
  let inputName: String
  let inputElements: Int
  let outputs: [OutputMeta]
}

final class BenchRunner {
  static let planName = "bench_plan.json"
  static let logPrefix = "LRTBENCH "

  let benchDir: URL
  let outDir: URL
  private var jsonl: FileHandle?
  /// Environment options that made the Metal accelerator load ("default" or "frameworks_dir").
  private var envMode = "default"

  init() {
    if let dir = ProcessInfo.processInfo.environment["BENCH_DIR"], !dir.isEmpty {
      benchDir = URL(fileURLWithPath: dir, isDirectory: true)
    } else {
      benchDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    outDir = benchDir.appendingPathComponent("bench_out", isDirectory: true)
  }

  // MARK: - Entry points

  /// Writes bench_out/device.json and returns it. Does not need a plan.
  @discardableResult
  func writeDeviceInfo() throws -> [String: Any] {
    try openOutput()
    let info = deviceInfo()
    let data = try JSONSerialization.data(
      withJSONObject: sanitize(info), options: [.prettyPrinted, .sortedKeys])
    try data.write(to: outDir.appendingPathComponent("device.json"))
    emit("device", info)
    return info
  }

  var metalAvailable: Bool { MTLCreateSystemDefaultDevice() != nil }

  func runPlan() throws -> BenchSummary {
    try openOutput()
    let planURL = benchDir.appendingPathComponent(Self.planName)
    guard let planData = try? Data(contentsOf: planURL) else {
      emit("error", ["error": "missing \(planURL.path)"])
      throw BenchError.noPlan("missing \(planURL.path)")
    }
    let plan = try JSONDecoder().decode(BenchPlan.self, from: planData)
    let iters = max(1, plan.iters ?? 20)
    let warmup = max(0, plan.warmup ?? 3)
    let count = max(1, plan.count ?? 4)
    var summary = BenchSummary(outPath: outDir.path)
    emit("plan", [
      "models": plan.models.map { $0.file }, "configs": plan.configs, "iters": iters,
      "warmup": warmup, "count": count, "bench_dir": benchDir.path,
    ])
    emit("device", deviceInfo())
    probeGpu(plan: plan)

    for model in plan.models {
      autoreleasepool {
        let url = benchDir.appendingPathComponent(model.file)
        do {
          let meta = try readMeta(url)
          let inputs = try loadInputs(model: model, meta: meta, count: count,
                                      synthetic: plan.synthetic_inputs ?? 2)
          emit("model", [
            "model": model.file, "bytes": fileSize(url),
            "input": "\(meta.inputName) FLOAT32 \(meta.inputElements)",
            "inputs": inputs.map { $0.name },
            "outputs": meta.outputs.map { "\($0.name):\($0.elementType):\($0.elements)" },
          ])
          let values = inputs.map { $0.values }
          let ownCpu = try runAll(url: url, meta: meta, inputs: values, config: "cpu")
          var refCpu: [[[Float]]]? = nil
          if let ref = model.ref {
            let refURL = benchDir.appendingPathComponent(ref)
            do {
              let refMeta = try readMeta(refURL)
              if refMeta.outputs.map({ $0.elements }) == meta.outputs.map({ $0.elements }) {
                refCpu = try runAll(url: refURL, meta: refMeta, inputs: values, config: "cpu")
              } else {
                emit("error", ["model": model.file, "error": "ref \(ref) has other output sizes"])
              }
            } catch {
              emit("error", ["model": model.file, "error": "ref \(ref): \(error)"])
            }
          }
          for config in model.configs ?? plan.configs {
            autoreleasepool {
              do {
                var row = try sweepOne(url: url, meta: meta, inputs: values, config: config,
                                       iters: iters, warmup: warmup, ownCpu: ownCpu, refCpu: refCpu,
                                       saveOutputs: plan.save_outputs ?? false)
                row["model"] = model.file
                emit("run", row)
                summary.rows += 1
              } catch {
                emit("run", ["model": model.file, "config": config, "error": "\(error)"])
                summary.errors += 1
              }
            }
          }
        } catch {
          emit("run", ["model": model.file, "config": "*", "error": "\(error)"])
          summary.errors += 1
        }
      }
    }
    emit("done", ["rows": summary.rows, "errors": summary.errors, "out": outDir.path])
    try? jsonl?.synchronize()
    return summary
  }

  // MARK: - One (model, config)

  private func sweepOne(
    url: URL, meta: ModelMeta, inputs: [[Float]], config: String, iters: Int, warmup: Int,
    ownCpu: [[[Float]]], refCpu: [[[Float]]]?, saveOutputs: Bool
  ) throws -> [String: Any] {
    let tokens = config.split(separator: ":").map(String.init)
    var cacheDir: URL? = nil
    if tokens.contains("cache") {
      let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("bench-cache", isDirectory: true)
        .appendingPathComponent(
          url.deletingPathExtension().lastPathComponent + "-" + config.replacingOccurrences(of: ":", with: "_"),
          isDirectory: true)
      try? FileManager.default.removeItem(at: dir)
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
      cacheDir = dir
    }
    defer { if let cacheDir { try? FileManager.default.removeItem(at: cacheDir) } }

    var row: [String: Any] = ["config": config, "inputs": inputs.count, "env_mode": envMode]
    row["thermal_before"] = ProcessInfo.processInfo.thermalState.rawValue
    let env = try makeEnvironment()
    var t = now()
    var compiled: CompiledModel? = try CompiledModel(
      filePath: url.path, environment: env,
      options: try makeOptions(tokens: tokens, modelURL: url, cacheDir: cacheDir))
    row["create_ms"] = ms(since: t)
    if cacheDir != nil {
      compiled = nil
      t = now()
      compiled = try CompiledModel(
        filePath: url.path, environment: env,
        options: try makeOptions(tokens: tokens, modelURL: url, cacheDir: cacheDir))
      row["create_warm_ms"] = ms(since: t)
      row["cache_files"] = (try? FileManager.default.contentsOfDirectory(atPath: cacheDir!.path).count) ?? 0
    }
    guard let model = compiled else { throw BenchError.badModel("compile returned nil") }
    compiled = nil
    row["fully_accelerated"] = opt(try? model.isFullyAccelerated())
    if tokens.first != "cpu" {
      row["gpu_env"] = env.hasGpuEnvironment()
    }
    if let req = try? model.inputBufferRequirements(inputIndex: 0) {
      row["input_buffer_types"] = req.supportedTypes.map { "\($0)" }.joined(separator: "|")
    }
    // Tensor buffers must die before the compiled model and the environment (Metal resources), also
    // when a run throws: they live only inside measure(), which runs while env and model are pinned.
    let timed = try withExtendedLifetime(env) {
      try withExtendedLifetime(model) {
        try measure(model: model, meta: meta, inputs: inputs, iters: iters, warmup: warmup)
      }
    }
    for (key, value) in timed.row { row[key] = value }
    let results = timed.results
    row["vs_cpu"] = compare(meta: meta, got: results, want: ownCpu)
    if let refCpu { row["vs_ref"] = compare(meta: meta, got: results, want: refCpu) }
    row["thermal_after"] = ProcessInfo.processInfo.thermalState.rawValue
    if saveOutputs, let first = results.first {
      let stem = url.deletingPathExtension().lastPathComponent + "__" + config.replacingOccurrences(of: ":", with: "_")
      for (o, values) in first.enumerated() {
        let data = values.withUnsafeBufferPointer { Data(buffer: $0) }
        try? data.write(to: outDir.appendingPathComponent("\(stem)__in0__out\(o).f32"))
      }
    }
    return row
  }

  /// Runs every input once (numerics, first run timed), then warm-up and timed iterations on input 0.
  private func measure(model: CompiledModel, meta: ModelMeta, inputs: [[Float]], iters: Int, warmup: Int)
    throws -> (row: [String: Any], results: [[[Float]]])
  {
    var row: [String: Any] = [:]
    let ins: [TensorBuffer] = try model.createInputBuffers()
    let outs: [TensorBuffer] = try model.createOutputBuffers()
    row["input_buffer_type"] = "\(ins[0].type)"
    row["output_buffer_types"] = outs.map { "\($0.type)" }.joined(separator: "|")

    var results: [[[Float]]] = []
    for (k, x) in inputs.enumerated() {
      let t = now()
      try ins[0].write(x)
      try model.run(inputs: ins, outputs: outs)
      results.append(try readOutputs(outs, meta: meta))
      if k == 0 { row["first_ms"] = ms(since: t) }
    }
    let x = inputs[0]
    for _ in 0..<warmup {
      try ins[0].write(x)
      try model.run(inputs: ins, outputs: outs)
      _ = try readOutputs(outs, meta: meta)
    }
    var total: [Double] = []
    var runOnly: [Double] = []
    for _ in 0..<iters {
      let t0 = now()
      try ins[0].write(x)
      let t1 = now()
      try model.run(inputs: ins, outputs: outs)
      let t2 = now()
      _ = try readOutputs(outs, meta: meta)
      total.append(Double(now() - t0) / 1e6)
      runOnly.append(Double(t2 - t1) / 1e6)
    }
    row["median_ms"] = pct(total, 50)
    row["p90_ms"] = pct(total, 90)
    row["run_median_ms"] = pct(runOnly, 50)
    row["run_p90_ms"] = pct(runOnly, 90)
    return (row, results)
  }

  /// Outputs of every input under one config (used for the CPU references).
  private func runAll(url: URL, meta: ModelMeta, inputs: [[Float]], config: String) throws -> [[[Float]]] {
    let tokens = config.split(separator: ":").map(String.init)
    let env = try makeEnvironment()
    let model = try CompiledModel(
      filePath: url.path, environment: env,
      options: try makeOptions(tokens: tokens, modelURL: url, cacheDir: nil))
    return try withExtendedLifetime(env) {
      try withExtendedLifetime(model) {
        try measure(model: model, meta: meta, inputs: inputs, iters: 1, warmup: 0).results
      }
    }
  }

  // MARK: - LiteRT setup

  private func makeEnvironment() throws -> Environment {
    if envMode == "frameworks_dir", let dir = Self.metalFrameworkDir() {
      return try Environment(options: [.runtimeLibraryDir(dir)])
    }
    return try Environment()
  }

  /// The embedded accelerator: <App>.app/Frameworks/LiteRtMetalAccelerator.framework. LiteRT tries
  /// "<runtime dir>/LiteRtMetalAccelerator", so the framework folder itself is the runtime dir.
  static func metalFrameworkDir() -> String? {
    guard let frameworks = Bundle.main.privateFrameworksPath else { return nil }
    let dir = (frameworks as NSString).appendingPathComponent("LiteRtMetalAccelerator.framework")
    return FileManager.default.fileExists(atPath: dir) ? dir : nil
  }

  private func makeOptions(tokens: [String], modelURL: URL, cacheDir: URL?) throws -> Options {
    let options = try Options()
    if tokens.first == "cpu" {
      try options.setHardwareAccelerators([.cpu])
      if let t = tokens.first(where: { $0.hasPrefix("t") }), let n = Int(t.dropFirst()) {
        let cpu = try CpuOptions()
        try cpu.setNumThreads(n)
        try options.addConcreteOptions(cpu)
      }
      return options
    }
    try options.setHardwareAccelerators(tokens.contains("gc") ? [.gpu, .cpu] : [.gpu])
    // GPU options travel as a TOML string under the "gpu_options" identifier; the Metal accelerator
    // parses it (LrtCreateGpuOptionsFromToml in litert/c/options/litert_gpu_options.cc, v2.4.0).
    var toml: [String] = []
    let precisions = ["DEFAULT": 0, "FP16": 1, "FP32": 2, "FP16_WITH_FP32_ACCUM": 3]
    if let p = tokens.compactMap({ precisions[$0] }).first {
      toml.append("precision = \(p)")
    }
    if tokens.contains("tex") { toml.append("buffer_storage_type = 2") }
    if tokens.contains("buf") { toml.append("buffer_storage_type = 1") }
    if tokens.contains("nores") { toml.append("enable_metal_residency_set = false") }
    if let cacheDir {
      toml.append("serialization_dir = \"\(cacheDir.path)\"")
      toml.append("model_cache_key = \"\(modelURL.lastPathComponent)-\(fileSize(modelURL))\"")
      toml.append("serialize_program_cache = true")
    }
    if !toml.isEmpty {
      guard let payload = strdup(toml.joined(separator: "\n")) else {
        throw BenchError.badModel("strdup failed")
      }
      let opaque = try OpaqueOptions(
        identifier: "gpu_options", payload: UnsafeMutableRawPointer(payload),
        destructor: { pointer in free(pointer) })
      try options.addOpaqueOptions(opaque)
    }
    return options
  }

  /// Finds the environment options under which a GPU compile of the first model works.
  private func probeGpu(plan: BenchPlan) {
    guard let first = plan.models.first else { return }
    let url = benchDir.appendingPathComponent(first.file)
    var attempts: [[String: Any]] = []
    var compiledModes: [String] = []
    for mode in ["default", "frameworks_dir"] {
      if mode == "frameworks_dir" && Self.metalFrameworkDir() == nil {
        attempts.append(["mode": mode, "error": "no LiteRtMetalAccelerator.framework in app Frameworks"])
        continue
      }
      envMode = mode
      do {
        let env = try makeEnvironment()
        let t = now()
        let model = try CompiledModel(
          filePath: url.path, environment: env,
          options: try makeOptions(tokens: ["gpu"], modelURL: url, cacheDir: nil))
        let gpuEnv = env.hasGpuEnvironment()
        var attempt: [String: Any] = ["mode": mode, "create_ms": ms(since: t)]
        attempt["fully_accelerated"] = opt(try? model.isFullyAccelerated())
        attempt["gpu_env"] = gpuEnv
        attempt["fp16"] = opt(try? env.supportsFP16())
        attempts.append(attempt)
        compiledModes.append(mode)
        // A compile without a GPU environment may have quietly run on the CPU: try the other mode.
        if gpuEnv {
          emit("gpu_probe", ["model": first.file, "attempts": attempts, "chosen": mode])
          return
        }
      } catch {
        attempts.append(["mode": mode, "error": "\(error)"])
      }
    }
    envMode = compiledModes.first ?? "default"
    emit("gpu_probe", ["model": first.file, "attempts": attempts, "chosen": opt(compiledModes.first),
                       "note": "no attempt reported a GPU environment"])
  }

  private func readMeta(_ url: URL) throws -> ModelMeta {
    guard FileManager.default.fileExists(atPath: url.path) else {
      throw BenchError.badModel("missing \(url.lastPathComponent)")
    }
    let env = try Environment()
    let options = try Options()
    try options.setHardwareAccelerators([.cpu])
    let model = try CompiledModel(filePath: url.path, environment: env, options: options)
    let inCount = try model.inputCount()
    guard inCount == 1 else { throw BenchError.badModel("\(inCount) inputs; only 1 is supported") }
    let inType = try model.inputTensorType(inputIndex: 0)
    guard inType.elementType == .float32 else {
      throw BenchError.badModel("input type \(inType.elementType); only float32 is supported")
    }
    let inLayout = try model.inputTensorLayout(inputIndex: 0)
    guard let inElements = inLayout.elementCount, inElements > 0 else {
      throw BenchError.badModel("dynamic input shape \(inLayout.dimensions)")
    }
    var outputs: [OutputMeta] = []
    for o in 0..<(try model.outputCount()) {
      let type = try model.outputTensorType(outputIndex: o)
      outputs.append(OutputMeta(
        name: (try? model.outputName(outputIndex: o)) ?? "out\(o)",
        elements: type.layout.elementCount ?? -1,
        elementType: type.elementType))
    }
    return ModelMeta(
      inputName: (try? model.inputName(inputIndex: 0)) ?? "in0",
      inputElements: inElements, outputs: outputs)
  }

  private func readOutputs(_ buffers: [TensorBuffer], meta: ModelMeta) throws -> [[Float]] {
    var result: [[Float]] = []
    for (i, buffer) in buffers.enumerated() {
      let type = i < meta.outputs.count ? meta.outputs[i].elementType : .float32
      switch type {
      case .float32:
        let values: [Float] = try buffer.read()
        result.append(values)
      case .int32:
        let raw: [Int32] = try buffer.read()
        result.append(raw.map { Float($0) })
      default:
        result.append([])
      }
    }
    return result
  }

  // MARK: - Inputs

  private func loadInputs(model: BenchPlanModel, meta: ModelMeta, count: Int, synthetic: Int)
    throws -> [(name: String, values: [Float])]
  {
    let n = meta.inputElements
    var inputs: [(name: String, values: [Float])] = []
    for name in (model.inputs ?? []).prefix(count) {
      let url = benchDir.appendingPathComponent(name)
      guard let data = try? Data(contentsOf: url) else { throw BenchError.badInput("missing \(name)") }
      if data.count == n * 4 {
        var values = [Float](repeating: 0, count: n)
        _ = values.withUnsafeMutableBytes { data.copyBytes(to: $0) }
        inputs.append((name, values))
      } else if data.count == n {
        inputs.append((name, data.map { Float($0) / 255 }))
      } else {
        throw BenchError.badInput("\(name) has \(data.count) bytes; model input has \(n) elements")
      }
    }
    if inputs.isEmpty {
      var state: UInt64 = 0x9E37_79B9_7F4A_7C15
      for k in 0..<max(1, synthetic) {
        var values = [Float](repeating: 0, count: n)
        for j in 0..<n {
          state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
          values[j] = Float(state >> 40) / Float(1 << 24)
        }
        inputs.append(("synthetic\(k)", values))
      }
    }
    return inputs
  }

  // MARK: - Metrics

  private func compare(meta: ModelMeta, got: [[[Float]]], want: [[[Float]]]) -> [[String: Any]] {
    var rows: [[String: Any]] = []
    for o in meta.outputs.indices {
      var minCos = 1.0
      var maxDiff = 0.0
      var jaccardSum = 0.0
      var jaccardN = 0
      var compared = 0
      for k in got.indices where k < want.count {
        guard o < got[k].count, o < want[k].count else { continue }
        let g = got[k][o]
        let w = want[k][o]
        guard g.count == w.count, !g.isEmpty else { continue }
        compared += 1
        minCos = min(minCos, cosine(g, w))
        var inRange = true
        var inter = 0
        var union = 0
        for j in 0..<g.count {
          let d = Double(abs(g[j] - w[j]))
          if d > maxDiff || d.isNaN { maxDiff = d.isNaN ? .nan : d }
          if w[j] < -1e-3 || w[j] > 1.001 { inRange = false }
          let a = g[j] >= 0.35
          let b = w[j] >= 0.35
          if a && b { inter += 1 }
          if a || b { union += 1 }
        }
        if inRange {
          jaccardSum += union == 0 ? 1.0 : Double(inter) / Double(union)
          jaccardN += 1
        }
      }
      rows.append([
        "output": meta.outputs[o].name,
        "min_cos": opt(compared > 0 ? minCos : nil),
        "max_abs_diff": opt(compared > 0 ? maxDiff : nil),
        "jaccard035": opt(jaccardN > 0 ? jaccardSum / Double(jaccardN) : nil),
      ])
    }
    return rows
  }

  private func cosine(_ a: [Float], _ b: [Float]) -> Double {
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for i in 0..<a.count {
      let x = Double(a[i])
      let y = Double(b[i])
      dot += x * y
      na += x * x
      nb += y * y
    }
    if na == 0 && nb == 0 { return 1 }
    if na == 0 || nb == 0 { return 0 }
    return dot / (na.squareRoot() * nb.squareRoot())
  }

  private func pct(_ values: [Double], _ p: Int) -> Double {
    let sorted = values.sorted()
    let index = min(sorted.count - 1, max(0, Int((Double(p) / 100 * Double(sorted.count - 1)).rounded())))
    return sorted[index]
  }

  // MARK: - Output

  private func openOutput() throws {
    if jsonl != nil { return }
    try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
    let path = outDir.appendingPathComponent("results.jsonl")
    if !FileManager.default.fileExists(atPath: path.path) {
      FileManager.default.createFile(atPath: path.path, contents: nil)
    }
    jsonl = try FileHandle(forWritingTo: path)
    _ = jsonl?.seekToEndOfFile()
  }

  private func emit(_ event: String, _ fields: [String: Any]) {
    var object = fields
    object["event"] = event
    object["t"] = Date().timeIntervalSince1970
    guard JSONSerialization.isValidJSONObject(sanitize(object)),
          let data = try? JSONSerialization.data(withJSONObject: sanitize(object), options: [.sortedKeys]),
          let line = String(data: data, encoding: .utf8)
    else {
      NSLog("%@", "\(Self.logPrefix){\"event\":\"\(event)\",\"error\":\"unserializable row\"}")
      return
    }
    jsonl?.write((line + "\n").data(using: .utf8)!)
    NSLog("%@", Self.logPrefix + line)
  }

  /// JSONSerialization rejects NaN/Inf; write them as strings.
  private func sanitize(_ value: Any) -> Any {
    switch value {
    case let d as Double: return d.isFinite ? d : "\(d)"
    case let f as Float: return f.isFinite ? Double(f) : "\(f)"
    case let dict as [String: Any]: return dict.mapValues { sanitize($0) }
    case let array as [Any]: return array.map { sanitize($0) }
    default: return value
    }
  }

  private func deviceInfo() -> [String: Any] {
    var sys = utsname()
    uname(&sys)
    let machine = withUnsafePointer(to: &sys.machine) {
      $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
    }
    let info = ProcessInfo.processInfo
    var out: [String: Any] = [
      "machine": machine,
      "os": info.operatingSystemVersionString,
      "physical_memory_mb": Int(info.physicalMemory / 1_048_576),
      "cpu_count": info.activeProcessorCount,
      "low_power": info.isLowPowerModeEnabled,
      "thermal": info.thermalState.rawValue,
      "litert_swift": "2.4.0",
      "metal_framework_dir": opt(Self.metalFrameworkDir()),
      "bench_dir": benchDir.path,
    ]
    #if targetEnvironment(simulator)
      out["simulator"] = true
    #endif
    if let device = MTLCreateSystemDefaultDevice() {
      out["gpu"] = device.name
      out["gpu_max_working_set_mb"] = Int(device.recommendedMaxWorkingSetSize / 1_048_576)
      if #available(iOS 17.0, *) {
        out["gpu_apple9"] = device.supportsFamily(.apple9)
      }
    }
    if let build = Bundle.main.object(forInfoDictionaryKey: "BenchBuildInfo") {
      out["build"] = build
    }
    return out
  }

  private func fileSize(_ url: URL) -> Int {
    ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.intValue ?? -1
  }

  private func now() -> UInt64 { DispatchTime.now().uptimeNanoseconds }

  /// Optional to a JSON value (NSNull for nil).
  private func opt(_ value: Any?) -> Any { value ?? NSNull() }

  private func ms(since start: UInt64) -> Double { Double(now() - start) / 1e6 }
}
