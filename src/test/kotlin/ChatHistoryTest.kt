import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import conversation.HistoryWindow
import conversation.withoutReasoning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the trimming policy.
 *
 * The property that matters is not "the history got shorter" but "what is left is
 * still a valid conversation": no tool result may survive without the call it
 * answers, or the provider rejects the whole request.
 */
class ChatHistoryTest {

    @Test
    fun `keeps everything when it fits`() {
        val history = plainTurn("one") + toolTurn("two")

        val kept = HistoryWindow(maxChars = 10_000).fit(history)

        assertEquals(history, kept)
    }

    @Test
    fun `drops the oldest turn whole`() {
        val old = plainTurn("a very old question indeed, padded out to take up room")
        val recent = toolTurn("recent")

        val kept = HistoryWindow(maxChars = 120).fit(old + recent)

        assertEquals(recent, kept, "the newest whole turn should survive, the old one should not")
    }

    @Test
    fun `never leaves a tool result without its call`() {
        val history = plainTurn("old one") + plainTurn("old two") + toolTurn("recent")

        // Every budget from tiny to generous must leave a conversation that still pairs up.
        for (budget in 1..400) {
            val kept = HistoryWindow(maxChars = budget).fit(history)

            val callIds = kept.filterIsInstance<Message.Assistant>()
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Call>()
                .map { it.id }
            val resultIds = kept.filterIsInstance<Message.User>()
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Result>()
                .map { it.id }

            assertTrue(
                callIds.containsAll(resultIds),
                "budget=$budget left orphaned tool results: $resultIds without $callIds",
            )
        }
    }

    @Test
    fun `returns nothing when even the newest turn is too large`() {
        val kept = HistoryWindow(maxChars = 5).fit(plainTurn("a question far longer than five characters"))

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `reasoning is stripped before a message is stored`() {
        val assistant = Message.Assistant(
            listOf(
                MessagePart.Reasoning(listOf("thinking out loud")),
                MessagePart.Text("The answer is 42."),
            ),
            ResponseMetaInfo.Empty,
        )

        val stored = assistant.withoutReasoning() as Message.Assistant

        assertEquals(1, stored.parts.size)
        assertEquals("The answer is 42.", stored.textContent())
    }

    @Test
    fun `a message with no reasoning is left untouched`() {
        val assistant = Message.Assistant("plain", ResponseMetaInfo.Empty)

        assertTrue(assistant.withoutReasoning() === assistant)
    }

    // --- fixtures --------------------------------------------------------------

    /** A question and a plain answer. */
    private fun plainTurn(text: String) = listOf(
        Message.User(text, RequestMetaInfo.Empty),
        Message.Assistant("answer to $text", ResponseMetaInfo.Empty),
    )

    /** A question answered via a tool: call and result must never be separated. */
    private fun toolTurn(text: String) = listOf(
        Message.User(text, RequestMetaInfo.Empty),
        Message.Assistant(
            listOf(MessagePart.Tool.Call(id = "call-$text", tool = "echo", args = """{"text":"$text"}""")),
            ResponseMetaInfo.Empty,
        ),
        Message.User(
            listOf(MessagePart.Tool.Result(id = "call-$text", tool = "echo", output = "echoed $text")),
            RequestMetaInfo.Empty,
        ),
        Message.Assistant("done with $text", ResponseMetaInfo.Empty),
    )
}
