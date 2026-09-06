package harness

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * One capability the model may call.
 *
 * Deliberately tiny: a description the model reads, and a function that runs.
 * Everything the harness needs to dispatch a call lives here, which is why the
 * loop in [AgentLoop] knows nothing about any concrete tool.
 *
 * [ToolDescriptor] is Koog's type only because it is what the LLM clients
 * serialise into the provider's tool-schema format. Nothing else is borrowed.
 */
interface AgentTool {
    val descriptor: ToolDescriptor

    /**
     * Runs the tool and returns text the model will read.
     *
     * Implementations must not throw for an ordinary bad-input case — they throw
     * [ToolArgumentException], which the harness hands back to the model as a
     * retryable error instead of aborting the run.
     */
    suspend fun execute(args: JsonObject): String
}

/** Raised when the model calls a tool with arguments that make no sense. */
class ToolArgumentException(message: String) : RuntimeException(message)

/**
 * The tools available for one run, indexed by name.
 *
 * Names must be unique: the model addresses a tool by name and nothing else, so a
 * duplicate would make dispatch ambiguous. That is a wiring bug, caught at startup.
 */
class ToolBox(tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.descriptor.name }

    init {
        require(byName.size == tools.size) {
            val duplicates = tools.map { it.descriptor.name }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
            "Duplicate tool name(s): ${duplicates.joinToString()}"
        }
    }

    val descriptors: List<ToolDescriptor> = tools.map { it.descriptor }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String): AgentTool? = byName[name]
}

/**
 * Reads a required string argument.
 *
 * Small models routinely send a number or a boolean where a string is expected, so
 * any primitive is accepted and rendered as text rather than rejected.
 */
fun JsonObject.requiredString(name: String): String {
    val value = this[name] ?: throw ToolArgumentException("Missing required argument '$name'.")
    val primitive = value as? JsonPrimitive
        ?: throw ToolArgumentException("Argument '$name' must be a string, got ${value::class.simpleName}.")
    return primitive.content.takeIf { it.isNotBlank() }
        ?: throw ToolArgumentException("Argument '$name' must not be empty.")
}

/** Reads an optional string argument, falling back to [default] when absent or blank. */
fun JsonObject.optionalString(name: String, default: String): String =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: default
