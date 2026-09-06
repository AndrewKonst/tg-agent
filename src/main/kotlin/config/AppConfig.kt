package config

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * LLM backends the bot can be pointed at.
 *
 * Adding a provider means adding a branch here and a matching branch in
 * [agent.AgentFactory.buildPromptExecutor] — nothing in the Telegram layer changes.
 */
enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    OLLAMA;

    /** Ollama runs locally and needs no credentials. */
    val requiresApiKey: Boolean get() = this != OLLAMA

    companion object {
        fun parse(raw: String): LlmProvider =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw ConfigException(
                    "LLM_PROVIDER='$raw' is not supported. Expected one of: " +
                        entries.joinToString(", ") { it.name.lowercase() }
                )
    }
}

/** Where conversation history is kept between requests. */
enum class ConversationStoreKind {
    /** In the process only; a restart starts every chat over. */
    MEMORY,

    /** In a SQLite file, so chats survive a restart. */
    SQLITE;

    companion object {
        fun parse(raw: String): ConversationStoreKind =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw ConfigException(
                    "CONVERSATION_STORE='$raw' is not supported. Expected one of: " +
                        entries.joinToString(", ") { it.name.lowercase() }
                )
    }
}

/** Where the agent's shell commands are executed, if at all. */
enum class SandboxKind {
    /** Inside a Docker container. The only setting that isolates anything. */
    DOCKER,

    /** Directly on this machine. No isolation; opt in deliberately. */
    HOST,

    /** No shell at all. The `exec` tool is not offered to anyone. */
    OFF;

    companion object {
        fun parse(raw: String): SandboxKind =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw ConfigException(
                    "EXEC_SANDBOX='$raw' is not supported. Expected one of: " +
                        entries.joinToString(", ") { it.name.lowercase() }
                )
    }
}

/** Thrown when the environment is missing or malformed. Never carries secret values. */
class ConfigException(message: String) : RuntimeException(message)

/**
 * Immutable application configuration, read once at startup.
 *
 * Secrets live only in this object; [toString] is overridden so an accidental log
 * statement can never leak the Telegram token or the LLM API key.
 */
