package harness

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/** Why a run stopped. Everything but [COMPLETED] means the model did not finish on its own. */
enum class StopReason {
    /** The model produced an answer instead of another tool call. */
    COMPLETED,

    /** The step budget ran out first. */
    MAX_STEPS,

    /** The model kept asking for the same tool call and was not making progress. */
    REPEATED_TOOL_CALL,
}

/**
 * The outcome of one agentic run.
 *
 * [appended] is every message the loop added — assistant replies and tool results
 * alike — so a caller keeping conversation history can store the run verbatim.
 */
data class AgentRun(
    val answer: String,
    val appended: List<Message>,
    val steps: Int,
    val stopReason: StopReason,
)

/**
 * The harness: call the model, run whatever tools it asks for, feed the results
 * back, repeat until it answers.
 *
 * ```
 *   messages ──> LLM ──> tool calls? ──no──> answer
 *                 ^                 │
 *                 └── tool results ─┘yes
 * ```
 *
 * Two independent guards keep a run finite. [maxSteps] bounds how many times the
 * model may be asked — the "max 5-10 steps" limit. The duplicate-call detector
 * catches the other failure mode, where a model burns every step re-issuing one
 * identical call; small local models do this often enough to be worth handling
 * explicitly rather than waiting for the step budget to expire.
 *
 * The loop holds no state between runs: everything lives on the stack of [run], so
 * one instance serves all chats concurrently.
 */
class AgentLoop(
    private val llm: Llm,
    private val maxSteps: Int,
    private val clock: KoogClock = KoogClock.System,
) {
    init {
        require(maxSteps >= 1) { "maxSteps must be at least 1" }
    }

    /**
     * Runs the loop over [messages], which must already contain the system prompt
     * and the user's request.
     *
     * [tools] is a parameter rather than a field because what the agent may do
     * depends on who is asking: a shell is offered to the bot's owner and to nobody
     * else, and the cleanest way to deny a capability is to never mention it.
     */
    suspend fun run(messages: List<Message>, tools: ToolBox): AgentRun {
        val transcript = messages.toMutableList()
        val appended = mutableListOf<Message>()
        val callCounts = mutableMapOf<String, Int>()

        for (step in 1..maxSteps) {
            val assistant = llm.complete(transcript, tools.descriptors)
            transcript += assistant
            appended += assistant

            val calls = assistant.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) {
                logger.info { "Run finished in $step step(s)" }
                return AgentRun(
                    answer = assistant.visibleText().ifEmpty { EMPTY_ANSWER },
                    appended = appended,
                    steps = step,
                    stopReason = StopReason.COMPLETED,
                )
            }

            logger.info {
                "Step $step/$maxSteps: model called ${calls.joinToString { it.tool }}"
            }

            val results = mutableListOf<MessagePart.Tool.Result>()
            for (call in calls) {
                val repeats = callCounts.merge(signature(call), 1, Int::plus) ?: 1
                if (repeats > GIVE_UP_AFTER_REPEATS) {
                    logger.warn { "Aborting run: '${call.tool}' called $repeats times with identical arguments" }
                    return AgentRun(
                        answer = STUCK_ANSWER,
                        appended = appended,
                        steps = step,
                        stopReason = StopReason.REPEATED_TOOL_CALL,
                    )
                }
                results += if (repeats > 1) duplicateNudge(call) else invoke(call, tools)
            }

            val toolMessage = Message.User(results, RequestMetaInfo.create(clock))
            transcript += toolMessage
            appended += toolMessage
        }

        logger.warn { "Run hit the step limit of $maxSteps" }
        return AgentRun(
            answer = exhaustedAnswer(appended),
            appended = appended,
            steps = maxSteps,
            stopReason = StopReason.MAX_STEPS,
        )
    }

    /**
     * Runs one tool call.
     *
     * Nothing here throws for a call the model got wrong: an unknown name, malformed
     * arguments, or a tool that failed all come back as an error result the model can
     * read and correct on the next step. Only cancellation escapes, because that means
     * the run itself is over.
     */
    private suspend fun invoke(call: MessagePart.Tool.Call, tools: ToolBox): MessagePart.Tool.Result {
        val tool = tools[call.tool]
            ?: return error(call, "Unknown tool '${call.tool}'. Available tools: ${tools.names.joinToString()}.")

        val args = try {
            call.argsJson
        } catch (e: Throwable) {
            return error(call, "Arguments for '${call.tool}' are not valid JSON. Send a JSON object.")
        }

        return try {
            MessagePart.Tool.Result(id = call.id, tool = call.tool, output = tool.execute(args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            error(call, e.message ?: "Invalid arguments.")
        } catch (e: Throwable) {
            logger.error(e) { "Tool '${call.tool}' failed" }
            error(call, "Tool '${call.tool}' failed: ${e::class.simpleName}.")
        }
    }

    /**
     * Answers a repeated call without running it again.
     *
     * Re-running would return the same output and invite the same loop, so the model
     * is told plainly that the answer is already above.
     */
    private fun duplicateNudge(call: MessagePart.Tool.Call): MessagePart.Tool.Result = error(
        call,
        "You already called '${call.tool}' with these exact arguments and its result is " +
            "earlier in this conversation. Use that result, or answer the user directly.",
    )

    private fun error(call: MessagePart.Tool.Call, text: String) =
        MessagePart.Tool.Result(id = call.id, tool = call.tool, output = text, isError = true)

    /** Identity of a call for duplicate detection: same tool, same arguments. */
    private fun signature(call: MessagePart.Tool.Call): String = "${call.tool}(${call.args})"

    /**
     * What to say when the step budget runs out.
     *
     * Any text the model already produced is worth more to the user than a bare
     * failure notice, so it is kept and labelled.
     */
    private fun exhaustedAnswer(appended: List<Message>): String {
        val lastText = appended.filterIsInstance<Message.Assistant>()
            .lastOrNull { it.visibleText().isNotEmpty() }
            ?.visibleText()
        return if (lastText.isNullOrEmpty()) {
            EXHAUSTED_ANSWER
        } else {
            "$EXHAUSTED_ANSWER\n\nHere is how far I got:\n$lastText"
        }
    }

    private companion object {
        /** The second identical call is nudged; the third ends the run. */
        const val GIVE_UP_AFTER_REPEATS = 2

        const val EMPTY_ANSWER =
            "I finished thinking but produced no answer. Please rephrase the question."

        const val EXHAUSTED_ANSWER =
            "I ran out of steps before finishing this task. Try asking for something narrower."

        const val STUCK_ANSWER =
            "I got stuck repeating the same action, so I stopped. Try rephrasing the request."
    }
}
