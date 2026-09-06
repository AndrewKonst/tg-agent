package conversation

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Trims a chat's history to fit a character budget without corrupting it.
 *
 * The naive approach — drop the oldest messages one by one — breaks agentic
 * conversations: a tool result whose call was dropped is an orphan, and providers
 * reject the request outright. So the unit of trimming is a **turn**: a user message
 * together with every assistant reply and tool result it produced. Turns are dropped
 * whole, oldest first, which makes it impossible to separate a call from its result.
 *
 * The budget is measured in characters rather than tokens on purpose: a real
 * tokenizer would have to match the model, and being roughly right here is enough,
 * since the point is only to keep a long chat from overflowing the context window.
 */
class HistoryWindow(private val maxChars: Int) {

    init {
        require(maxChars > 0) { "maxChars must be positive" }
    }

    /** Returns the newest whole turns of [history] that fit the budget. */
    fun fit(history: List<Message>): List<Message> {
        if (history.isEmpty()) return history

        val kept = ArrayDeque<List<Message>>()
        var size = 0

        for (turn in splitIntoTurns(history).asReversed()) {
            val turnSize = turn.sumOf { it.approximateSize() }
            if (size + turnSize > maxChars) break
            kept.addFirst(turn)
            size += turnSize
        }
        return kept.flatten()
    }

    /**
     * Groups messages into turns.
     *
     * A turn starts at a message the user actually typed. A [Message.User] carrying
     * only tool results is the loop feeding the model, not a new turn, so it stays
     * with the assistant message whose calls it answers.
     */
    private fun splitIntoTurns(history: List<Message>): List<List<Message>> {
        val turns = mutableListOf<MutableList<Message>>()
        for (message in history) {
            if (turns.isEmpty() || message.startsTurn()) turns.add(mutableListOf())
            turns.last() += message
        }
        return turns
    }

    private fun Message.startsTurn(): Boolean =
        this is Message.User && parts.any { it !is MessagePart.Tool.Result }
}

/** Rough size of a message, in characters of content the model will actually read. */
internal fun Message.approximateSize(): Int = when (this) {
    is Message.System -> parts.sumOf { it.text.length }
    is Message.User -> parts.sumOf { it.approximateSize() }
    is Message.Assistant -> parts.sumOf { it.approximateSize() }
}

private fun MessagePart.approximateSize(): Int = when (this) {
    is MessagePart.Text -> text.length
    is MessagePart.Tool.Call -> tool.length + args.length
    is MessagePart.Tool.Result -> tool.length + output.length
    is MessagePart.Reasoning -> content.sumOf { it.length }
    else -> 0
}

/**
 * Strips the model's chain-of-thought before the message is stored.
 *
 * Reasoning is what the model needed to reach *this* answer; replaying it on every
 * later turn spends context on nothing. Tool calls and text are kept untouched, so
 * the conversation the model sees stays coherent.
 */
internal fun Message.withoutReasoning(): Message = when {
    this !is Message.Assistant -> this
    parts.none { it is MessagePart.Reasoning } -> this
    else -> copy(parts = parts.filter { it !is MessagePart.Reasoning })
}
