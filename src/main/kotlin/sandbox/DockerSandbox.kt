package sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Runs the agent's commands inside a Docker container.
 *
 * The container is the security boundary. The bot itself stays on the host, which
 * matters for two reasons: a command cannot read the bot's `.env` or kill its own
 * process, and the model provider stays reachable without any container networking.
 *
 * What the container gets:
 * - a fixed memory, CPU and process budget, so a fork bomb or a runaway download
 *   inconveniences nothing outside it;
 * - one writable directory, `/work`, which starts empty on every restart;
 * - the project's `.git` mounted **read-only** at `/project/.git`, so the agent can
 *   report on commit history without being able to read the working tree — where
 *   `.env` lives — or rewrite anything.
 *
 * Networking is left on, because reaching a REST API with `curl` is the whole point
 * of the exercise. That is also the sharpest edge: anything fetched from the network
 * arrives in the model's context, so a page can try to talk the agent into running
 * something. The skills tell the model to treat fetched content as data.
 */
class DockerSandbox(
    private val image: String,
    private val container: String,
    private val gitDirectory: Path?,
    private val commandTimeout: Duration,
    private val outputLimit: Int,
) : Sandbox {

    private val startup = Mutex()

    @Volatile
    private var ready = false

    override val description: String
        get() = "Docker container '$container' (image $image)"

    override suspend fun exec(command: String): ExecResult {
        ensureRunning()
        // `bash -lc` so the model can use pipes, redirection and && the way it expects.
        return runProcess(
            listOf("docker", "exec", container, "bash", "-lc", command),
            timeout = commandTimeout,
            outputLimit = outputLimit,
        )
    }

    override suspend fun shutdown() {
        if (!ready) return
        ready = false
        // Stop, don't remove: the next start reuses it, and a stopped container is
        // easy to inspect when something went wrong.
        runCatching { docker(listOf("stop", container), 30.seconds) }
            .onFailure { logger.warn { "Could not stop the sandbox container" } }
    }

    /**
     * Makes sure the image exists and the container is up, at most once at a time.
     *
     * Called lazily rather than at startup so a bot on a machine where Docker is not
     * running still serves every request that does not need a shell.
     */
    private suspend fun ensureRunning() {
        if (ready) return
        startup.withLock {
            if (ready) return
            requireDaemon()
            ensureImage()
            ensureContainer()
            ready = true
        }
    }

    private suspend fun requireDaemon() {
        val result = runCatching { docker(listOf("info", "--format", "{{.ServerVersion}}"), 20.seconds) }
            .getOrElse { throw SandboxException("The docker command is not available on this machine.", it) }

        if (result.exitCode != 0) {
            throw SandboxException(
                "Docker is installed but its daemon is not running. Start Docker Desktop and try again.",
            )
        }
        logger.debug { "Docker daemon ${result.output.trim()} is up" }
    }

    private suspend fun ensureImage() {
        if (docker(listOf("image", "inspect", image), 30.seconds).exitCode == 0) return

        val dockerfile = Path.of("sandbox")
        if (!Files.isDirectory(dockerfile)) {
            throw SandboxException("Sandbox image '$image' is missing and sandbox/Dockerfile was not found.")
        }

        logger.info { "Building the sandbox image '$image'; this happens once and takes a minute" }
        val build = docker(listOf("build", "-t", image, dockerfile.toString()), IMAGE_BUILD_TIMEOUT)
        if (build.exitCode != 0) {
            throw SandboxException("Could not build the sandbox image:\n${build.output.takeLast(500)}")
        }
    }

    private suspend fun ensureContainer() {
        val state = docker(listOf("inspect", "-f", "{{.State.Running}}", container), 20.seconds)
        when {
            state.exitCode == 0 && state.output.trim() == "true" -> {
                logger.info { "Reusing the running sandbox container '$container'" }
                return
            }

            state.exitCode == 0 -> {
                logger.info { "Starting the existing sandbox container '$container'" }
                // The mounts of an existing container are fixed at creation time; if
                // the configuration changed, recreate it rather than start it stale.
                docker(listOf("rm", "-f", container), 30.seconds)
            }
        }
        create()
    }

    private suspend fun create() {
        val command = buildList {
            addAll(listOf("run", "--detach", "--name", container))
            // Resource caps: a runaway command must not take the machine with it.
            addAll(listOf("--memory", "512m", "--cpus", "1", "--pids-limit", "256"))
            gitDirectory?.let { addAll(listOf("--volume", "$it:/project/.git:ro")) }
            add(image)
        }

        val run = docker(command, 60.seconds)
        if (run.exitCode != 0) {
            throw SandboxException("Could not start the sandbox container:\n${run.output.takeLast(500)}")
        }
        logger.info {
            "Sandbox container '$container' started" +
                (gitDirectory?.let { "; $it mounted read-only at /project/.git" } ?: "")
        }
    }

    private suspend fun docker(arguments: List<String>, timeout: Duration): ExecResult =
        runProcess(listOf("docker") + arguments, timeout, DOCKER_OUTPUT_LIMIT)

    private companion object {
        val IMAGE_BUILD_TIMEOUT = 10.minutes

        /** Docker's own chatter is for the log, not the model; a few KB is plenty. */
        const val DOCKER_OUTPUT_LIMIT = 8_000
    }
}

/**
 * Runs commands directly on the host, with no isolation whatsoever.
 *
 * Only reachable by setting `EXEC_SANDBOX=host` by hand. It exists because a machine
 * without Docker should still be able to demonstrate the agent, and because being
 * explicit about "no sandbox" is safer than silently degrading to it.
 */
class HostSandbox(
    private val workingDirectory: Path,
    private val commandTimeout: Duration,
    private val outputLimit: Int,
) : Sandbox {

    init {
        Files.createDirectories(workingDirectory)
        logger.warn {
            "EXEC_SANDBOX=host: shell commands run directly on this machine with no isolation"
        }
    }

    override val description: String
        get() = "this machine, in $workingDirectory (no isolation)"

    override suspend fun exec(command: String): ExecResult = runProcess(
        listOf("bash", "-lc", "cd ${quote(workingDirectory.toString())} && $command"),
        timeout = commandTimeout,
        outputLimit = outputLimit,
    )

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
