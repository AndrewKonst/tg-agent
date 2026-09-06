import kotlinx.coroutines.runBlocking
import sandbox.HostSandbox
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the process plumbing for real, by running actual commands.
 *
 * This is the code that hands a language model a shell, so the guards around it —
 * the timeout, the output cap, the closed stdin, the scrubbed environment — are
 * worth exercising against a real process rather than a mock. [HostSandbox] is used
 * because it needs nothing installed; the same runner backs the Docker sandbox.
 */
class SandboxTest {

    private fun sandbox(
        timeoutMs: Long = 10_000,
        outputLimit: Int = 4_000,
    ) = HostSandbox(
        workingDirectory = createTempDirectory("tg-bot-ai-sandbox"),
        commandTimeout = timeoutMs.milliseconds,
        outputLimit = outputLimit,
    )

    @Test
    fun `runs a command and returns its output`() = runBlocking {
        val result = sandbox().exec("echo hello from the sandbox")

        assertEquals(0, result.exitCode)
        assertContains(result.output, "hello from the sandbox")
        assertTrue(!result.truncated)
        assertTrue(!result.timedOut)
    }

    @Test
    fun `stderr comes back too, with the failing exit code`() = runBlocking {
        val result = sandbox().exec("echo to-stderr >&2; exit 3")

        assertEquals(3, result.exitCode)
        assertContains(result.output, "to-stderr")
    }

    @Test
    fun `shell features work, since that is the point of a shell`() = runBlocking {
        val result = sandbox().exec("printf 'b\\na\\nc\\n' | sort | tr '\\n' ' '")

        assertEquals(0, result.exitCode)
        assertContains(result.output, "a b c")
    }

    @Test
    fun `a command that runs too long is killed, and takes its children with it`() = runBlocking {
        val box = sandbox(timeoutMs = 500)

        // The sleep is a grandchild of the process we start, which is the case that
        // matters: killing only the shell would leave it running for another 30s.
        val result = box.exec("bash -c 'echo \$\$ > child.pid; sleep 30'")

        assertTrue(result.timedOut, "expected the command to be killed")
        val alive = box.exec("if kill -0 \"\$(cat child.pid)\" 2>/dev/null; then echo alive; else echo gone; fi")
        assertContains(alive.output, "gone", message = "the child process outlived its shell")
    }

    @Test
    fun `output is capped instead of flooding the model's context`() = runBlocking {
        val result = sandbox(outputLimit = 100).exec("yes abcdefghij | head -n 10000")

        assertTrue(result.truncated, "expected truncation")
        assertEquals(100, result.output.length)
        // The producer still finished rather than blocking on a full pipe.
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `a command asking for input dies instead of hanging`() = runBlocking {
        val result = sandbox(timeoutMs = 5_000).exec("read -r answer; echo \"got: \$answer\"")

        assertTrue(!result.timedOut, "closed stdin should end the read immediately")
    }

    @Test
    fun `secrets are stripped from the environment of a command`() = runBlocking {
        // Whatever this process was started with, a child must not see a token.
        val result = sandbox().exec("env | grep -ciE 'token|secret|password' || true")

        assertEquals("0", result.output.trim(), "no credential-shaped variable may reach a command")
    }

    @Test
    fun `commands run in the configured working directory`() = runBlocking {
        val sandbox = sandbox()

        sandbox.exec("echo marker > marker.txt")
        val result = sandbox.exec("cat marker.txt")

        assertContains(result.output, "marker")
    }

    @Test
    fun `a long timeout does not delay a fast command`() = runBlocking {
        val started = System.currentTimeMillis()

        sandbox(timeoutMs = 60.seconds.inWholeMilliseconds).exec("true")

        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 10_000, "a quick command should return at once, took ${elapsed}ms")
    }
}
