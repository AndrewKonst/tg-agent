package skills

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/** One skill file: a short instruction sheet the agent can read when it needs it. */
data class Skill(
    val name: String,
    val description: String,
    val path: Path,
)

/**
 * The agent's library of written instructions.
 *
 * A skill is a Markdown file: how to drive a particular CLI or API, or the steps of
 * a routine the agent should follow. The mechanism that matters is **progressive
 * disclosure** — the system prompt carries only a one-line description of each
 * skill, and the full text is fetched with the `skill` tool once the model decides a
 * skill applies.
 *
 * That indirection is the whole point. Pasting every skill into the system prompt
 * would work with two files and collapse with twenty: a small local model drowns in
 * a long prompt, and most of it is irrelevant to any single question. This way the
 * catalogue costs a line per skill and the detail is paid for only when used.
 *
 * The catalogue is read once at startup, but a skill's body is re-read from disk on
 * every call — so editing a skill takes effect immediately, while adding one needs a
 * restart.
 */
class SkillCatalog(
    private val directory: Path,
    private val skills: List<Skill>,
) {

    val names: List<String> = skills.map { it.name }

    fun isEmpty(): Boolean = skills.isEmpty()

    /**
     * The catalogue as it appears in the system prompt.
     *
     * Deliberately terse: name, one line, and a nudge to read the skill before acting.
     */
    fun promptSection(): String = buildString {
        appendLine("You have skills — short written instructions for specific tasks:")
        skills.forEach { appendLine("- ${it.name}: ${it.description}") }
        append(
            "When a request matches one of these, call the `skill` tool with its name and " +
                "follow what it says before doing anything else.",
        )
    }

    /**
     * Returns the full text of [name], or null if there is no such skill.
     *
     * Lookup goes through the catalogue rather than the filesystem, so a name the
     * model invented — or a path it tried to escape with — cannot reach a file.
     */
    fun read(name: String): String? {
        val skill = skills.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return null
        return runCatching { skill.path.readText() }
            .onFailure { logger.error(it) { "Could not read skill '${skill.name}' at ${skill.path}" } }
            .getOrNull()
    }

    companion object {
        /**
         * Reads every `.md` file in [directory].
         *
         * A malformed file is skipped with a warning rather than failing startup: one
         * bad skill should not take the bot down, and the warning names the file.
         */
        fun load(directory: Path): SkillCatalog {
            if (!Files.isDirectory(directory)) {
                logger.info { "No skills directory at ${directory.toAbsolutePath()}" }
                return SkillCatalog(directory, emptyList())
            }

            val found = Files.list(directory).use { stream ->
                stream.filter { it.extension.equals("md", ignoreCase = true) }
                    .sorted()
                    .toList()
            }.mapNotNull { parse(it) }

            val unique = found.distinctBy { it.name.lowercase() }
            if (unique.size != found.size) {
                logger.warn { "Skills with duplicate names were ignored in ${directory.toAbsolutePath()}" }
            }

            logger.info {
                if (unique.isEmpty()) {
                    "No skills found in ${directory.toAbsolutePath()}"
                } else {
                    "Skills loaded: ${unique.joinToString { it.name }}"
                }
            }
            return SkillCatalog(directory, unique)
        }

        /**
         * Parses the YAML-ish front matter every skill file opens with.
         *
         * Only `key: value` pairs are supported, which is all the format needs — a
         * real YAML parser would be a dependency bought for nothing.
         */
        private fun parse(path: Path): Skill? {
            val text = runCatching { path.readText() }.getOrElse {
                logger.warn { "Could not read $path" }
                return null
            }

            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != DELIMITER) {
                logger.warn { "Skipping $path: it does not start with a '---' front matter block" }
                return null
            }

            val end = lines.drop(1).indexOfFirst { it.trim() == DELIMITER }
            if (end < 0) {
                logger.warn { "Skipping $path: the front matter block is never closed" }
                return null
            }

            val fields = lines.subList(1, end + 1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim().removeSurrounding("\"")
                }
                .toMap()

            val description = fields["description"]?.takeIf { it.isNotBlank() }
            if (description == null) {
                // Without a description the model has no basis for choosing the skill,
                // so the file would be dead weight in the catalogue.
                logger.warn { "Skipping $path: front matter has no 'description'" }
                return null
            }

            return Skill(
                name = fields["name"]?.takeIf { it.isNotBlank() } ?: path.nameWithoutExtension,
                description = description,
                path = path,
            )
        }

        private const val DELIMITER = "---"
    }
}
