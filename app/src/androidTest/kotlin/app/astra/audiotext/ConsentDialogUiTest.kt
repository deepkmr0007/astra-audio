package app.astra.audiotext

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.astra.audiotext.ui.ConsentDialog
import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.ConsentRequest
import app.astra.core.privacy.PayloadClass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The consent screen shows the destination, payload and options — and never an "always allow". */
@RunWith(AndroidJUnit4::class)
class ConsentDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private val request = ConsentRequest(
        operationId = "op", task = "Download speech model: Whisper base", method = "GET",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        allowedHosts = setOf("huggingface.co"), payloadClass = PayloadClass.NONE,
        payloadDescription = "No audio or transcripts", reason = "Model not installed",
        estimatedBytes = 148_897_792, offlineAlternative = "Import a model file you already have",
    )

    @Test fun showsDetailsAndOnlyOneTimeOptions() {
        var decision: ConsentDecision? = null
        compose.setContent { ConsentDialog(request) { decision = it } }
        compose.onNodeWithText("huggingface.co").assertIsDisplayed()
        compose.onNodeWithText("Not verified by this app").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Always", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Allow this request once").performClick()
        assertEquals(ConsentDecision.ALLOW_ONCE, decision)
    }
}
