package com.fersaiyan.cyanbridge.localagent

data class LocalAgentObservation(
    val createdAtMs: Long,
    val packageName: String?,
    val screenText: String?,
    val screenSnapshot: LocalAgentScreenSnapshot?,
)

data class LocalAgentScreenSnapshot(
    val packageName: String?,
    val textSummary: String?,
    val nodes: List<LocalAgentScreenNode>,
) {
    fun toPromptText(
        maxNodes: Int = 120,
        maxChars: Int = 12_000,
    ): String {
        val body = buildString {
            val pkg = packageName?.trim().orEmpty()
            if (pkg.isNotEmpty()) {
                appendLine("Current app: $pkg")
            }

            val summary = textSummary?.trim().orEmpty()
            if (summary.isNotEmpty()) {
                appendLine("Visible text summary:")
                appendLine(summary)
            }

            if (nodes.isNotEmpty()) {
                appendLine("Structured nodes:")
                nodes.take(maxNodes).forEach { node ->
                    appendLine(node.toPromptLine())
                }
            }
        }.trim()

        return if (body.length <= maxChars) body else body.take(maxChars)
    }

    /**
     * Compact screen description that filters noise, highlights task-relevant
     * elements, and uses abbreviated type names to save LLM tokens.
     */
    fun toCompressedPromptText(goal: String, maxNodes: Int = 120): String {
        val keywords = extractGoalKeywords(goal)
        val pkg = packageName?.trim().orEmpty()

        return buildString {
            if (pkg.isNotEmpty()) {
                appendLine("APP: $pkg")
            }
            nodes.take(maxNodes).forEach { node ->
                val line = node.toCompressedLine(keywords)
                if (line != null) appendLine(line)
            }
        }.trim()
    }

    companion object {
        private val STATUS_BAR_PATTERNS = listOf(
            "battery", "percent", "do not disturb", "three bars",
            "signal strength", "airplane mode", "vibrate",
        )
        private val TIME_REGEX = Regex("""^\d{1,2}:\d{2}$""")
        private val STOP_WORDS = setOf(
            "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by",
            "from", "go", "turn", "open", "is", "my", "me", "an", "it", "do",
        )

        fun extractGoalKeywords(goal: String): List<String> {
            return goal.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in STOP_WORDS }
        }

        fun isStatusBarNoise(text: String): Boolean {
            val lower = text.lowercase()
            if (STATUS_BAR_PATTERNS.any { lower.contains(it) }) return true
            if (TIME_REGEX.matches(lower)) return true
            return false
        }
    }
}

data class LocalAgentScreenNode(
    val index: Int,
    val depth: Int,
    val text: String,
    val contentDescription: String,
    val hintText: String = "",
    val className: String,
    val viewId: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val bounds: LocalAgentNodeBounds,
    val isPassword: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false,
) {
    fun toPromptLine(): String {
        val label = when {
            isPassword -> "(password field redacted)"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            hintText.isNotBlank() -> hintText
            else -> "(no text)"
        }
        val attrs = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isScrollable) add("scrollable")
            if (isCheckable) add(if (isChecked) "checked" else "unchecked")
            if (isFocused) add("focused")
            if (viewId.isNotBlank()) add("viewId=$viewId")
        }.joinToString(",")

        val type = className.ifBlank { "Node" }
        val attrSuffix = if (attrs.isNotBlank()) " {$attrs}" else ""

        return "[$index] [$type] \"${label.sanitizeForPrompt()}\"$attrSuffix bounds:[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] center:(${bounds.centerX},${bounds.centerY})"
    }

    /**
     * Compact line for compressed screen descriptions. Returns null if the node
     * is status-bar noise or has no useful content.
     */
    fun toCompressedLine(keywords: List<String>): String? {
        val displayText = when {
            isPassword -> "(password)"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            hintText.isNotBlank() -> hintText
            else -> ""
        }

        if (LocalAgentScreenSnapshot.isStatusBarNoise(displayText)) return null
        if (displayText.isEmpty() && !isClickable && !isEditable && !isScrollable) return null

        val truncated = if (displayText.length > 50) displayText.take(50) + "…" else displayText

        val tags = buildList {
            if (isClickable) add("tap")
            if (isEditable) add("edit")
            if (isScrollable) add("scroll")
        }

        val type = simplifyClassName(className)
        val label = if (truncated.isNotBlank()) "\"${truncated.sanitizeForPrompt()}\"" else ""
        val tagStr = if (tags.isNotEmpty()) "[${tags.joinToString(",")}]" else ""

        val isTarget = truncated.isNotBlank() && keywords.any { truncated.lowercase().contains(it) }
        val targetMark = if (isTarget) "*" else ""

        return "[$index]$targetMark $type $label $tagStr center:(${bounds.centerX},${bounds.centerY})".trim()
    }

    private fun simplifyClassName(className: String): String {
        val simple = className.substringAfterLast('.')
        return when (simple) {
            "TextView" -> "text"
            "Button" -> "btn"
            "Switch", "ToggleButton" -> "toggle"
            "ImageView" -> "img"
            "EditText" -> "input"
            "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout" -> "view"
            "RecyclerView" -> "list"
            "ScrollView", "NestedScrollView" -> "scroll"
            "CheckBox", "RadioButton" -> "check"
            else -> simple.lowercase()
        }
    }
}

data class LocalAgentNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

private fun String.sanitizeForPrompt(): String =
    replace("\n", " ")
        .replace("\r", " ")
        .trim()
