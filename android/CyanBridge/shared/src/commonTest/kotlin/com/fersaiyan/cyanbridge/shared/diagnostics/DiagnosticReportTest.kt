package com.fersaiyan.cyanbridge.shared.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReportTest {

    @Test
    fun redactionRemovesPersonalDataAndSecrets() {
        // input -> text that must be gone, text that must remain
        val cases = listOf(
            Triple("contact akio+ci@cyanbridgelabs.com now", "akio", "<email>"),
            Triple("Authorization: Bearer relay_AbCdEf0123456789AbCdEf0123456789AbCdEf0123", "relay_AbCd", "<redacted>"),
            Triple("key sk-proj-abcdefghijklmnop1234 used", "sk-proj", "<api-key>"),
            Triple("client AR|1057183300587462|77e92a3d9f02778279c80f220a2d81a2", "77e92a3d", "<client-token>"),
            Triple("GET https://relay.example/v1/x?api_key=AIzaSyB123&q=hello ok", "hello", "https://relay.example/v1/x?<query>"),
            Triple("User transcription: what is on my desk", "my desk", "transcription: <"),
            Triple("""answer="Those are yellow socks" done""", "yellow", "answer=<22 chars>"),
            Triple("connect to 7C:2A:31:4F:9E:D0 failed", "7C:2A:31:4F", "XX:XX:XX:XX:9E:D0"),
            Triple("password=hunter22 retry", "hunter22", "password=<redacted>"),
        )
        cases.forEach { (input, gone, kept) ->
            val out = DiagnosticRedactor.redact(input)
            assertFalse(gone in out.text, "'$gone' left in '${out.text}'")
            assertTrue(kept in out.text, "'$kept' missing in '${out.text}'")
            assertTrue(out.count > 0)
        }
        // Facts that support needs stay untouched.
        val facts = "P2P group formed owner_ip=192.168.49.1 flow=ADAPTIVE fw=1.0.182.20 sha=68906f738fba30621b138ed4e2400e2b"
        assertEquals(facts, DiagnosticRedactor.redactText(facts))
    }

    @Test
    fun aNoisySubsystemCannotEvictTheOthers() {
        var now = 0L
        val log = DiagnosticEventLog(maxTotal = 50, perSubsystemCap = 20, foldWindowMs = 0, clock = { now })
        log.record(DiagnosticSubsystem.BLE, DiagnosticLevel.INFO, "glasses connected")
        log.record(DiagnosticSubsystem.SYNC, DiagnosticLevel.ERROR, "sync failed")
        log.record(DiagnosticSubsystem.META_DAT, DiagnosticLevel.ERROR, "registration failed")
        repeat(1_000) { now += 10; log.record(DiagnosticSubsystem.META_DAT, DiagnosticLevel.INFO, "stream frame $it") }
        repeat(30) { now += 10; log.record(DiagnosticSubsystem.QUESTION, DiagnosticLevel.INFO, "route $it") }

        val events = log.snapshot()
        val bySubsystem = events.groupBy { it.subsystem }
        assertTrue(events.size <= 50)
        assertEquals(20, bySubsystem.getValue(DiagnosticSubsystem.META_DAT).size)
        assertTrue(bySubsystem.getValue(DiagnosticSubsystem.META_DAT).any { it.level == DiagnosticLevel.ERROR }, "DAT's own error survives its flood")
        assertEquals("glasses connected", bySubsystem.getValue(DiagnosticSubsystem.BLE).single().message)
        assertEquals("sync failed", bySubsystem.getValue(DiagnosticSubsystem.SYNC).single().message)
        assertEquals("route 29", bySubsystem.getValue(DiagnosticSubsystem.QUESTION).last().message)
        assertEquals(events.map { it.seq }.sorted(), events.map { it.seq }, "oldest first")

        // Identical consecutive events fold into one with a repeat count.
        val folding = DiagnosticEventLog(clock = { now })
        repeat(5) { folding.record(DiagnosticSubsystem.BLE, DiagnosticLevel.INFO, "auto-pair skipped") }
        assertEquals(4, folding.snapshot().single().repeats)
    }

    @Test
    fun reportKeepsLegacyFieldsAddsStructuredOnesAndStaysBounded() {
        val events = (1..700).map { i ->
            DiagnosticEvent(i.toLong(), 1_000L * i, DiagnosticSubsystem.META_DAT, DiagnosticLevel.INFO, "x".repeat(300), mapOf("k" to "v".repeat(200)))
        } + DiagnosticEvent(701, 800_000, DiagnosticSubsystem.SYNC, DiagnosticLevel.ERROR, "sync failed", stack = "java.io.IOException: reset")
        val report = DiagnosticReportComposer.compose(
            DiagnosticReportInput(
                trigger = DiagnosticTrigger.VOLUNTARY,
                issueType = "Other/General",
                description = "",
                userNote = "Sync stopped, mail me at someone@example.com",
                contactEmail = "someone@example.com",
                requestMetadata = null,
                appVersion = "2.3.0",
                versionCode = 31,
                buildType = "release",
                header = linkedMapOf("glasses" to mapOf("family" to "HEY_CYAN", "address" to "7C:2A:31:4F:9E:D0")),
                extraDiagnostics = "Automation diagnostics:\n- Feature enabled: false",
                events = events,
                eventWindowMs = 30 * 60_000L,
                lastExit = DiagnosticLastExit(pid = 42, reason = "CRASH", timeMs = 5_000, stack = "java.lang.IllegalStateException: boom"),
                logcat = "11-17 10:00:00.000  42  42 I AIHijack: Spoken decision route=ANSWER\n".repeat(5_000),
                createdAtMs = 900_000,
                destination = "cyanbridgelabs.com",
            ),
        )
        val json = report.json
        listOf("issue_type", "description", "logs", "device_info", "app_version", "contact_email", "report_version",
            "trigger", "user_note", "app_build", "header", "events", "event_window_ms", "last_exit", "redaction").forEach {
            assertTrue(it in json, "missing $it")
        }
        assertEquals(2, json.getValue("report_version").jsonPrimitive.int)
        assertEquals("voluntary", json.getValue("trigger").jsonPrimitive.content)
        assertEquals("someone@example.com", json.getValue("contact_email").jsonPrimitive.content)
        assertFalse("someone@example.com" in json.getValue("user_note").jsonPrimitive.content)
        assertFalse("someone@example.com" in json.getValue("logs").jsonPrimitive.content)
        assertTrue(json.getValue("logs").jsonPrimitive.content.length <= DiagnosticReportComposer.MAX_LOGS)
        assertTrue(json.getValue("device_info").jsonPrimitive.content.length <= DiagnosticReportComposer.MAX_DEVICE_INFO)
        assertTrue("XX:XX:XX:XX:9E:D0" in (json.getValue("header").jsonObject.getValue("glasses") as JsonObject).toString())
        val sent = json.getValue("events").jsonArray
        assertTrue(sent.size <= DiagnosticReportComposer.MAX_EVENTS)
        assertTrue(sent.toString().length <= DiagnosticReportComposer.MAX_EVENTS_JSON + 10_000)
        assertTrue((sent as JsonArray).any { it.jsonObject.getValue("m").jsonPrimitive.content == "sync failed" }, "errors are kept")
        assertEquals("CRASH", json.getValue("last_exit").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(report.serialized.length < 260_000, "payload ${report.serialized.length}")
        assertTrue(report.preview.startsWith("Sent to CyanBridge support (cyanbridgelabs.com)"))
        assertTrue("Your note: Sync stopped" in report.preview)
    }
}
