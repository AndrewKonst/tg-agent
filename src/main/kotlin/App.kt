import agent.AgentFactory
import config.AppConfig
import config.ConfigException
import config.ConversationStoreKind
import config.SandboxKind
import conversation.ConversationStore
import conversation.InMemoryConversationStore
import conversation.SqliteConversationStore
import dev.inmo.tgbotapi.bot.exceptions.UnauthorizedException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlinx.coroutines.runBlocking as runBlockingShutdown
import sandbox.DockerSandbox
import sandbox.HostSandbox
import sandbox.Sandbox
import telegram.MessageHandler
import telegram.TelegramBot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Wires the application together and keeps it running until the process is stopped.
 *
 *   Telegram -> TelegramBot -> MessageHandler -> AgentService -> Koog agent -> LLM
 */
fun main(): Unit = runBlocking {
    // Must run before anything else touches a logger.
    bootstrapLogging()
    val logger = KotlinLogging.logger("App")

    val config = try {
        AppConfig.load()
    } catch (e: ConfigException) {
        // Configuration problems are the operator's to fix; a stack trace only adds noise.
        logger.error { "Configuration error: ${e.message}" }
        exitProcess(1)
    }

    logger.info { "Starting tg-bot-ai with $config" }

    val store = buildConversationStore(config)
    val sandbox = buildSandbox(config, logger)
    sandbox?.let { logger.info { "Shell commands will run in: ${it.description}" } }
    val agentService = AgentFactory.create(config, store, sandbox)
    val messageHandler = MessageHandler(agentService, config.llmTimeout)

    // SupervisorJob: one failing request must not cancel the others or the poller.
    val requestScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("request"),
    )

    val bot = TelegramBot(config.telegramBotToken, messageHandler)

    val pollingJob = try {
        bot.start(requestScope)
    } catch (e: UnauthorizedException) {
        // By far the most likely startup mistake, so say exactly what to fix.
        logger.error { "Telegram rejected the bot token. Check TELEGRAM_BOT_TOKEN." }
        requestScope.cancel()
        exitProcess(1)
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start the Telegram bot" }
        requestScope.cancel()
        exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info { "Shutting down; cancelling in-flight requests" }
            requestScope.cancel()
            pollingJob.cancel()
            sandbox?.let { runBlockingShutdown { it.shutdown() } }
            (store as? AutoCloseable)?.close()
        },
    )

    pollingJob.join()
    logger.info { "Long polling stopped" }
}

/**
 * Picks where conversation history is kept.
 *
 * SQLite is the default because "one chat is one long conversation" is much more
 * convincing when it also survives a restart of the bot.
 */
private fun buildConversationStore(config: AppConfig): ConversationStore =
    when (config.conversationStore) {
        ConversationStoreKind.MEMORY -> InMemoryConversationStore()
        ConversationStoreKind.SQLITE -> SqliteConversationStore(Path.of(config.conversationDbPath))
    }

/**
 * Builds the sandbox the agent runs shell commands in, or nothing.
 *
 * The shell stays off unless it was asked for **and** somebody is allowed to use it.
 * An empty `OWNER_CHAT_IDS` with `EXEC_SANDBOX=docker` is almost certainly a
 * half-finished setup, and the safe reading of a half-finished setup is "no shell":
 * the alternative would hand a terminal to anyone who found the bot.
 */
private fun buildSandbox(config: AppConfig, logger: io.github.oshai.kotlinlogging.KLogger): Sandbox? {
    if (config.execSandbox == SandboxKind.OFF) {
        logger.info { "Shell commands are disabled (EXEC_SANDBOX=off)" }
        return null
    }
    if (config.ownerChatIds.isEmpty()) {
        logger.warn {
            "EXEC_SANDBOX=${config.execSandbox} but OWNER_CHAT_IDS is empty, so the shell " +
                "stays disabled. Send /whoami to the bot and put your chat id there."
        }
        return null
    }

    return when (config.execSandbox) {
        SandboxKind.DOCKER -> DockerSandbox(
            image = config.execImage,
            container = config.execContainer,
            gitDirectory = resolveGitDirectory(config, logger),
            commandTimeout = config.execTimeout,
            outputLimit = config.execOutputLimit,
        )

        SandboxKind.HOST -> HostSandbox(
            workingDirectory = Path.of(config.execWorkDir).toAbsolutePath(),
            commandTimeout = config.execTimeout,
            outputLimit = config.execOutputLimit,
        )

        SandboxKind.OFF -> null
    }
}

/**
 * Finds the repository to expose to the agent, read-only.
 *
 * Only `.git` is mounted, never the working tree: commit history is what the morning
 * routine needs, and the working tree is where `.env` lives.
 */
private fun resolveGitDirectory(
    config: AppConfig,
    logger: io.github.oshai.kotlinlogging.KLogger,
): Path? {
    val candidate = Path.of(config.projectGitDir ?: ".git").toAbsolutePath().normalize()
    if (Files.isDirectory(candidate)) return candidate

    logger.warn { "No git directory at $candidate; the agent will not see any commit history" }
    return null
}

/**
 * Makes every library log through Logback so the output has one format and one
 * place to configure levels.
 *
 * tgbotapi logs through `java.util.logging`, which would otherwise bypass Logback
 * and print its own raw stack traces to stderr.
 */
private fun bootstrapLogging() {
    KotlinLoggingConfiguration.logStartupMessage = false
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
}
