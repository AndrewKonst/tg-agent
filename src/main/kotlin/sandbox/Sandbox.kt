package sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Where the agent's shell commands run.
 *
 * The interface exists so the dangerous part — actually executing what a language
 * model asked for — has exactly one implementation-independent shape, and so tests
 * can exercise the tool without running anything.
 */
interface Sandbox {
    /** Human-readable description of where commands land, for logs and `/help`. */
    val description: String

    /** Runs [command] through a shell and returns everything it printed. */
    suspend fun exec(command: String): ExecResult

    /** Releases whatever the sandbox holds. Safe to call more than once. */
    suspend fun shutdown() {}
}

/**
 * The outcome of one command.
 *
 * [output] merges stdout and stderr: the model needs the error text far more often
 * than it needs to know which stream it arrived on.
 */
data class ExecResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
)

/** Raised when the sandbox itself is unusable, as opposed to a command that failed. */
class SandboxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Runs a process and captures what it prints, under a hard timeout.
 *
 * Three things here are not optional:
 *
 * - stdin is closed immediately, so a command that decides to prompt for input dies
 *   instead of hanging until the timeout;
 * - output is read on a separate coroutine while the process runs, because a process
 *   that fills the pipe buffer blocks forever if nobody is draining it;
 * - the secrets this bot holds are stripped from the child's environment, so
 *   `echo $TELEGRAM_BOT_TOKEN` cannot exfiltrate the token into a chat.
 */
internal suspend fun runProcess(
    command: List<String>,
    timeout: Duration,
    outputLimit: Int,
): ExecResult = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    builder.environment().scrubSecrets()

    val process = builder.start()
    process.outputStream.close()

    val finished = coroutineScope {
        val capture = async { readCapped(process.inputStream, outputLimit) }
        withTimeoutOrNull(timeout) {
            val captured = capture.await()
            captured to runInterruptible { process.waitFor() }
        }
    }

    if (finished != null) {
        val (captured, exitCode) = finished
        return@withContext ExecResult(
            exitCode = exitCode,
            output = captured.text,
            truncated = captured.truncated,
        )
    }

    // The command outstayed its welcome. Killing only the shell would leave its
    // children running as orphans — `bash -lc "sleep 30"` outlives its own bash — so
    // the whole tree goes. Descendants are collected before the kill, because a dead
    // parent no longer reports them.
    logger.warn { "Command timed out after $timeout; killing it and its children" }
    val descendants = process.descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
    runInterruptible { process.waitFor() }
    ExecResult(
        exitCode = TIMEOUT_EXIT_CODE,
        output = "",
        timedOut = true,
    )
}

private class Captured(val text: String, val truncated: Boolean)

/**
 * Reads [stream] to the end, keeping at most [limit] characters.
 *
 * Reading continues past the limit rather than stopping: abandoning the stream would
 * block a chatty process on a full pipe, and it would never reach its exit code.
 */
private fun readCapped(stream: InputStream, limit: Int): Captured {
    val builder = StringBuilder()
    var truncated = false
    stream.bufferedReader().use { reader ->
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            val room = limit - builder.length
            when {
                room <= 0 -> truncated = true
                read > room -> {
                    builder.appendRange(buffer, 0, room)
                    truncated = true
                }
                else -> builder.appendRange(buffer, 0, read)
            }
        }
    }
    return Captured(builder.toString(), truncated)
}

/**
 * Removes anything that looks like a credential from a child process's environment.
 *
 * The bot reads its own secrets from `.env` rather than the environment, so this is
 * belt and braces — but the cost is one pass over a map, and the failure it prevents
 * is a token pasted into a public chat.
 */
private fun MutableMap<String, String>.scrubSecrets() {
    keys.filter { key -> SECRET_PATTERN.containsMatchIn(key) }.forEach { remove(it) }
}

private val SECRET_PATTERN = Regex("TOKEN|KEY|SECRET|PASSWORD|CREDENTIAL", RegexOption.IGNORE_CASE)

/** Exit code reported for a command the sandbox had to kill. Matches the shell's 128+SIGKILL. */
internal const val TIMEOUT_EXIT_CODE = 137
