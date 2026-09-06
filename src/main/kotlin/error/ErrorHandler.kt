package error

import agent.AgentException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

private val logger = KotlinLogging.logger {}

/**
 * Turns any failure into a short, non-technical reply.
 *
 * Two rules: the user never sees a stack trace, an internal class name, or anything
 * derived from a secret; and the operator always gets the full detail in the log.
 */
object ErrorHandler {

    /**
     * Logs [error] with full context and returns the text to send back to the user.
     *
     * @param context short description of what was being attempted, for the log only.
     */
    fun toUserMessage(error: Throwable, context: String): String = when (error) {
        is TimeoutCancellationException -> {
            logger.warn { "$context: timed out waiting for the AI response" }
            "The AI took too long to respond. Please try again, or ask something shorter."
        }

        is UnknownHostException, is UnresolvedAddressException, is HttpRequestTimeoutException -> {
            logger.error(error) { "$context: network failure reaching the AI provider" }
            "I can't reach the AI service right now. Please try again in a moment."
        }

        is ResponseException -> {
            // Status only — a provider error body can echo back request content.
            logger.error(error) { "$context: AI provider returned HTTP ${error.response.status}" }
            when (error.response.status.value) {
                401, 403 -> "The AI service rejected my credentials. Please contact the bot administrator."
                429 -> "The AI service is rate-limiting me. Please try again shortly."
                in 500..599 -> "The AI service is having trouble. Please try again in a moment."
                else -> GENERIC
            }
        }

        is AgentException -> {
            logger.error(error) { "$context: agent failure" }
            GENERIC
        }

        is IOException -> {
            logger.error(error) { "$context: I/O failure" }
            "I can't reach the AI service right now. Please try again in a moment."
        }

        else -> {
            logger.error(error) { "$context: unexpected failure" }
            GENERIC
        }
    }

    private const val GENERIC =
        "Something went wrong while I was thinking. Please try again."
}
