package telegram

import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Owns the Telegram connection: creates the bot, starts long polling, and routes
 * updates to [MessageHandler].
 *
 * Deliberately thin — it knows nothing about LLMs, providers, or tools.
 */
class TelegramBot(
    private val token: String,
    private val messageHandler: MessageHandler,
) {

    /**
     * Starts long polling and returns the [Job] that runs it.
     *
     * Each incoming message is dispatched into its own coroutine on [requestScope],
     * so a slow LLM call for one user never delays anyone else's message. That scope
     * is expected to be backed by a `SupervisorJob`, so a failed request cannot take
     * down its siblings or the polling loop.
     */
    suspend fun start(requestScope: CoroutineScope): Job {
        val bot = telegramBot(token)

        val me = bot.getMe()
        logger.info { "Connected to Telegram as @${me.username?.withoutAt ?: me.firstName}" }

        return bot.buildBehaviourWithLongPolling(
            // A failure that escapes a handler is logged; polling continues.
            defaultExceptionsHandler = { e ->
                logger.error(e) { "Unhandled exception in Telegram update processing" }
            },
            // An idle long poll timing out is normal, not an error worth logging.
            autoSkipTimeoutExceptions = true,
            timeoutSeconds = POLL_TIMEOUT_SECONDS,
        ) {
            onCommand("start") { message ->
                reply(
                    message,
                    "Hi! Send me a message and I'll answer it with AI.",
                )
            }

            onCommand("help") { message ->
                reply(
                    message,
                    "Just send any text message and I'll pass it to the AI and reply.\n\n" +
                        "A chat is one long conversation: I remember what we already said, " +
                        "and what my tools returned.\n\n" +
                        "/new — forget this conversation and start a fresh one\n" +
                        "/whoami — show your chat id",
                )
            }

            onCommand("new") { message ->
                // Runs on the polling coroutine: clearing history is a single fast write,
                // and doing it here keeps it ordered against the messages around it.
                reply(message, messageHandler.startNewChat(message.chat.id.chatId.long))
            }

            onCommand("whoami") { message ->
                val chatId = message.chat.id.chatId.long
                reply(message, "Your chat id is $chatId.")
            }

            onText { message ->
                // Skip commands; they are handled by the triggers above.
                if (message.content.text.startsWith("/")) return@onText

                requestScope.launch {
                    messageHandler.handle(this@buildBehaviourWithLongPolling, message)
                }
            }

            logger.info { "Long polling started; waiting for messages" }
        }
    }

    private companion object {
        /**
         * Long-poll window. Must stay below tgbotapi's own 30s HTTP request timeout,
         * otherwise every idle poll aborts as a timeout instead of returning cleanly.
         */
        const val POLL_TIMEOUT_SECONDS = 20
    }
}
