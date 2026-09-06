package logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * Drops the one log entry tgbotapi emits when an idle long poll times out.
 *
 * Long polling holds `getUpdates` open until an update arrives, and tgbotapi derives
 * the HTTP request timeout from the same `timeoutSeconds` value as the poll window.
 * The two therefore expire together, so **every** idle poll loses the race and is
 * logged as an ERROR with a stack trace — roughly every 20 seconds on a quiet bot.
 *
 * The condition is benign and already handled: `autoSkipTimeoutExceptions` makes
 * tgbotapi retry, and polling continues. Only the log entry is wrong, and left alone
 * it would bury real problems and trip alerting.
 *
 * The match is deliberately narrow — this exact exception type on this exact endpoint
 * — so every other tgbotapi diagnostic still reaches the log.
 */
class PollTimeoutFilter : Filter<ILoggingEvent>() {

    override fun decide(event: ILoggingEvent): FilterReply {
        val throwable = event.throwableProxy ?: return FilterReply.NEUTRAL

        val isRequestTimeout = throwable.className == KTOR_TIMEOUT_EXCEPTION
        val isUpdatePoll = throwable.message?.contains(GET_UPDATES) == true

        return if (isRequestTimeout && isUpdatePoll) FilterReply.DENY else FilterReply.NEUTRAL
    }

    private companion object {
        const val KTOR_TIMEOUT_EXCEPTION = "io.ktor.client.plugins.HttpRequestTimeoutException"
        const val GET_UPDATES = "/getUpdates"
    }
}
