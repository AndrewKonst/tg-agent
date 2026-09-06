import agent.AgentException
import agent.AgentProfile
import agent.HarnessAgentService
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import conversation.ConversationStore
import conversation.HistoryWindow
import conversation.InMemoryConversationStore
import harness.AgentLoop
import harness.Llm
import harness.ToolBox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests that a chat really is one long conversation: what was said earlier comes
 * back on the next message, `/new` ends it, and two chats never mix.
 */
class ConversationServiceTest {

    @Test
    fun `the previous exchange is replayed into the next run`() = runTest {
        val llm = RecordingLlm { "answer ${it.size}" }
        val service = service(llm)

        service.ask(CHAT, "first question")
        service.ask(CHAT, "second question")

        val second = llm.prompts.last().map { it.textContent() }
        assertTrue(second.contains("first question"), "the earlier question should be replayed: $second")
        assertTrue(second.contains("answer 2"), "the earlier answer should be replayed: $second")
        assertTrue(second.contains("second question"))
    }

    @Test
    fun `the system prompt leads every run exactly once`() = runTest {
        val llm = RecordingLlm { "ok" }
        val service = service(llm)

        service.ask(CHAT, "one")
        service.ask(CHAT, "two")

        val prompt = llm.prompts.last()
        assertEquals(1, prompt.count { it is Message.System }, "history must not accumulate system prompts")
        assertTrue(prompt.first() is Message.System)
    }

    @Test
    fun `reset starts a fresh conversation`() = runTest {
        val llm = RecordingLlm { "ok" }
        val service = service(llm)

        service.ask(CHAT, "remember this")
        service.reset(CHAT)
        service.ask(CHAT, "new question")

        val prompt = llm.prompts.last().map { it.textContent() }
        assertTrue(prompt.none { it.contains("remember this") }, "history should be gone: $prompt")
        assertEquals(2, prompt.size, "only the system prompt and the new question should remain")
    }

    @Test
    fun `two chats keep separate conversations`() = runTest {
        val llm = RecordingLlm { "ok" }
        val service = service(llm)

        service.ask(1, "chat one secret")
        service.ask(2, "chat two question")

        val prompt = llm.prompts.last().map { it.textContent() }
        assertTrue(prompt.none { it.contains("chat one secret") }, "chats must not leak into each other: $prompt")
    }

    @Test
    fun `a failed run leaves no half-written exchange behind`() = runTest {
        val store = InMemoryConversationStore()
        val service = service(FailingLlm(), store)

        assertFailsWith<AgentException> { service.ask(CHAT, "this will fail") }

        assertTrue(store.load(CHAT).isEmpty(), "nothing should be stored for a run that failed")
    }

    @Test
    fun `concurrent messages in one chat do not interleave`() = runTest {
        // Each call pauses mid-run, which without the per-chat lock would let the
        // second run read the history before the first one has written it.
        val llm = RecordingLlm { delay(50); "ok" }
        val store = InMemoryConversationStore()
        val service = service(llm, store)

        listOf(
            async { service.ask(CHAT, "first") },
            async { service.ask(CHAT, "second") },
        ).awaitAll()

        // Two turns, each a question and an answer, in one unbroken sequence.
        val history = store.load(CHAT)
        assertEquals(4, history.size, "expected two complete turns, got ${history.map { it.textContent() }}")
        assertTrue(history[0] is Message.User && history[1] is Message.Assistant)
        assertTrue(history[2] is Message.User && history[3] is Message.Assistant)
    }

    // --- fixtures --------------------------------------------------------------

    private fun service(llm: Llm, store: ConversationStore = InMemoryConversationStore()) =
        HarnessAgentService(
            loop = AgentLoop(llm, maxSteps = 4),
            store = store,
            window = HistoryWindow(maxChars = 100_000),
            profileFor = { AgentProfile(SYSTEM_PROMPT, ToolBox(emptyList())) },
        )

    /** A model that always answers, and remembers what it was shown. */
    private class RecordingLlm(private val answer: suspend (List<Message>) -> String) : Llm {
        val prompts = mutableListOf<List<Message>>()

        override suspend fun complete(
            messages: List<Message>,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            prompts += messages.toList()
            return Message.Assistant(answer(messages), ResponseMetaInfo.Empty)
        }
    }

    private class FailingLlm : Llm {
        override suspend fun complete(
            messages: List<Message>,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = throw IllegalStateException("provider is down")
    }

    private companion object {
        const val CHAT = 42L
        const val SYSTEM_PROMPT = "You are a test fixture."
    }
}
