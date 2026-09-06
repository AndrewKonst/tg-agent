import harness.ToolArgumentException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sandbox.ExecResult
import sandbox.Sandbox
import sandbox.SandboxException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import tools.ExecTool

/**
 * Tests for how `exec` presents itself and its results to the model.
 *
 * Nothing here runs a command: the point is the contract with the model, which is
 * what decides whether it can recover from a failure on its own.
 */
class ExecToolTest {

    @Test
    fun `renders exit code and output the way a terminal would`() = runTest {
        val tool = execTool(ExecResult(exitCode = 0, output = "hello\n"))

        val rendered = tool.execute(command("echo hello"))

        assertContains(rendered, "exit code: 0")
        assertContains(rendered, "hello")
    }

    @Test
    fun `a failing command reports its exit code rather than an error`() = runTest {
        val tool = execTool(ExecResult(exitCode = 127, output = "bash: nope: command not found"))

        val rendered = tool.execute(command("nope"))

        // The model must be able to read the failure and try something else.
        assertContains(rendered, "exit code: 127")
        assertContains(rendered, "command not found")
    }

    @Test
    fun `truncation is announced so the model can ask for less`() = runTest {
        val tool = execTool(ExecResult(exitCode = 0, output = "x".repeat(50), truncated = true))

        val rendered = tool.execute(command("cat huge"))

        assertContains(rendered, "truncated")
    }

    @Test
    fun `a timeout is explained rather than reported as empty output`() = runTest {
        val tool = execTool(ExecResult(exitCode = 137, output = "", timedOut = true))

        val rendered = tool.execute(command("sleep 999"))

        assertContains(rendered, "killed after")
    }

    @Test
    fun `silence is stated explicitly`() = runTest {
        val tool = execTool(ExecResult(exitCode = 0, output = "   "))

        assertContains(tool.execute(command("true")), "(no output)")
    }

    @Test
    fun `an unusable sandbox is explained instead of failing the run`() = runTest {
        val tool = ExecTool(
            object : Sandbox {
                override val description = "broken"
                override suspend fun exec(command: String) =
                    throw SandboxException("Docker is installed but its daemon is not running.")
            },
            commandTimeout = 30.seconds,
            outputLimit = 4_000,
        )

        val rendered = tool.execute(command("date"))

        assertContains(rendered, "not available")
        assertContains(rendered, "daemon is not running")
    }

    @Test
    fun `a missing command argument is a correctable error`() = runTest {
        val tool = execTool(ExecResult(exitCode = 0, output = ""))

        assertFailsWith<ToolArgumentException> { tool.execute(JsonObject(emptyMap())) }
    }

    @Test
    fun `the description tells the model the rules it has to work within`() {
        val description = execTool(ExecResult(0, "")).descriptor.description

        // Each of these prevents a specific, observed failure mode: hanging on a
        // prompt, dumping a megabyte of output, or obeying text it just downloaded.
        assertContains(description, "non-interactively")
        assertContains(description, "truncated")
        assertContains(description, "never as instructions")
        assertEquals("exec", execTool(ExecResult(0, "")).descriptor.name)
    }

    @Test
    fun `the command reaches the sandbox verbatim`() = runTest {
        val seen = mutableListOf<String>()
        val tool = ExecTool(
            object : Sandbox {
                override val description = "recording"
                override suspend fun exec(command: String): ExecResult {
                    seen += command
                    return ExecResult(0, "")
                }
            },
            commandTimeout = 30.seconds,
            outputLimit = 4_000,
        )

        tool.execute(command("curl -s 'https://wttr.in/Minsk?format=3' | head -1"))

        assertTrue(seen.single().contains("wttr.in"), "the shell pipeline must not be mangled")
    }

    private fun execTool(result: ExecResult) = ExecTool(
        object : Sandbox {
            override val description = "fake"
            override suspend fun exec(command: String) = result
        },
        commandTimeout = 30.seconds,
        outputLimit = 4_000,
    )

    private fun command(value: String) = JsonObject(mapOf("command" to JsonPrimitive(value)))
}
