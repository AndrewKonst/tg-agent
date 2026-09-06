package conversation

import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * History kept in a SQLite file, so a chat survives a restart of the bot.
 *
 * Messages are stored as JSON: Koog's [Message] is a `@Serializable` sealed
 * hierarchy, so a message round-trips through one column with no hand-written
 * mapping and no schema to migrate whenever a new part type appears.
 *
 * JDBC is blocking, so every statement runs on [Dispatchers.IO]. A single connection
 * guarded by a [Mutex] is enough here — traffic is a handful of statements per
 * message — and it sidesteps SQLite's writer contention entirely. WAL is enabled so
 * a reader is never blocked by the writer.
 */
class SqliteConversationStore(databasePath: Path) : ConversationStore, AutoCloseable {

    private val connection: Connection
    private val mutex = Mutex()

    init {
        databasePath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    chat_id INTEGER NOT NULL,
                    seq     INTEGER NOT NULL,
                    payload TEXT    NOT NULL,
                    PRIMARY KEY (chat_id, seq)
                )
                """.trimIndent(),
            )
        }
        logger.info { "Conversation history: sqlite at ${databasePath.toAbsolutePath()}" }
    }

    override suspend fun load(chatId: Long): List<Message> = io {
        val messages = mutableListOf<Message>()
        connection.prepareStatement(
            "SELECT payload FROM messages WHERE chat_id = ? ORDER BY seq",
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val payload = rows.getString(1)
                    // A row written by an older build must not break the chat: skip it.
                    runCatching { json.decodeFromString(Message.serializer(), payload) }
                        .onSuccess { messages += it }
                        .onFailure { logger.warn { "Dropping an unreadable history row for chat=$chatId" } }
                }
            }
        }
        messages
    }

    override suspend fun append(chatId: Long, messages: List<Message>) {
        if (messages.isEmpty()) return
        io {
            var seq = nextSequence(chatId)
            connection.prepareStatement(
                "INSERT INTO messages (chat_id, seq, payload) VALUES (?, ?, ?)",
            ).use { statement ->
                for (message in messages) {
                    statement.setLong(1, chatId)
                    statement.setLong(2, seq++)
                    statement.setString(3, json.encodeToString(Message.serializer(), message))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            prune(chatId)
        }
    }

    override suspend fun clear(chatId: Long) {
        io {
            connection.prepareStatement("DELETE FROM messages WHERE chat_id = ?").use { statement ->
                statement.setLong(1, chatId)
                statement.executeUpdate()
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun nextSequence(chatId: Long): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE chat_id = ?")
            .use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 1L }
            }

    /** Keeps only the newest [ConversationStore.MAX_STORED_MESSAGES] rows of a chat. */
    private fun prune(chatId: Long) {
        connection.prepareStatement(
            """
            DELETE FROM messages
            WHERE chat_id = ?
              AND seq <= (
                SELECT MAX(seq) - ? FROM messages WHERE chat_id = ?
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setInt(2, ConversationStore.MAX_STORED_MESSAGES)
            statement.setLong(3, chatId)
            statement.executeUpdate()
        }
    }

    /** Runs [block] off the event loop, one statement batch at a time. */
    private suspend fun <T> io(block: () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        val json = Json { encodeDefaults = false }
    }
}
