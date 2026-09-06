package harness

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * One call to the language model.
 *
 * An interface rather than a class so the agentic loop can be tested against a
 * scripted model — no network, no provider, no timing.
 */
interface Llm {
    /**
     * Sends the whole conversation and returns the model's next message.
     *
     * The reply either carries text (the final answer) or one or more
     * [MessagePart.Tool.Call] parts. Deciding which is [AgentLoop]'s job.
     */
    suspend fun complete(messages: List<Message>, tools: List<ToolDescriptor>): Message.Assistant
}

/**
 * [Llm] backed by Koog's [PromptExecutor].
 *
 * This is the entire surface we take from the framework: serialise a conversation
 * and tool schemas into the provider's wire format, and parse the reply back. The
 * agentic loop, the step limit, tool dispatch and retries are ours.
 *
 * [callTimeout] bounds a single request. The whole multi-step run is bounded
 * separately, by the caller — a run of eight steps must not be able to outlive its
 * own deadline eight times over.
 */
class KoogLlm(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val callTimeout: Duration,
    private val maxAttempts: Int,
) : Llm {

    override suspend fun complete(
        messages: List<Message>,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = withRetry(maxAttempts) { attempt ->
        logger.debug { "LLM call (attempt $attempt): ${messages.size} messages, ${tools.size} tools" }

        val reply = withTimeout(callTimeout) {
            executor.execute(Prompt(messages, PROMPT_ID), model, tools)
        }

        reply.metaInfo.totalTokensCount?.let { total ->
            logger.debug { "LLM replied: $total tokens, finishReason=${reply.finishReason ?: "none"}" }
        }
        reply
    }

    private companion object {
        /** Koog wants an id for the prompt; it is only used for its own bookkeeping. */
        const val PROMPT_ID = "agent"
    }
}

/**
 * The part of an assistant message meant for the user.
 *
 * Koog models chain-of-thought as a separate [MessagePart.Reasoning] part, so
 * [Message.Assistant.textContent] normally excludes it. Local models served through
 * Ollama — qwen3 in particular — sometimes emit the `<think>` block inline in the
 * text instead, which would otherwise be relayed straight into the chat. Stripping
 * it here costs nothing when the part-based path already did the right thing.
 */
fun Message.Assistant.visibleText(): String = stripInlineReasoning(textContent()).trim()

internal fun stripInlineReasoning(text: String): String {
    if (!text.contains(THINK_OPEN, ignoreCase = true)) {
        // A stray closing tag means the opening one was consumed upstream.
        val closing = text.indexOf(THINK_CLOSE, ignoreCase = true)
        return if (closing >= 0) text.substring(closing + THINK_CLOSE.length) else text
    }
    val withoutBlocks = THINK_BLOCK.replace(text, "")
    // An unterminated block means the model was cut off mid-thought; nothing after it is usable.
    return THINK_UNTERMINATED.replace(withoutBlocks, "")
}

private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"
private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val THINK_UNTERMINATED = Regex("<think>.*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
