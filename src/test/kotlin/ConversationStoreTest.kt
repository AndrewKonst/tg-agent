import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import conversation.ConversationStore
import conversation.InMemoryConversationStore
import conversation.SqliteConversationStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both stores are held to the same contract, so swapping `CONVERSATION_STORE`
 * cannot change how a conversation behaves — only whether it survives a restart.
 */
class ConversationStoreTest {

    @Test
    fun `in-memory store keeps a chat`() = runTest { roundTrip(InMemoryConversationStore()) }

    @Test
    fun `sqlite store keeps a chat`() = runTest {
        SqliteConversationStore(tempDb()).use { roundTrip(it) }
    }

    @Test
    fun `chats do not see each other`() = runTest {
        SqliteConversationStore(tempDb()).use { store ->
            store.append(1, listOf(user("from chat one")))
            store.append(2, listOf(user("from chat two")))

            assertEquals("from chat one", (store.load(1).single() as Message.User).textContent())
            assertEquals("from chat two", (store.load(2).single() as Message.User).textContent())
        }
    }

    @Test
    fun `clear forgets one chat and leaves the others alone`() = runTest {
        SqliteConversationStore(tempDb()).use { store ->
            store.append(1, listOf(user("keep me")))
            store.append(2, listOf(user("drop me")))

            store.clear(2)

            assertEquals(1, store.load(1).size)
            assertTrue(store.load(2).isEmpty())
        }
    }

    @Test
    fun `history outlives the process`() = runTest {
        val path = tempDb()
        SqliteConversationStore(path).use { it.append(7, listOf(user("remember this"))) }

        // A second instance stands in for a restart of the bot.
        SqliteConversationStore(path).use { store ->
            assertEquals("remember this", (store.load(7).single() as Message.User).textContent())
        }
    }

    @Test
    fun `tool calls and results survive a round trip`() = runTest {
        SqliteConversationStore(tempDb()).use { store ->
            store.append(
                1,
                listOf(
                    Message.Assistant(
                        listOf(MessagePart.Tool.Call(id = "c1", tool = "echo", args = """{"text":"hi"}""")),
                        ResponseMetaInfo.Empty,
                    ),
                    Message.User(
                        listOf(MessagePart.Tool.Result(id = "c1", tool = "echo", output = "echo: hi")),
                        RequestMetaInfo.Empty,
                    ),
                ),
            )

            val loaded = store.load(1)
            val call = (loaded[0] as Message.Assistant).parts.filterIsInstance<MessagePart.Tool.Call>().single()
            val result = (loaded[1] as Message.User).parts.filterIsInstance<MessagePart.Tool.Result>().single()

            assertEquals("echo", call.tool)
            assertEquals("""{"text":"hi"}""", call.args)
            assertEquals("c1", result.id)
            assertEquals("echo: hi", result.output)
        }
    }

    @Test
    fun `a very long chat is capped instead of growing forever`() = runTest {
        SqliteConversationStore(tempDb()).use { store ->
            val overflow = ConversationStore.MAX_STORED_MESSAGES + 50
            repeat(overflow) { store.append(1, listOf(user("message $it"))) }

            val loaded = store.load(1)

            assertEquals(ConversationStore.MAX_STORED_MESSAGES, loaded.size)
            // The newest messages are the ones kept.
            assertEquals("message ${overflow - 1}", (loaded.last() as Message.User).textContent())
        }
    }

    private suspend fun roundTrip(store: ConversationStore) {
        assertTrue(store.load(1).isEmpty(), "a fresh chat starts empty")

        store.append(1, listOf(user("first"), assistant("second")))
        store.append(1, listOf(user("third")))

        val loaded = store.load(1)
        assertEquals(3, loaded.size, "appends must accumulate in order")
        assertEquals("first", (loaded[0] as Message.User).textContent())
        assertEquals("second", (loaded[1] as Message.Assistant).textContent())
        assertEquals("third", (loaded[2] as Message.User).textContent())

        store.clear(1)
        assertTrue(store.load(1).isEmpty())
    }

    private fun tempDb(): Path = createTempDirectory("tg-bot-ai-test").resolve("conversations.db")

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)

    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
}
