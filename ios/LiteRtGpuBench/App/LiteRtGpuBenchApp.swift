// Host app for the LiteRT GPU bench. Device Run runs the XCTest inside this app; the screen is only
// for a manual check on a phone: copy bench_plan.json, models and inputs into the app's Documents
// (Finder / Files, file sharing is on) and tap "Run plan", or launch with the argument -autorun.

import SwiftUI

@main
struct LiteRtGpuBenchApp: App {
  var body: some Scene {
    WindowGroup {
      ContentView()
    }
  }
}

struct ContentView: View {
  @State private var status = "Idle"
  @State private var running = false

  var body: some View {
    VStack(spacing: 16) {
      Text("LiteRT GPU Bench").font(.title2)
      Text(status)
        .font(.footnote.monospaced())
        .multilineTextAlignment(.center)
        .padding(.horizontal)
      Button(running ? "Running..." : "Run plan") { run() }
        .buttonStyle(.borderedProminent)
        .disabled(running)
    }
    .onAppear {
      if ProcessInfo.processInfo.arguments.contains("-autorun") { run() }
    }
  }

  private func run() {
    running = true
    status = "Running..."
    DispatchQueue.global(qos: .userInitiated).async {
      let text: String
      do {
        let summary = try BenchRunner().runPlan()
        text = "rows \(summary.rows), errors \(summary.errors)\n\(summary.outPath)/results.jsonl"
      } catch {
        text = "Failed: \(error)"
      }
      DispatchQueue.main.async {
        status = text
        running = false
      }
    }
  }
}
