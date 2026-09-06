package conversation

import ai.koog.prompt.message.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a chat's messages live between requests.
 *
 * This is what turns the bot from a series of one-off questions into a conversation:
 * everything a chat has said and every tool result it produced is loaded back before
 * the next run.
 *
 * Implementations are called under the chat's lock (see [ChatLocks]), so they need
 * to be safe across chats but not within one.
 */
interface ConversationStore {
    /** Every stored message for [chatId], oldest first. */
    suspend fun load(chatId: Long): List<Message>

    /** Appends [messages] to the end of [chatId]'s history. */
    suspend fun append(chatId: Long, messages: List<Message>)

    /** Forgets everything in [chatId]. Backs the `/new` command. */
    suspend fun clear(chatId: Long)

    companion object {
        /**
         * Hard cap on stored messages per chat.
         *
         * The context window is bounded by [HistoryWindow] at read time; this is the
         * separate, much looser bound that keeps a months-old chat from growing
         * without limit on disk or in memory.
         */
        const val MAX_STORED_MESSAGES = 500
    }
}

/**
 * History kept in memory only.
 *
 * Everything is lost on restart, which is fine for tests and for a throwaway run.
 */
class InMemoryConversationStore : ConversationStore {

    private val chats = ConcurrentHashMap<Long, MutableList<Message>>()

    override suspend fun load(chatId: Long): List<Message> =
        chats[chatId]?.toList() ?: emptyList()

    override suspend fun append(chatId: Long, messages: List<Message>) {
        if (messages.isEmpty()) return
        val history = chats.getOrPut(chatId) { mutableListOf() }
        history += messages
        val excess = history.size - ConversationStore.MAX_STORED_MESSAGES
        if (excess > 0) repeat(excess) { history.removeFirst() }
    }

    override suspend fun clear(chatId: Long) {
        chats.remove(chatId)
    }
}

/**
 * One lock per chat.
 *
 * Every message is handled in its own coroutine, so a user who sends two messages
 * quickly would otherwise have two runs reading the same history, both appending,
 * and interleaving their tool calls into an unusable transcript. The whole
 * read-run-append sequence for a chat happens under this lock; different chats never
 * wait on each other.
 */
class ChatLocks {

    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(chatId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(chatId) { Mutex() }.withLock { block() }
}
