package tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import harness.AgentTool
import harness.requiredString
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import skills.SkillCatalog

private val logger = KotlinLogging.logger {}

/**
 * Fetches the full text of a skill the model has decided to use.
 *
 * The other half of progressive disclosure: [SkillCatalog] puts one line per skill
 * in the system prompt, and this tool pays for the detail only when it is needed.
 */
class SkillTool(private val catalog: SkillCatalog) : AgentTool {

    override val descriptor = ToolDescriptor(
        name = "skill",
        description = "Returns the full instructions for one of your skills. " +
            "Read the skill before starting a task it covers, then follow it.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "name",
                description = "Skill name, one of: ${catalog.names.joinToString()}",
                // An enum rather than a free string: a small model that cannot invent
                // a name cannot waste a step being told the name was wrong.
                type = ToolParameterType.Enum(catalog.names.toTypedArray()),
            ),
        ),
        optionalParameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.requiredString("name")
        logger.info { "skill: $name" }

        val body = catalog.read(name)
            ?: return "There is no skill called '$name'. Available skills: ${catalog.names.joinToString()}."

        return "Instructions for the '$name' skill follow. Do what they say.\n\n$body"
    }
}
