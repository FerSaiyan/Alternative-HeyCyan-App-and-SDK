package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.shared.ui.localagent.LocalAgentDocumentScreen
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCapturesActivity : AppCompatActivity() {

    private var title by mutableStateOf("Screen captures")
    private var path by mutableStateOf("")
    private var renderedText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalAgentMemoryStore.ensureSeedFiles(this)
        loadAndRender()
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                LocalAgentDocumentScreen(
                    title = title,
                    path = path,
                    text = renderedText,
                    hint = "Latest captures (tail)",
                    editable = false,
                    primaryLabel = "Refresh",
                    onTextChange = {},
                    onPrimary = ::loadAndRender,
                    secondaryLabel = "Share",
                    onSecondary = ::shareRenderedText,
                    onBack = ::finish,
                )
            }
        }
    }

    private fun loadAndRender() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        val file = LocalAgentMemoryStore.screenCaptureFileForDate(this, date)
        val lines = LocalAgentMemoryStore.readScreenCaptureLines(this, date, maxLines = 25)

        title = "Screen captures ($date)"
        path = file.absolutePath

        val rendered = renderTailPretty(lines)
        renderedText = rendered

        if (rendered.startsWith("(no captures")) {
            Toast.makeText(this, "No screen captures yet for today", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRenderedText() {
        val text = renderedText.trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Screen captures (tail)")
            putExtra(Intent.EXTRA_TEXT, text.take(120_000))
        }
        startActivity(Intent.createChooser(i, "Share screen captures"))
    }

    private fun renderTailPretty(lines: List<String>, maxTextCharsPerEntry: Int = 2500): String {

        if (lines.isEmpty()) return "(no captures yet)"
        val sb = StringBuilder()

        val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        for (line in lines) {
            val obj = runCatching { JSONObject(line) }.getOrNull()
            if (obj == null) {
                sb.appendLine(line)
                sb.appendLine("---")
                continue
            }

            val ts = obj.optLong("ts_ms", 0L)
            val pkg = obj.optString("package", "")
            val text = obj.optString("text", "")

            val tsText = if (ts > 0L) tsFmt.format(Date(ts)) else "(no-ts)"

            sb.appendLine("[$tsText] $pkg")
            sb.appendLine(text.take(maxTextCharsPerEntry))
            if (text.length > maxTextCharsPerEntry) sb.appendLine("…(truncated)")
            sb.appendLine("\n---\n")
        }

        return sb.toString().trim()
    }
}
