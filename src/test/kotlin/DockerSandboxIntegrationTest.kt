import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import sandbox.DockerSandbox
import tools.ExecTool
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the real exec path: a real container, real `docker exec`, real output.
 *
 * The unit tests prove the tool renders results correctly against a fake sandbox;
 * this proves the sandbox itself is built the way it was meant to be — that the
 * mounts are what they should be, and that the isolation is not merely intended.
 *
 * Skipped when Docker is not running, so the suite still passes on a machine
 * without it. It uses its own container name so it never disturbs the bot's.
 */
class DockerSandboxIntegrationTest {

    private val container = "tg-agent-sandbox-test"

    private val sandbox = DockerSandbox(
        image = "tg-agent-sandbox:1",
        container = container,
        // The project's own .git, exactly as the bot mounts it.
        gitDirectory = Path.of(".git").toAbsolutePath().takeIf { it.toFile().isDirectory },
        commandTimeout = 30.seconds,
        outputLimit = 4_000,
    )

    @AfterTest
    fun removeContainer() {
        runCatching {
            ProcessBuilder("docker", "rm", "-f", container)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    private fun requireDocker() {
        val running = runCatching {
            ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

        assumeTrue(running, "Docker is not running — skipping")
    }

    @Test
    fun `runs a command inside the container`() = runBlocking {
        requireDocker()

        val result = sandbox.exec("cat /etc/os-release | head -1")

        assertEquals(0, result.exitCode)
        // Alpine, not macOS: the command really did leave this machine's userland.
        assertContains(result.output, "Alpine")
    }

    @Test
    fun `the tools the skills rely on are installed`() = runBlocking {
        requireDocker()

        val result = sandbox.exec("command -v bash curl jq git | wc -l")

        assertEquals("4", result.output.trim())
    }

    @Test
    fun `the working tree is not reachable, so secrets stay out`() = runBlocking {
        requireDocker()

        // Only .git is mounted. This is the check that matters: .env lives next to it
        // on the host, and must not be readable from inside.
        val result = sandbox.exec("cat /project/.env 2>&1")

        assertTrue(result.exitCode != 0, "reading .env from the sandbox must fail")
        assertContains(result.output, "No such file")
    }

    @Test
    fun `the repository is mounted read-only`() = runBlocking {
        requireDocker()

        val result = sandbox.exec("touch /project/.git/tampered 2>&1")

        assertTrue(result.exitCode != 0)
        assertContains(result.output, "Read-only")
    }

    @Test
    fun `a runaway command is killed rather than tying up the container`() = runBlocking {
        requireDocker()

        val quick = DockerSandbox(
            image = "tg-agent-sandbox:1",
            container = container,
            gitDirectory = null,
            commandTimeout = 2.seconds,
            outputLimit = 4_000,
        )

        val result = quick.exec("sleep 60")

        assertTrue(result.timedOut, "expected the command to be killed")
    }

    @Test
    fun `the exec tool reports container output the way the model reads it`() = runBlocking {
        requireDocker()

        val tool = ExecTool(sandbox, commandTimeout = 30.seconds, outputLimit = 4_000)

        val rendered = tool.execute(JsonObject(mapOf("command" to JsonPrimitive("echo from-the-container"))))

        assertContains(rendered, "exit code: 0")
        assertContains(rendered, "from-the-container")
    }
}
