import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import harness.AgentLoop
import harness.AgentTool
import harness.Llm
import harness.StopReason
import harness.ToolBox
import harness.requiredString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the agentic loop itself, against a scripted model.
 *
 * The point of these is that every guard can be provoked deterministically: no
 * network, no provider, and no waiting for a real model to misbehave.
 */
class AgentLoopTest {

    @Test
    fun `answers directly when the model asks for no tools`() = runTest {
        val llm = LlmStub { _, _ -> assistant("Two.") }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("What is one plus one?"), ToolBox(listOf(EchoTool())))

        assertEquals("Two.", run.answer)
        assertEquals(1, run.steps)
        assertEquals(StopReason.COMPLETED, run.stopReason)
    }

    @Test
    fun `runs a tool and feeds its result back to the model`() = runTest {
        val tool = EchoTool()
        val llm = LlmStub { call, _ ->
            if (call == 1) assistantCall("c1", "echo", """{"text":"ping"}""") else assistant("Done.")
        }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("say ping"), ToolBox(listOf(tool)))

        assertEquals("Done.", run.answer)
        assertEquals(2, run.steps)
        assertEquals(1, tool.invocations.size)

        // The second call must have carried the tool's output back to the model.
        val secondPrompt = llm.prompts[1]
        val result = secondPrompt.filterIsInstance<Message.User>()
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool.Result>()
            .single()
        assertEquals("echo: ping", result.output)
        assertTrue(!result.isError)
    }

    @Test
    fun `stops at the step limit instead of looping forever`() = runTest {
        // A model that never stops asking for tools, with different arguments each time
        // so the duplicate detector does not fire first.
        val llm = LlmStub { call, _ -> assistantCall("c$call", "echo", """{"text":"call $call"}""") }
        val loop = AgentLoop(llm, maxSteps = 4)

        val run = loop.run(conversation("go"), ToolBox(listOf(EchoTool())))

        assertEquals(StopReason.MAX_STEPS, run.stopReason)
        assertEquals(4, run.steps)
        assertEquals(4, llm.calls, "the model must not be asked more than maxSteps times")
        assertContains(run.answer, "ran out of steps")
    }

    @Test
    fun `gives up when the model repeats one identical call`() = runTest {
        val tool = EchoTool()
        val llm = LlmStub { _, _ -> assistantCall("c1", "echo", """{"text":"same"}""") }
        val loop = AgentLoop(llm, maxSteps = 20)

        val run = loop.run(conversation("go"), ToolBox(listOf(tool)))

        assertEquals(StopReason.REPEATED_TOOL_CALL, run.stopReason)
        // Stopped long before the step budget, and the tool ran only once.
        assertEquals(3, run.steps)
        assertEquals(1, tool.invocations.size, "a repeated call must not be executed again")
    }

    @Test
    fun `an unknown tool name is reported to the model rather than failing the run`() = runTest {
        val llm = LlmStub { call, _ ->
            if (call == 1) assistantCall("c1", "no_such_tool", "{}") else assistant("Recovered.")
        }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("go"), ToolBox(listOf(EchoTool())))

        assertEquals("Recovered.", run.answer)
        val result = toolResults(llm.prompts[1]).single()
        assertTrue(result.isError)
        assertContains(result.output, "Unknown tool")
        assertContains(result.output, "echo", ignoreCase = true)
    }

    @Test
    fun `bad arguments come back as a correctable error`() = runTest {
        val llm = LlmStub { call, _ ->
            if (call == 1) assistantCall("c1", "echo", """{"wrong":"key"}""") else assistant("Fixed.")
        }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("go"), ToolBox(listOf(EchoTool())))

        assertEquals("Fixed.", run.answer)
        val result = toolResults(llm.prompts[1]).single()
        assertTrue(result.isError)
        assertContains(result.output, "text")
    }

    @Test
    fun `a tool that throws does not abort the run`() = runTest {
        val llm = LlmStub { call, _ ->
            if (call == 1) assistantCall("c1", "boom", "{}") else assistant("Carried on.")
        }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("go"), ToolBox(listOf(ExplodingTool())))

        assertEquals("Carried on.", run.answer)
        assertTrue(toolResults(llm.prompts[1]).single().isError)
    }

    @Test
    fun `everything the loop added is returned for the caller to store`() = runTest {
        val llm = LlmStub { call, _ ->
            if (call == 1) assistantCall("c1", "echo", """{"text":"x"}""") else assistant("Done.")
        }
        val loop = AgentLoop(llm, maxSteps = 5)

        val run = loop.run(conversation("go"), ToolBox(listOf(EchoTool())))

        // assistant(call) -> user(result) -> assistant(answer)
        assertEquals(3, run.appended.size)
        assertTrue(run.appended[0] is Message.Assistant)
        assertTrue(run.appended[1] is Message.User)
        assertTrue(run.appended[2] is Message.Assistant)
    }

    // --- helpers ---------------------------------------------------------------

    private fun conversation(userText: String) = listOf(
        Message.System("You are a test fixture.", RequestMetaInfo.Empty),
        Message.User(userText, RequestMetaInfo.Empty),
    )

    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    private fun assistantCall(id: String, tool: String, args: String) =
        Message.Assistant(listOf(MessagePart.Tool.Call(id, tool, args)), ResponseMetaInfo.Empty)

    private fun toolResults(messages: List<Message>) = messages
        .filterIsInstance<Message.User>()
        .flatMap { it.parts }
        .filterIsInstance<MessagePart.Tool.Result>()

    /** A model whose reply is decided by the test, given the call number. */
    private class LlmStub(
        private val reply: (call: Int, messages: List<Message>) -> Message.Assistant,
    ) : Llm {
        var calls = 0
            private set

        /** The conversation as it looked at each call, for asserting on what the model saw. */
        val prompts = mutableListOf<List<Message>>()

        override suspend fun complete(
            messages: List<Message>,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            prompts += messages.toList()
            return reply(++calls, messages)
        }
    }

    private class EchoTool : AgentTool {
        val invocations = mutableListOf<JsonObject>()

        override val descriptor = ToolDescriptor(
            name = "echo",
            description = "Echoes the given text back.",
            requiredParameters = listOf(
                ToolParameterDescriptor("text", "Text to echo.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        )

        override suspend fun execute(args: JsonObject): String {
            invocations += args
            return "echo: ${args.requiredString("text")}"
        }
    }

    private class ExplodingTool : AgentTool {
        override val descriptor = ToolDescriptor(
            name = "boom",
            description = "Always fails.",
            requiredParameters = emptyList(),
            optionalParameters = emptyList(),
        )

        override suspend fun execute(args: JsonObject): String = throw IllegalStateException("boom")
    }
}
