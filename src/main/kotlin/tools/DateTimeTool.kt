package tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import harness.AgentTool
import harness.optionalString
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A minimal, genuinely useful tool: language models have no clock, so without this
 * they guess at "today".
 *
 * It is also the reference example for extending the agent. A new tool is a class
 * implementing [AgentTool] plus one line in `AgentFactory.buildToolBox`; nothing in
 * the harness or the Telegram layer changes.
 */
class DateTimeTool : AgentTool {

    override val descriptor = ToolDescriptor(
        name = "current_datetime",
        description = "Returns the current date and time. Call this whenever the answer " +
            "depends on what the date or time is right now.",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "time_zone",
                description = "IANA time zone id, for example 'Europe/Minsk' or 'UTC'. Defaults to UTC.",
                type = ToolParameterType.String,
            ),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val requested = args.optionalString("time_zone", "UTC")
        val zone = runCatching { ZoneId.of(requested) }.getOrElse {
            // An unusable argument is information for the model, not a failure.
            return "Unknown time zone '$requested'. Use an IANA id such as 'UTC' or 'Europe/Minsk'."
        }
        return ZonedDateTime.now(zone).format(FORMAT)
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm z")
    }
}
