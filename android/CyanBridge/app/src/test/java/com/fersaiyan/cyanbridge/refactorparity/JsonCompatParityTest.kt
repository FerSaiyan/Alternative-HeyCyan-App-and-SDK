package com.fersaiyan.cyanbridge.refactorparity

import com.fersaiyan.cyanbridge.shared.json.parseJsonArray
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared reader accepts and rejects the same text as Android's org.json, with the same values. */
@RunWith(RobolectricTestRunner::class)
class JsonCompatParityTest {
    private val objects = listOf(
        """{"a":"x","b":[1,2.5,true,null],"c":{}}""",
        """```json {"oops":1}```""",
        """{type: click_text; 'text' => 'O\'K', n = 0x1F, o: 010, list: [,1,], big: 12345678901, e: 1e3}""",
        """  # c
        {"a": /* x */ "b"} // tail""",
        """{"a":1} trailing""",
        """{"a":-0, "b": 08, "c": TRUE, "d": Null, "e": 1.0}""",
        """{"a":"é\n\t\/"}""",
        """{"a":1,"a":2}""",
        """{"a":1""",
        """{"a":"open}""",
        """{1:2}""",
        """{"a":NaN}""",
        """{a b: 1}""",
        "[1]",
        "",
        "plain",
    )

    @Test
    fun objectsParseAlike() {
        objects.forEach { text ->
            val legacy = runCatching { JSONObject(text).toString() }
            val shared = runCatching { JSONObject(parseJsonObject(text).toString()).toString() }
            assertEquals(text, legacy.isSuccess, shared.isSuccess)
            if (legacy.isSuccess) assertEquals(text, legacy.getOrThrow(), shared.getOrThrow())
        }
    }

    @Test
    fun arraysParseAlike() {
        listOf("[1,,2]", "[,]", "[]", "['a';b]", """[{"x":1}, "y"]""", "[1", "{}", "[1 2]").forEach { text ->
            val legacy = runCatching { JSONArray(text).toString() }
            val shared = runCatching { JSONArray(parseJsonArray(text).toString()).toString() }
            assertEquals(text, legacy.isSuccess, shared.isSuccess)
            if (legacy.isSuccess) assertEquals(text, legacy.getOrThrow(), shared.getOrThrow())
        }
    }
}
