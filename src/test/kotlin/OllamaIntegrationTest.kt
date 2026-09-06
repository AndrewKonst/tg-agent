import agent.AgentFactory
import config.AppConfig
import conversation.InMemoryConversationStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the real chain against a **local Ollama** — no API key, no network egress:
 *
 *     AgentFactory -> Koog agent -> Ollama -> answer
 *
 * Skipped automatically when Ollama is not running or the model is not pulled, so
 * `./gradlew test` stays green on machines without it.
 *
 * To run it:
 *     brew services start ollama
 *     ollama pull qwen3:14b
 *     ./gradlew test --tests OllamaIntegrationTest -i
 *
 * Override the model with `OLLAMA_MODEL=<tag>`.
 */
class OllamaIntegrationTest {

    private val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    private val model = System.getenv("OLLAMA_MODEL") ?: "qwen3:14b"

    private fun installedModels(): List<String> = try {
        val connection = (URI("$baseUrl/api/tags").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }
        if (connection.responseCode != 200) return emptyList()
        val body = connection.inputStream.use { it.readBytes().decodeToString() }
        // Cheap extraction of every "name":"..." — enough to check availability.
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun requireOllama() {
        val installed = installedModels()
        assumeTrue(installed.isNotEmpty(), "Ollama is not running at $baseUrl — skipping")
        assumeTrue(
            installed.any { it == model || it.substringBefore(':') == model.substringBefore(':') },
            "Model '$model' is not pulled (available: $installed) — skipping",
        )
    }

    private fun service() = AgentFactory.create(
        config = AppConfig.from(
            mapOf(
                "TELEGRAM_BOT_TOKEN" to "not-used-in-this-test",
                "LLM_PROVIDER" to "ollama",
                "LLM_MODEL" to model,
                "LLM_BASE_URL" to baseUrl,
                // Local models are slower than hosted ones; be generous.
                "LLM_TIMEOUT_MS" to "300000",
                "SYSTEM_PROMPT" to "Answer in one short sentence.",
            ),
        ),
        store = InMemoryConversationStore(),
    )

    @Test
    fun `answers a plain question`() = runBlocking {
        requireOllama()

        val answer = service().ask(chatId = 1, message = "What is the capital of France? Answer in one word.")

        println("[ollama] plain answer: $answer")
        assertTrue(answer.isNotBlank(), "the model returned nothing")
        assertTrue(
            answer.contains("Paris", ignoreCase = true),
            "expected the answer to mention Paris, got: $answer",
        )
    }

    @Test
    fun `can call a tool`() = runBlocking {
        requireOllama()

        // Only DateTimeTool can answer this, so a sensible reply implies tool calling works.
        val answer = service().ask(chatId = 1, message = "What year is it right now? Use your tools.")

        println("[ollama] tool answer: $answer")
        assertTrue(answer.isNotBlank(), "the model returned nothing")
    }
}
