import config.AppConfig
import config.ConfigException
import config.LlmProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class AppConfigTest {

    private val valid = mapOf(
        "TELEGRAM_BOT_TOKEN" to "123:abc",
        "LLM_API_KEY" to "sk-secret",
        "LLM_MODEL" to "gpt-4o-mini",
    )

    @Test
    fun `applies documented defaults`() {
        val config = AppConfig.from(valid)

        assertEquals(LlmProvider.OPENAI, config.llmProvider)
        assertEquals(30_000.milliseconds, config.llmTimeout)
        assertEquals(AppConfig.DEFAULT_SYSTEM_PROMPT, config.systemPrompt)
    }

    @Test
    fun `reads every documented variable`() {
        val config = AppConfig.from(
            valid + mapOf(
                "LLM_PROVIDER" to "anthropic",
                "LLM_TIMEOUT_MS" to "5000",
                "SYSTEM_PROMPT" to "Be terse.",
                "AGENT_MAX_STEPS" to "7",
                "LLM_CALL_TIMEOUT_MS" to "9000",
                "LLM_MAX_ATTEMPTS" to "2",
            ),
        )

        assertEquals(LlmProvider.ANTHROPIC, config.llmProvider)
        assertEquals(5_000.milliseconds, config.llmTimeout)
        assertEquals("Be terse.", config.systemPrompt)
        assertEquals(7, config.agentMaxSteps)
        assertEquals(9_000.milliseconds, config.llmCallTimeout)
        assertEquals(2, config.llmMaxAttempts)
    }

    @Test
    fun `owner chat ids are parsed from a list`() {
        val config = AppConfig.from(valid + mapOf("OWNER_CHAT_IDS" to " 111, 222  333 "))

        assertEquals(setOf(111L, 222L, 333L), config.ownerChatIds)
    }

    @Test
    fun `nobody owns the bot by default, so the shell stays off`() {
        assertEquals(emptySet(), AppConfig.from(valid).ownerChatIds)
    }

    @Test
    fun `a malformed owner id is fatal rather than silently ignored`() {
        // Silently dropping it would look configured while granting nothing — or,
        // worse, grant it to the wrong chat.
        val error = assertFailsWith<ConfigException> {
            AppConfig.from(valid + mapOf("OWNER_CHAT_IDS" to "111,@konst"))
        }

        assertContains(error.message!!, "@konst")
    }

    @Test
    fun `an unknown sandbox kind names the supported ones`() {
        val error = assertFailsWith<ConfigException> {
            AppConfig.from(valid + mapOf("EXEC_SANDBOX" to "vm"))
        }

        assertContains(error.message!!, "docker")
        assertContains(error.message!!, "off")
    }

    @Test
    fun `names every missing variable at once`() {
        val error = assertFailsWith<ConfigException> { AppConfig.from(emptyMap()) }

        assertContains(error.message!!, "TELEGRAM_BOT_TOKEN")
        assertContains(error.message!!, "LLM_API_KEY")
        assertContains(error.message!!, "LLM_MODEL")
    }

    @Test
    fun `blank values count as missing`() {
        val error = assertFailsWith<ConfigException> {
            AppConfig.from(valid + ("TELEGRAM_BOT_TOKEN" to "   "))
        }
        assertContains(error.message!!, "TELEGRAM_BOT_TOKEN")
    }

    @Test
    fun `ollama needs no api key`() {
        val config = AppConfig.from(
            mapOf(
                "TELEGRAM_BOT_TOKEN" to "123:abc",
                "LLM_MODEL" to "llama3.2",
                "LLM_PROVIDER" to "ollama",
            ),
        )
        assertEquals(LlmProvider.OLLAMA, config.llmProvider)
    }

    @Test
    fun `rejects an unknown provider`() {
        val error = assertFailsWith<ConfigException> {
            AppConfig.from(valid + ("LLM_PROVIDER" to "hal9000"))
        }
        assertContains(error.message!!, "hal9000")
    }

    @Test
    fun `rejects a non-numeric or non-positive timeout`() {
        assertFailsWith<ConfigException> { AppConfig.from(valid + ("LLM_TIMEOUT_MS" to "soon")) }
        assertFailsWith<ConfigException> { AppConfig.from(valid + ("LLM_TIMEOUT_MS" to "0")) }
        assertFailsWith<ConfigException> { AppConfig.from(valid + ("LLM_TIMEOUT_MS" to "-1")) }
    }

    @Test
    fun `toString never leaks secrets`() {
        val rendered = AppConfig.from(valid).toString()

        assertFalse(rendered.contains("sk-secret"), "API key leaked into toString()")
        assertFalse(rendered.contains("123:abc"), "Telegram token leaked into toString()")
    }
}
