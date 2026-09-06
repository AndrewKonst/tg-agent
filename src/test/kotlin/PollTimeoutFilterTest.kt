import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.spi.FilterReply
import io.ktor.client.plugins.HttpRequestTimeoutException
import logging.PollTimeoutFilter
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the filter's scope.
 *
 * It exists to hide one specific benign event, so the risk is that it grows broad
 * enough to silently swallow real failures. These cases fail if that happens.
 */
class PollTimeoutFilterTest {

    private val filter = PollTimeoutFilter()

    private fun event(throwable: Throwable?): LoggingEvent = LoggingEvent().apply {
        loggerName = "KSLog"
        level = Level.ERROR
        message = "Something web wrong"
        if (throwable != null) setThrowableProxy(ThrowableProxy(throwable))
    }

    private fun ktorTimeout(url: String) = HttpRequestTimeoutException(url, 20_000L, null)

    @Test
    fun `denies the idle getUpdates timeout`() {
        val event = event(ktorTimeout("https://api.telegram.org/bot123:abc/getUpdates"))

        assertEquals(FilterReply.DENY, filter.decide(event))
    }

    @Test
    fun `keeps a timeout on any other endpoint`() {
        val event = event(ktorTimeout("https://api.telegram.org/bot123:abc/sendMessage"))

        assertEquals(
            FilterReply.NEUTRAL,
            filter.decide(event),
            "a timeout while sending a reply is a real problem and must stay visible",
        )
    }

    @Test
    fun `keeps unrelated network failures`() {
        assertEquals(
            FilterReply.NEUTRAL,
            filter.decide(event(IOException("Connection refused to api.telegram.org"))),
            "a real connectivity failure must stay visible",
        )
    }

    @Test
    fun `keeps events without a throwable`() {
        assertEquals(FilterReply.NEUTRAL, filter.decide(event(null)))
    }
}
