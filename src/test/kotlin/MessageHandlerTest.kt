import agent.AgentException
import agent.AgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import telegram.MessageHandler
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the resilience requirements: a request must time out, must survive an agent
 * failure, and must never block another user's request.
 *
 * `runTest` uses virtual time, so the delays below cost no real wall-clock time.
 */
class MessageHandlerTest {

    private fun handler(timeoutMs: Long = 1_000, agent: AgentService) =
        MessageHandler(agent, timeoutMs.milliseconds)

    private fun agentOf(block: suspend (String) -> String) = object : AgentService {
        override suspend fun ask(chatId: Long, message: String): String = block(message)
        override suspend fun reset(chatId: Long) = Unit
    }

    @Test
    fun `returns the agent answer`() = runTest {
        val handler = handler(agent = agentOf { "echo: $it" })

        assertEquals("echo: hi", handler.answerOrExplain("hi", chatId = 1))
    }

    @Test
    fun `a slow agent is cut off by the timeout`() = runTest {
        val handler = handler(timeoutMs = 100, agent = agentOf { delay(10.seconds); "too late" })

        val reply = handler.answerOrExplain("hi", chatId = 1)

        assertContains(reply.lowercase(), "too long")
    }

    @Test
    fun `an agent failure becomes a friendly reply instead of an exception`() = runTest {
        val handler = handler(agent = agentOf { throw AgentException("upstream exploded") })

        val reply = handler.answerOrExplain("hi", chatId = 1)

        assertTrue(reply.isNotBlank())
        assertTrue(
            !reply.contains("upstream exploded") && !reply.contains("AgentException"),
            "internal detail leaked to the user: $reply",
        )
    }

    @Test
    fun `a network failure is explained without technical detail`() = runTest {
        val handler = handler(agent = agentOf { throw IOException("connection reset by peer") })

        val reply = handler.answerOrExplain("hi", chatId = 1)

        assertContains(reply.lowercase(), "reach the ai service")
        assertTrue(!reply.contains("connection reset"), "internal detail leaked: $reply")
    }

    @Test
    fun `an unexpected error is still answered`() = runTest {
        val handler = handler(agent = agentOf { throw IllegalStateException("boom") })

        assertTrue(handler.answerOrExplain("hi", chatId = 1).isNotBlank())
    }

    @Test
    fun `scope cancellation propagates rather than being swallowed`() = runTest {
        val handler = handler(timeoutMs = 60_000, agent = agentOf { awaitCancellation() })

        val job = async { handler.answerOrExplain("hi", chatId = 1) }
        // Let the agent call actually start before cancelling it.
        delay(10)
        job.cancel()

        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun `a slow request does not block another user`() = runTest {
        val started = AtomicInteger()
        val handler = handler(
            timeoutMs = 60_000,
            agent = agentOf { message ->
                started.incrementAndGet()
                if (message == "slow") delay(30.seconds) else delay(1)
                "done: $message"
            },
        )

        val slow = async { handler.answerOrExplain("slow", chatId = 1) }
        val fast = async { handler.answerOrExplain("fast", chatId = 2) }

        // The fast reply arrives while the slow one is still in flight.
        assertEquals("done: fast", fast.await())
        assertTrue(slow.isActive, "the slow request should still be running")
        assertEquals(2, started.get(), "both requests should have started concurrently")

        assertEquals("done: slow", slow.await())
    }

    @Test
    fun `one failing request does not disturb its neighbours`() = runTest {
        val handler = handler(
            agent = agentOf { message ->
                if (message == "bad") throw IllegalStateException("boom") else "ok: $message"
            },
        )

        val results = List(6) { index ->
            val text = if (index % 2 == 0) "bad" else "good$index"
            async { handler.answerOrExplain(text, chatId = index.toLong()) }
        }.map { it.await() }

        assertEquals(3, results.count { it.startsWith("ok: good") })
        assertTrue(results.all { it.isNotBlank() })
    }

    @Test
    fun `short replies are sent as a single message`() {
        assertEquals(listOf("hello"), MessageHandler.chunk("hello"))
    }

    @Test
    fun `long replies are split within Telegram's limit`() {
        val text = (1..600).joinToString("\n") { "line $it with some padding text" }

        val parts = MessageHandler.chunk(text, limit = 500)

        assertTrue(parts.size > 1, "expected the text to be split")
        assertTrue(parts.all { it.length <= 500 }, "a part exceeded the limit")
        // Nothing is lost: rejoining recovers the original words.
        assertEquals(text.split(Regex("\\s+")), parts.joinToString("\n").split(Regex("\\s+")))
    }

    @Test
    fun `text without break opportunities is still split`() {
        val text = "x".repeat(1_000)

        val parts = MessageHandler.chunk(text, limit = 100)

        assertEquals(10, parts.size)
        assertTrue(parts.all { it.length <= 100 })
    }
}