data class AppConfig(
    val telegramBotToken: String,
    val llmProvider: LlmProvider,
    val llmApiKey: String,
    val llmModel: String,
    val llmBaseUrl: String?,
    /** Hard deadline for a whole agentic run, however many steps it takes. */
    val llmTimeout: Duration,
    /** Deadline for a single call to the model, inside a run. */
    val llmCallTimeout: Duration,
    /** How many times one model call may be retried after a transient failure. */
    val llmMaxAttempts: Int,
    val systemPrompt: String,
    /** Loop guard: how many times the model may be asked before a run is cut short. */
    val agentMaxSteps: Int,
    val conversationStore: ConversationStoreKind,
    val conversationDbPath: String,
    /** Roughly how much of a chat's history is replayed into each run. */
    val conversationMaxChars: Int,
    /**
     * Chats allowed to run shell commands.
     *
     * Anyone can find a bot on Telegram, so this list is the only thing standing
     * between a stranger and a shell. Empty means nobody, and `exec` stays off.
     */
    val ownerChatIds: Set<Long>,
    val execSandbox: SandboxKind,
    /** Hard limit on a single shell command. */
    val execTimeout: Duration,
    /** How much of a command's output reaches the model, in characters. */
    val execOutputLimit: Int,
    val execImage: String,
    val execContainer: String,
    /** Working directory for `EXEC_SANDBOX=host`. Unused for Docker. */
    val execWorkDir: String,
    /** Repository exposed read-only to the agent, or null to expose nothing. */
    val projectGitDir: String?,
    /** Directory of Markdown skill files the agent can read on demand. */
    val skillsDir: String,
) {
    override fun toString(): String =
        "AppConfig(llmProvider=$llmProvider, llmModel='$llmModel', " +
            "llmBaseUrl=${llmBaseUrl ?: "<provider default>"}, llmTimeout=$llmTimeout, " +
            "llmCallTimeout=$llmCallTimeout, llmMaxAttempts=$llmMaxAttempts, " +
            "agentMaxSteps=$agentMaxSteps, conversationStore=$conversationStore, " +
            "conversationMaxChars=$conversationMaxChars, execSandbox=$execSandbox, " +
            "owners=${ownerChatIds.size}, execTimeout=$execTimeout, " +
            "telegramBotToken=***, llmApiKey=***)"

    companion object {
        const val DEFAULT_SYSTEM_PROMPT: String =
            "You are a helpful assistant answering inside a Telegram chat. " +
                "Keep answers concise and easy to read on a phone. " +
                "Use plain text; avoid Markdown tables and heavy formatting."

        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_CALL_TIMEOUT_MS = 60_000L
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_MAX_STEPS = 8
        private const val DEFAULT_DB_PATH = "data/conversations.db"

        /**
         * Roughly 3-4k tokens of history. Deliberately modest: the local models this
         * bot is aimed at have a small default context, and overflowing it silently
         * drops the beginning of the conversation instead of failing loudly.
         */
        private const val DEFAULT_MAX_CHARS = 12_000
        private const val DEFAULT_EXEC_TIMEOUT_MS = 30_000L
        private const val DEFAULT_EXEC_OUTPUT_LIMIT = 4_000
        private const val DEFAULT_EXEC_IMAGE = "tg-agent-sandbox:1"
        private const val DEFAULT_EXEC_CONTAINER = "tg-agent-sandbox"
        private const val DEFAULT_EXEC_WORKDIR = "data/workspace"
        private const val DEFAULT_SKILLS_DIR = "skills"

        /**
         * Builds the config from environment variables, falling back to a local `.env`
         * file when a variable is absent. Real environment variables always win, so
         * a stray `.env` on a server cannot override the deployed configuration.
         */
        fun load(
            env: Map<String, String> = System.getenv(),
            dotenvFile: File = File(".env"),
        ): AppConfig {
            val source = DotEnv.read(dotenvFile) + env.filterValues { it.isNotBlank() }
            return from(source)
        }

        /** Pure construction from an already-merged key/value source. Used directly by tests. */
        fun from(source: Map<String, String>): AppConfig {
            val missing = mutableListOf<String>()

            fun required(key: String): String =
                source[key]?.trim()?.takeIf { it.isNotEmpty() } ?: "".also { missing += key }

            val telegramToken = required("TELEGRAM_BOT_TOKEN")
            val provider = source["LLM_PROVIDER"]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { LlmProvider.parse(it) }
                ?: LlmProvider.OPENAI
            val apiKey = if (provider.requiresApiKey) required("LLM_API_KEY") else source["LLM_API_KEY"].orEmpty()
            val model = required("LLM_MODEL")

            if (missing.isNotEmpty()) {
                throw ConfigException(
                    "Missing required environment variable(s): ${missing.joinToString(", ")}. " +
                        "Copy .env.example to .env and fill it in, or export them in the environment."
                )
            }

            return AppConfig(
                telegramBotToken = telegramToken,
                llmProvider = provider,
                llmApiKey = apiKey,
                llmModel = model,
                llmBaseUrl = source["LLM_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() },
                llmTimeout = positiveLong(source, "LLM_TIMEOUT_MS", DEFAULT_TIMEOUT_MS).milliseconds,
                llmCallTimeout = positiveLong(source, "LLM_CALL_TIMEOUT_MS", DEFAULT_CALL_TIMEOUT_MS).milliseconds,
                llmMaxAttempts = positiveLong(source, "LLM_MAX_ATTEMPTS", DEFAULT_MAX_ATTEMPTS.toLong()).toInt(),
                systemPrompt = source["SYSTEM_PROMPT"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_SYSTEM_PROMPT,
                agentMaxSteps = positiveLong(source, "AGENT_MAX_STEPS", DEFAULT_MAX_STEPS.toLong()).toInt(),
                conversationStore = source["CONVERSATION_STORE"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { ConversationStoreKind.parse(it) }
                    ?: ConversationStoreKind.SQLITE,
                conversationDbPath = source["CONVERSATION_DB_PATH"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_DB_PATH,
                conversationMaxChars = positiveLong(
                    source, "CONVERSATION_MAX_CHARS", DEFAULT_MAX_CHARS.toLong(),
                ).toInt(),
                ownerChatIds = chatIds(source["OWNER_CHAT_IDS"]),
                execSandbox = source["EXEC_SANDBOX"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { SandboxKind.parse(it) }
                    ?: SandboxKind.DOCKER,
                execTimeout = positiveLong(source, "EXEC_TIMEOUT_MS", DEFAULT_EXEC_TIMEOUT_MS).milliseconds,
                execOutputLimit = positiveLong(
                    source, "EXEC_OUTPUT_LIMIT", DEFAULT_EXEC_OUTPUT_LIMIT.toLong(),
                ).toInt(),
                execImage = source["EXEC_IMAGE"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_EXEC_IMAGE,
                execContainer = source["EXEC_CONTAINER"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_EXEC_CONTAINER,
                execWorkDir = source["EXEC_WORKDIR"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_EXEC_WORKDIR,
                projectGitDir = source["PROJECT_GIT_DIR"]?.trim()?.takeIf { it.isNotEmpty() },
                skillsDir = source["SKILLS_DIR"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SKILLS_DIR,
            )
        }

        /**
         * Parses a comma- or space-separated list of chat ids.
         *
         * A typo here would silently hand a shell to nobody — or, worse, look like it
         * had been configured when it had not — so anything unparseable is fatal at
         * startup rather than ignored.
         */
        private fun chatIds(raw: String?): Set<Long> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return emptySet()
            return trimmed.split(',', ' ', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { entry ->
                    entry.toLongOrNull()
                        ?: throw ConfigException("OWNER_CHAT_IDS must be numeric chat ids, got '$entry'.")
                }
                .toSet()
        }

        private fun positiveLong(source: Map<String, String>, key: String, default: Long): Long {
            val raw = source[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = raw.toLongOrNull()
                ?: throw ConfigException("$key must be an integer, got '$raw'.")
            if (value <= 0) throw ConfigException("$key must be greater than 0, got $value.")
            return value
        }
    }
}

/** Minimal `.env` reader: `KEY=VALUE` lines, `#` comments, optional surrounding quotes. */
internal object DotEnv {
    fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                val key = line.substring(0, separator).removePrefix("export ").trim()
                val value = line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                key.takeIf { it.isNotEmpty() }?.let { it to value }
            }
            .filter { (_, value) -> value.isNotBlank() }
            .toMap()
    }
}
