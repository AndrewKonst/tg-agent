import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import config.AppConfig
import conversation.InMemoryConversationStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real chain — AgentFactory -> AgentLoop -> HTTP -> response — against
 * a stub OpenAI-compatible server, so the wiring is verified without a paid API call.
 *
 * Uses the JDK's built-in HTTP server; no extra dependency, no network access.
 */
class AgentIntegrationTest {

    private companion object {
        const val CHAT = 1L
        const val OWNER = 500L
        const val STRANGER = 501L
    }

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    /** Body the stub returns for the next chat completion. */
    private var reply: String = "Hello from the stub"

    @BeforeTest
    fun startStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.executor = null
        server.start()
    }

    @AfterTest
    fun stopStub() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange) {
        requests += exchange.requestBody.readBytes().decodeToString()
        val body = """
            {
              "id": "chatcmpl-stub",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4o-mini",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": ${quote(reply)} },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3 }
            }
        """.trimIndent().toByteArray()

        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun config(extra: Map<String, String> = emptyMap()) = AppConfig.from(
        mapOf(
            "TELEGRAM_BOT_TOKEN" to "123:abc",
            "LLM_API_KEY" to "sk-test",
            "LLM_MODEL" to "gpt-4o-mini",
            "LLM_BASE_URL" to "http://127.0.0.1:${server.address.port}",
            "SYSTEM_PROMPT" to "You are a test fixture.",
        ) + extra,
    )

    @Test
    fun `the agent reaches the model and returns its answer`() = runBlocking {
        val service = agent.AgentFactory.create(config(), InMemoryConversationStore())

        val answer = service.ask(CHAT, "Say hello")

        assertEquals("Hello from the stub", answer)
        assertEquals(1, requests.size, "expected exactly one chat completion call")

        // The user's message and the configured system prompt actually reached the model.
        val sent = requests.single()
        assertContains(sent, "Say hello")
        assertContains(sent, "You are a test fixture.")
        // The registered tool was advertised, proving tools are wired end to end.
        assertContains(sent, "current_datetime")
    }

    @Test
    fun `concurrent requests are served independently`() = runBlocking {
        val service = agent.AgentFactory.create(config(), InMemoryConversationStore())

        val answers = kotlinx.coroutines.coroutineScope {
            List(5) { index ->
                async(kotlinx.coroutines.Dispatchers.IO) { service.ask(chatId = index.toLong(), message = "request $index") }
            }.map { it.await() }
        }

        assertEquals(5, answers.size)
        assertTrue(answers.all { it == "Hello from the stub" })
        assertEquals(5, requests.size, "every request should have reached the model")
    }

    @Test
    fun `a shell is offered to owners and hidden from everyone else`() = runBlocking {
        val service = agent.AgentFactory.create(
            config = config(mapOf("OWNER_CHAT_IDS" to "$OWNER")),
            store = InMemoryConversationStore(),
            sandbox = SilentSandbox,
        )

        service.ask(OWNER, "hello")
        assertContains(requests.last(), "exec", message = "the owner should be offered a shell")

        service.ask(STRANGER, "hello")
        val forStranger = requests.last()
        assertTrue(
            !forStranger.contains("\"exec\""),
            "a stranger must not even be told the exec tool exists",
        )
        // They still get the harmless tool, so this is gating, not a broken tool list.
        assertContains(forStranger, "current_datetime")
    }

    /** Stands in for a container; nothing in this test should ever run a command. */
    private object SilentSandbox : sandbox.Sandbox {
        override val description = "test double"
        override suspend fun exec(command: String) =
            sandbox.ExecResult(exitCode = 0, output = "")
    }

    @Test
    fun `an empty model response becomes a readable reply`() = runBlocking {
        reply = "   "
        val service = agent.AgentFactory.create(config(), InMemoryConversationStore())

        // A model that returns nothing is not an error the operator can act on, so the
        // user gets something to react to rather than a generic failure notice.
        val answer = service.ask(CHAT, "hi")

        assertTrue(answer.isNotBlank(), "expected a non-blank fallback, got '$answer'")
    }
}
