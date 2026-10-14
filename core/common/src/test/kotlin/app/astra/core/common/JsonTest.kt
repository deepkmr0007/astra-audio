package app.astra.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test fun roundTrip() {
        val v = mapOf("a" to 1L, "b" to listOf("x", "हिन्दी \"q\"\n", null, true), "c" to mapOf("d" to 2.5))
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(v, Json.parse(Json.write(v, pretty = true)))
    }

    @Test fun rejectsMalformedAndDeepInput() {
        for (bad in listOf("{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "1 2", "{\"a\":\"\u0001\"}")) {
            try { Json.parse(bad); fail("should reject $bad") } catch (e: Json.ParseException) { }
        }
        try { Json.parse("[".repeat(100) + "]".repeat(100)); fail("too deep") } catch (e: Json.ParseException) { }
    }

    @Test fun timestampsAreLocaleIndependent() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("hi-IN-u-nu-deva"))
            assertEquals("01:02:03,004", formatTimestamp(3_723_004, ','))
        } finally { java.util.Locale.setDefault(old) }
    }
}
