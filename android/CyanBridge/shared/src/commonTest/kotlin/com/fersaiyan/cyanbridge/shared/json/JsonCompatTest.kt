package com.fersaiyan.cyanbridge.shared.json

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonCompatTest {
    @Test
    fun readsStrictJson() {
        val obj = parseJsonObject("""{"a":"x\"yé","b":[1,2.5,true,null],"c":{}}""")
        assertEquals("""{"a":"x\"yé","b":[1,2.5,true,null],"c":{}}""", obj.toString())
    }

    @Test
    fun acceptsWhatOrgJsonAccepted() {
        val obj = parseJsonObject(
            """
            # comment
            {type: click_text; 'text' => 'O\'K', /* note */ n = 0x1F, o: 010, list: [,1,], // tail
             big: 12345678901, e: 1e3} trailing words
            """.trimIndent(),
        )
        assertEquals("click_text", obj.optString("type"))
        assertEquals("O'K", obj.optString("text"))
        assertEquals(31, obj.optInt("n"))
        assertEquals(8, obj.optInt("o"))
        assertEquals("[null,1,null]", obj["list"].toString())
        assertEquals(12345678901L, obj.optLong("big"))
        assertEquals("1000.0", obj.optString("e"))
    }

    @Test
    fun optReadersFallBackLikeOrgJson() {
        val obj = parseJsonObject("""{"s":"12.9","b":"TRUE","n":null,"o":{"k":1},"d":4.7,"t":true}""")
        assertEquals(12, obj.optInt("s"))
        assertEquals(4, obj.optInt("d"))
        assertEquals(-1, obj.optInt("t", -1))
        assertEquals(-1, obj.optInt("n", -1))
        assertEquals(true, obj.optBoolean("b"))
        assertEquals("null", obj.optString("n"))
        assertEquals("""{"k":1}""", obj.optString("o"))
        assertEquals("fallback", obj.optString("missing", "fallback"))
        assertEquals(JsonNull, obj["n"])
    }

    @Test
    fun rejectsWhatOrgJsonRejected() {
        assertFailsWith<JsonSyntaxException> { parseJsonObject("") }
        assertFailsWith<JsonSyntaxException> { parseJsonObject("[1]") }
        assertFailsWith<JsonSyntaxException> { parseJsonObject("""{"a":1""") }
        assertFailsWith<JsonSyntaxException> { parseJsonObject("""{"a":"open}""") }
        assertFailsWith<JsonSyntaxException> { parseJsonObject("""{1:2}""") }
        assertFailsWith<JsonSyntaxException> { parseJsonObject("""{"a":NaN}""") }
        assertFailsWith<JsonSyntaxException> { parseJsonArray("{}") }
    }
}
