package agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import conversation.ChatLocks
import conversation.ConversationStore
import conversation.HistoryWindow
import conversation.withoutReasoning
import harness.AgentLoop
import harness.StopReason
import harness.ToolBox
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * The single entry point into AI logic.
 *
 * The Telegram layer depends on this interface only, so it never learns which
 * provider, model, or tools are in play.
 */
interface AgentService {
    /**
     * Answers [message] in the context of everything [chatId] has said before.
     *
     * Implementations must be safe to call concurrently and must stay cancellable:
     * cancelling the calling coroutine has to abort the in-flight run.
     *
     * @throws AgentException when the agent could not produce an answer.
     */
    suspend fun ask(chatId: Long, message: String): String

    /** Forgets [chatId]'s history, starting a fresh conversation. */
    suspend fun reset(chatId: Long)
}

/** Raised when the agent fails. The Telegram layer turns this into a friendly reply. */
class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * What one chat is allowed to do and what it is told.
 *
 * Capability and instructions travel together on purpose: telling a chat about
 * skills it cannot act on — because it has no shell — would only invite the model to
 * promise things it cannot deliver.
 */
data class AgentProfile(
    val systemPrompt: String,
    val tools: ToolBox,
)

/**
 * [AgentService] backed by our own [AgentLoop], with per-chat conversation history.
 *
 * One chat is one long conversation: the stored history is replayed into every run,
 * so the model can refer back to what was said and to what its tools returned.
 *
 * Reading the history, running the loop, and appending the result happen under the
 * chat's lock as a single unit. Without that, two quick messages from one user would
 * both read the same history and write interleaved transcripts over each other.
 */
class HarnessAgentService(
    private val loop: AgentLoop,
    private val store: ConversationStore,
    private val window: HistoryWindow,
    /** What the chat may do, and what it is told. See [AgentFactory] for who gets what. */
    private val profileFor: (chatId: Long) -> AgentProfile,
    private val clock: KoogClock = KoogClock.System,
) : AgentService {

    private val locks = ChatLocks()

    override suspend fun ask(chatId: Long, message: String): String = locks.withLock(chatId) {
        val profile = profileFor(chatId)
        val history = window.fit(store.load(chatId))
        val userMessage = Message.User(message, RequestMetaInfo.create(clock))

        val conversation = buildList {
            add(Message.System(profile.systemPrompt, RequestMetaInfo.create(clock)))
            addAll(history)
            add(userMessage)
        }
        logger.debug { "chat=$chatId: replaying ${history.size} message(s) of history" }

        val run = try {
            loop.run(conversation, profile.tools)
        } catch (e: CancellationException) {
            // Timeout or shutdown: let the caller's coroutine machinery handle it.
            throw e
        } catch (e: Throwable) {
            // Logged with the stack trace here; the user-facing text comes from ErrorHandler.
            logger.error(e) { "Agent run failed" }
            throw AgentException("Agent run failed: ${e::class.simpleName}", e)
        }

        // Only a completed run is remembered, so a failure cannot leave a half-written
        // exchange — an assistant tool call with no result — poisoning the next turn.
        store.append(chatId, buildList {
            add(userMessage)
            addAll(run.appended.map { it.withoutReasoning() })
        })

        if (run.stopReason != StopReason.COMPLETED) {
            logger.warn { "chat=$chatId: run ended as ${run.stopReason} after ${run.steps} step(s)" }
        }
        run.answer
    }

    override suspend fun reset(chatId: Long) = locks.withLock(chatId) {
        store.clear(chatId)
        logger.info { "chat=$chatId: history cleared" }
    }
}
