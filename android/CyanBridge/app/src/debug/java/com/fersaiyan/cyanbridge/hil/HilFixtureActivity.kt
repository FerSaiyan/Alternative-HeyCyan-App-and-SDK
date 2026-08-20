package com.fersaiyan.cyanbridge.hil

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.R

/**
 * Debug-only deterministic UI used by emulator and physical hardware-in-the-loop tests.
 *
 * The IDs and visible strings are intentionally stable because Tasker/AutoInput tests use
 * this screen as a known external-automation target. No production code should depend on it.
 */
class HilFixtureActivity : AppCompatActivity() {
    private var clickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val marker = TextView(this).apply {
            id = R.id.hil_marker
            text = MARKER_TEXT
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(marker)

        val status = TextView(this).apply {
            id = R.id.hil_status
            text = STATUS_READY
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
            importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(status)

        val clickButton = Button(this).apply {
            id = R.id.hil_click_button
            text = CLICK_BUTTON_TEXT
            contentDescription = CLICK_BUTTON_TEXT
            setOnClickListener {
                clickCount += 1
                status.text = "HIL_CLICK_COUNT=$clickCount"
            }
        }
        root.addView(
            clickButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val input = EditText(this).apply {
            id = R.id.hil_input
            hint = INPUT_HINT
            contentDescription = INPUT_HINT
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val longPress = TextView(this).apply {
            id = R.id.hil_long_press
            text = LONG_PRESS_TEXT
            contentDescription = LONG_PRESS_TEXT
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
            setOnLongClickListener {
                status.text = STATUS_LONG_PRESSED
                true
            }
        }
        root.addView(
            longPress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        repeat(18) { index ->
            scrollContent.addView(
                TextView(this).apply {
                    text = "HIL_FILLER_${index.toString().padStart(2, '0')}"
                    setPadding(dp(8), dp(18), dp(8), dp(18))
                },
            )
        }
        scrollContent.addView(
            TextView(this).apply {
                id = R.id.hil_scroll_target
                text = SCROLL_TARGET_TEXT
                contentDescription = SCROLL_TARGET_TEXT
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(28), dp(8), dp(28))
            },
        )

        val scrollView = ScrollView(this).apply {
            id = R.id.hil_scroll_container
            isFillViewport = true
            addView(
                scrollContent,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
    }

    companion object {
        const val MARKER_TEXT = "CYANBRIDGE_HIL_SCREEN_72941"
        const val STATUS_READY = "HIL_STATUS_READY"
        const val STATUS_LONG_PRESSED = "HIL_LONG_PRESS_OK"
        const val CLICK_BUTTON_TEXT = "HIL CLICK ME"
        const val INPUT_HINT = "HIL INPUT"
        const val LONG_PRESS_TEXT = "HIL LONG PRESS"
        const val SCROLL_TARGET_TEXT = "HIL_SCROLL_TARGET"
    }
}
