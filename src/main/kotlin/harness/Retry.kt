package harness

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Retry budget for transient LLM failures.
 *
 * This is the "Max Retries" guard, and it is counted **separately** from the step
 * limit in [AgentLoop] on purpose: one flaky connection must not eat the budget the
 * model needs for reasoning, and a model stuck in a tool-calling loop must not be
 * mistaken for a network problem.
 */
suspend fun <T> withRetry(
    maxAttempts: Int,
    block: suspend (attempt: Int) -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }

    var lastError: Throwable? = null
    for (attempt in 1..maxAttempts) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (!isTransient(e) || attempt == maxAttempts) throw e
            lastError = e
            val pause = backoff(attempt)
            logger.warn {
                "LLM call failed (attempt $attempt/$maxAttempts): ${describe(e)}; retrying in $pause"
            }
            delay(pause)
        }
    }
    // Unreachable: the loop either returns or rethrows on the final attempt.
    throw IllegalStateException("Retry loop exited without a result", lastError)
}

/**
 * Whether [error] is worth a second attempt.
 *
 * Only failures that a later identical request could survive: connectivity, provider
 * overload, and per-call timeouts. A 4xx other than 429 means the request itself is
 * wrong, so repeating it verbatim would only waste the budget.
 */
internal fun isTransient(error: Throwable): Boolean = when (error) {
    // Our own per-call timeout. Outer cancellation arrives as a different
    // CancellationException and is deliberately not matched here.
    is TimeoutCancellationException -> true
    is HttpRequestTimeoutException, is UnresolvedAddressException -> true
    is ResponseException -> error.response.status.value.let { it == 429 || it in 500..599 }
    is IOException -> true
    else -> false
}

/** Exponential backoff with jitter, so parallel chats do not retry in lockstep. */
private fun backoff(attempt: Int): Duration {
    val exponential = BASE_DELAY * (1 shl (attempt - 1))
    val capped = minOf(exponential, MAX_DELAY)
    val jitter = Random.nextDouble(JITTER_FLOOR, JITTER_CEILING)
    return capped * jitter
}

/** Status codes and exception types only — a provider error body can echo the prompt back. */
private fun describe(error: Throwable): String = when (error) {
    is ResponseException -> "HTTP ${error.response.status.value}"
    else -> error::class.simpleName ?: "unknown error"
}

private val BASE_DELAY = 500.milliseconds
private val MAX_DELAY = 8.seconds
private const val JITTER_FLOOR = 0.8
private const val JITTER_CEILING = 1.2
