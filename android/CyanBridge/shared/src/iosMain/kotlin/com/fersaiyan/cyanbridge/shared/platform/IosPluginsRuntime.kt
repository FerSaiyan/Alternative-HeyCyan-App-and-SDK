package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.ImageAiService
import com.fersaiyan.cyanbridge.shared.persistence.NoteEntity
import com.fersaiyan.cyanbridge.shared.persistence.NotesRepository
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCatalogParser
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutButton
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.localizedStringForLanguageCode
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS runtime for the native plugins (Android: LiveCaptionRelayService,
 * HandsFreeTranslatorService, MeetingSparkNotesService, ErrandBrainService,
 * WalkingAidService). One plugin runs at a time because they share the mic.
 */
class IosPluginsRuntime(
    private val chatAiService: ChatAiService,
    private val imageAiService: ImageAiService,
    private val notesRepository: NotesRepository,
    private val relayBaseUrl: String,
    private val onShortcutChanged: (NativePluginShortcutUiState?) -> Unit,
    /** Asks the glasses for an AI photo; the image arrives through [onGlassesImage]. */
    private val requestGlassesPhoto: suspend () -> Boolean,
) : SharedPluginsPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = PlatformHttpClient()
    private val speechQueue = Mutex()

    override val availablePluginIds: Set<String> = setOf(
        NativePluginIds.LIVE_CAPTION_RELAY,
        NativePluginIds.HANDS_FREE_TRANSLATOR,
        NativePluginIds.MEETING_SPARK_NOTES,
        NativePluginIds.ERRAND_BRAIN,
        NativePluginIds.WALKING_AID,
    )
    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    override val enabledPluginIds: StateFlow<Set<String>> = _enabled.asStateFlow()

    private var activeId: String? = null
    private var speech: IosSpeechEngine? = null
    private var walkingJob: Job? = null
    private val meetingTranscript = StringBuilder()
    private var lastLine = ""

    val isWalkingAidActive: Boolean get() = activeId == NativePluginIds.WALKING_AID

    override fun setPluginEnabled(id: String, enabled: Boolean) {
        if (id !in availablePluginIds) return
        scope.launch {
            if (enabled) start(id) else if (activeId == id) stopActive(summarize = true)
        }
    }

    fun runShortcut(action: NativePluginShortcutAction) {
        scope.launch {
            when (action) {
                NativePluginShortcutAction.STOP -> stopActive(summarize = true)
                NativePluginShortcutAction.SUMMARIZE -> stopActive(summarize = true)
                else -> Unit
            }
        }
    }

    private suspend fun start(id: String) {
        stopActive(summarize = true)
        activeId = id
        _enabled.value = setOf(id)
        meetingTranscript.clear()
        lastLine = ""
        if (id == NativePluginIds.WALKING_AID) {
            publish("Walking Aid is watching the path…")
            walkingJob = scope.launch {
                while (activeId == NativePluginIds.WALKING_AID) {
                    if (!requestGlassesPhoto()) publish("Connect HeyCyan glasses to use Walking Aid")
                    delay(WALKING_AID_INTERVAL_MS)
                }
            }
            return
        }
        val engine = IosSpeechEngine(onPartial = ::onPartial, onPhrase = ::onPhrase)
        val error = engine.start()
        if (error != null) {
            activeId = null
            _enabled.value = emptySet()
            onShortcutChanged(null)
            PlatformLogger.w(TAG, "Plugin $id could not start: $error")
            return
        }
        speech = engine
        publish("Listening…")
    }

    private suspend fun stopActive(summarize: Boolean) {
        val id = activeId ?: return
        activeId = null
        _enabled.value = emptySet()
        speech?.stop()
        speech = null
        walkingJob?.cancel()
        walkingJob = null
        onShortcutChanged(null)
        if (summarize && id == NativePluginIds.MEETING_SPARK_NOTES && meetingTranscript.isNotBlank()) {
            saveMeetingNotes(meetingTranscript.toString())
        }
    }

    private fun onPartial(text: String) {
        when (activeId) {
            NativePluginIds.LIVE_CAPTION_RELAY -> publish(text.takeLast(CAPTION_CHARS))
            NativePluginIds.MEETING_SPARK_NOTES ->
                publish((meetingTranscript.toString() + " " + text).trim().takeLast(CAPTION_CHARS))
            else -> Unit
        }
    }

    private fun onPhrase(text: String) {
        when (activeId) {
            NativePluginIds.LIVE_CAPTION_RELAY -> {
                lastLine = text
                publish(text.takeLast(CAPTION_CHARS))
            }
            NativePluginIds.MEETING_SPARK_NOTES -> meetingTranscript.append(text).append('\n')
            NativePluginIds.HANDS_FREE_TRANSLATOR -> scope.launch { translate(text) }
            NativePluginIds.ERRAND_BRAIN -> scope.launch { captureErrands(text) }
        }
    }

    private suspend fun translate(text: String) {
        val language = userLanguageName()
        val translation = runCatching {
            ask("Translate the following into $language. Reply with only the translation.\n\n$text")
        }.getOrNull() ?: return
        publish("$text\n→ $translation")
        speakWithoutEcho(translation)
    }

    private suspend fun captureErrands(text: String) {
        val reply = runCatching {
            ask(
                """
                Extract tasks or reminders from this sentence. Reply with only a JSON array like
                [{"task": "buy milk", "minutes_from_now": 30}] using null minutes when no time is given,
                or [] when there is no task.

                $text
                """.trimIndent(),
            )
        }.getOrNull() ?: return
        val tasks = parseErrands(reply)
        if (tasks.isEmpty()) return
        val now = platformCurrentTimeMillis()
        val lines = tasks.joinToString("\n") { (task, minutes) ->
            "- [ ] $task" + (minutes?.let { " (in $it min)" } ?: "")
        }
        val existing = notesRepository.getNote(ERRANDS_NOTE_ID)
        if (existing == null) {
            notesRepository.insertNote(NoteEntity(ERRANDS_NOTE_ID, "Errands", lines, now, now, "agent"))
        } else {
            notesRepository.updateNote(existing.copy(content = existing.content.trimEnd() + "\n" + lines, updatedAt = now))
        }
        tasks.forEach { (task, minutes) -> if (minutes != null && minutes > 0) scheduleReminder(task, minutes) }
        publish("Saved: " + tasks.joinToString(", ") { it.first })
        speakWithoutEcho("Saved " + tasks.joinToString(", ") { it.first })
    }

    /** Walking Aid: analyze a glasses photo for hazards and speak short warnings. */
    fun onGlassesImage(image: ByteArray) {
        if (!isWalkingAidActive) return
        scope.launch {
            val warning = runCatching {
                imageAiService.analyzeImage(
                    image,
                    "You help a pedestrian wearing camera glasses. In one short sentence in ${userLanguageName()}, " +
                        "warn about obstacles, steps, vehicles or hazards ahead. Reply with only OK if the path is clear.",
                )
            }.getOrNull()?.trim() ?: return@launch
            if (warning.equals("ok", ignoreCase = true) || warning.startsWith("OK", ignoreCase = true)) {
                publish("Path looks clear")
            } else {
                publish(warning)
                speechQueue.withLock { IosChatPlatform.speak(warning) }
            }
        }
    }

    private suspend fun saveMeetingNotes(transcript: String) {
        val now = platformCurrentTimeMillis()
        val summary = runCatching {
            ask(
                "Summarize this meeting transcript as Markdown with ## Summary, ## Action items, " +
                    "## Key decisions and ## Open questions, in the transcript's language.\n\n$transcript",
            )
        }.getOrDefault("")
        notesRepository.insertNote(
            NoteEntity(
                id = "spark-$now",
                title = "Meeting notes",
                content = listOf(summary, "## Transcript\n\n$transcript").filter { it.isNotBlank() }.joinToString("\n\n---\n\n"),
                createdAt = now,
                updatedAt = now,
                source = "meeting",
            ),
        )
    }

    private suspend fun speakWithoutEcho(text: String) = speechQueue.withLock {
        speech?.setPaused(true)
        IosChatPlatform.speak(text)
        speech?.setPaused(false)
    }

    private suspend fun ask(prompt: String): String =
        chatAiService.chat(listOf(ChatMessage("user", prompt))).message.content.trim()

    private fun parseErrands(reply: String): List<Pair<String, Int?>> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val task = (item["task"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            task to (item["minutes_from_now"] as? JsonPrimitive)?.intOrNull
        }
    }

    private fun scheduleReminder(task: String, minutes: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            if (!granted) return@requestAuthorizationWithOptions
            val content = UNMutableNotificationContent()
            content.setTitle("Errand Brain")
            content.setBody(task)
            content.setSound(UNNotificationSound.defaultSound)
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(minutes * 60.0, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier("errand-${platformCurrentTimeMillis()}", content, trigger)
            center.addNotificationRequest(request, withCompletionHandler = null)
        }
    }

    private fun publish(description: String) {
        val id = activeId ?: return
        val title = when (id) {
            NativePluginIds.LIVE_CAPTION_RELAY -> "Live Caption"
            NativePluginIds.HANDS_FREE_TRANSLATOR -> "Hands-Free Translator"
            NativePluginIds.MEETING_SPARK_NOTES -> "Meeting Spark Notes"
            NativePluginIds.ERRAND_BRAIN -> "Errand Brain"
            else -> "Walking Aid"
        }
        val buttons = buildList {
            if (id == NativePluginIds.MEETING_SPARK_NOTES) {
                add(NativePluginShortcutButton(NativePluginShortcutAction.SUMMARIZE, "Stop and save notes"))
            } else {
                add(NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop"))
            }
        }
        onShortcutChanged(NativePluginShortcutUiState(id, title, description, isEnabled = true, buttons = buttons))
    }

    override suspend fun fetchCommunityPlugins(): List<CommunityPluginCardData> {
        val response = httpClient.get("$relayBaseUrl/plugins")
        check(response.isSuccessful) { "HTTP ${response.statusCode}" }
        return CommunityPluginCatalogParser.parse(response.body)
    }

    override fun openLink(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    override suspend fun publishPlugin(title: String, author: String, description: String, category: String, link: String): Boolean {
        val body = buildJsonObject {
            put("title", title)
            put("author", author)
            put("description", description)
            put("category", category)
            put("taskernet_link", link)
        }.toString()
        return runCatching {
            httpClient.post("$relayBaseUrl/plugins/submit", body, mapOf("Content-Type" to "application/json")).isSuccessful
        }.getOrDefault(false)
    }

    private fun userLanguageName(): String {
        val code = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
        return NSLocale(localeIdentifier = "en").localizedStringForLanguageCode(code) ?: "English"
    }

    private companion object {
        const val TAG = "IosPlugins"
        const val CAPTION_CHARS = 280
        const val WALKING_AID_INTERVAL_MS = 8_000L
        const val ERRANDS_NOTE_ID = "errand-brain"
    }
}
