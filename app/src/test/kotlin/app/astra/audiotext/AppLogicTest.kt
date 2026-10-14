package app.astra.audiotext

import app.astra.audiotext.engine.JobRequest
import app.astra.audiotext.ui.Navigator
import app.astra.audiotext.ui.Screen
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.TranscriptStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    @Test fun jobRequestRoundTripsForResume() {
        val r = JobRequest("item-1", "hi", false, AccuracyProfile.HIGHEST, TranscriptStyle.VERBATIM, speakerTurns = false, useGlossary = true, useCleanAudio = true)
        assertEquals(r, JobRequest.fromJson(r.toJson()))
        assertNull(JobRequest.fromJson("{not json"))
    }

    @Test fun navigatorBackStack() {
        val n = Navigator()
        assertFalse(n.back())
        n.go(Screen.Library)
        n.go(Screen.Item("a"))
        n.go(Screen.Item("a")) // no duplicate push
        assertEquals(3, n.stack.value.size)
        n.replace(Screen.Item("b"))
        assertEquals(Screen.Item("b"), n.current)
        assertTrue(n.back())
        assertEquals(Screen.Library, n.current)
        n.home()
        assertEquals(listOf<Screen>(Screen.Home), n.stack.value)
    }
}
