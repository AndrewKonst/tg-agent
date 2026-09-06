package telegram

import agent.AgentService
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import error.ErrorHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Handles one text message: ask the agent, reply with the answer.
 *
 * Holds no per-request state — everything about a request lives on the stack of
 * [handle] — so a single instance is safe to share across all concurrent users.
 */
class MessageHandler(
    private val agentService: AgentService,
    private val timeout: Duration,
) {

    /**
     * Processes [message] and replies. Never throws for an ordinary failure: any
     * error becomes a friendly reply, so one bad request cannot stop long polling.
     */
    suspend fun handle(context: BehaviourContext, message: ChatContentMessage<TextContent>) {
        val text = message.content.text.trim()
        if (text.isEmpty()) return

        val reply = answerOrExplain(text, message.chat.id.chatId.long)
        sendReply(context, message, reply)
    }

    /**
     * Runs the agent under [timeout] and returns either its answer or a friendly
     * explanation of what went wrong.
     *
     * This is where the resilience requirements are met, and it is deliberately free
     * of Telegram types so it can be tested on its own. Cancellation of the enclosing
     * scope (shutdown) is propagated rather than swallowed.
     */
    internal suspend fun answerOrExplain(text: String, chatId: Long): String {
        logger.info { "Request from chat=$chatId (${text.length} chars)" }

        return try {
            val answer = withTimeout(timeout) {
                agentService.ask(chatId, text)
            }
            logger.info { "Answered chat=$chatId (${answer.length} chars)" }
            answer
        } catch (e: TimeoutCancellationException) {
            // Must be caught before CancellationException — it is a subtype of it.
            ErrorHandler.toUserMessage(e, "chat=$chatId")
        } catch (e: CancellationException) {
            // The bot is shutting down, or the parent scope was cancelled. Stay cooperative.
            logger.debug { "Request for chat=$chatId cancelled" }
            throw e
        } catch (e: Throwable) {
            ErrorHandler.toUserMessage(e, "chat=$chatId")
        }
    }

    /**
     * Clears [chatId]'s history and returns the confirmation to send back.
     *
     * Failure is reported to the user rather than swallowed: silently continuing an
     * old conversation after someone asked for a new one is worse than an error.
     */
    internal suspend fun startNewChat(chatId: Long): String = try {
        agentService.reset(chatId)
        logger.info { "chat=$chatId: started a new conversation" }
        NEW_CHAT_CONFIRMATION
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.error(e) { "Failed to clear history for chat=$chatId" }
        "I could not clear the history. Please try again."
    }

    /**
     * Sends [text] back, splitting it across messages when it exceeds Telegram's
     * per-message limit. A Telegram API failure here is logged, not rethrown —
     * there is no way left to tell the user about it.
     */
    private suspend fun sendReply(
        context: BehaviourContext,
        message: ChatContentMessage<TextContent>,
        text: String,
    ) {
        try {
            chunk(text).forEach { part -> context.reply(message, part) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error(e) { "Failed to deliver reply to chat=${message.chat.id.chatId.long}" }
        }
    }

    internal companion object {
        const val NEW_CHAT_CONFIRMATION =
            "History cleared. This is a new chat — I no longer remember what we discussed."

        /** Telegram rejects text messages longer than 4096 UTF-16 code units. */
        const val TELEGRAM_MAX_MESSAGE_LENGTH = 4096

        /**
         * Splits [text] into Telegram-sized parts, preferring to break at a paragraph
         * or line boundary so lists and code blocks stay readable.
         */
        fun chunk(text: String, limit: Int = TELEGRAM_MAX_MESSAGE_LENGTH): List<String> {
            require(limit > 0) { "limit must be positive" }
            if (text.length <= limit) return listOf(text)

            val parts = mutableListOf<String>()
            var rest = text
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                val breakAt = window.lastIndexOf("\n\n").takeIf { it > limit / 2 }
                    ?: window.lastIndexOf('\n').takeIf { it > limit / 2 }
                    ?: window.lastIndexOf(' ').takeIf { it > limit / 2 }
                    ?: limit
                parts += rest.substring(0, breakAt).trimEnd()
                rest = rest.substring(breakAt).trimStart()
            }
            if (rest.isNotEmpty()) parts += rest
            return parts
        }
    }
}
