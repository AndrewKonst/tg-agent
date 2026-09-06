import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import skills.SkillCatalog
import tools.SkillTool
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for progressive disclosure: the catalogue is cheap, the detail is on demand.
 */
class SkillCatalogTest {

    @Test
    fun `reads name and description from the front matter`() {
        val directory = skillsDir("greet" to skill("greet", "Say hello properly.", "Say hi."))

        val catalog = SkillCatalog.load(directory)

        assertEquals(listOf("greet"), catalog.names)
        assertContains(catalog.promptSection(), "greet: Say hello properly.")
    }

    @Test
    fun `the catalogue carries descriptions, never whole skill bodies`() {
        val body = "A very long body that must not reach the system prompt. ".repeat(20)
        val directory = skillsDir("big" to skill("big", "One line.", body))

        val section = SkillCatalog.load(directory).promptSection()

        assertTrue(
            !section.contains("must not reach the system prompt"),
            "the whole point is that bodies stay out of the prompt",
        )
        assertTrue(section.length < 300, "the catalogue should cost about a line per skill")
    }

    @Test
    fun `the name falls back to the file name`() {
        val directory = createTempDirectory("skills")
        directory.resolve("fallback.md").writeText("---\ndescription: No name given.\n---\nBody.")

        assertEquals(listOf("fallback"), SkillCatalog.load(directory).names)
    }

    @Test
    fun `a file without a description is skipped rather than offered blindly`() {
        val directory = createTempDirectory("skills")
        // Without a description the model has nothing to choose on.
        directory.resolve("mystery.md").writeText("---\nname: mystery\n---\nBody.")
        directory.resolve("fine.md").writeText(skill("fine", "Usable.", "Body."))

        assertEquals(listOf("fine"), SkillCatalog.load(directory).names)
    }

    @Test
    fun `a file with no front matter at all is skipped`() {
        val directory = createTempDirectory("skills")
        directory.resolve("notes.md").writeText("# Just some notes\n\nNothing structured here.")

        assertTrue(SkillCatalog.load(directory).isEmpty())
    }

    @Test
    fun `a missing directory is not an error`() {
        val catalog = SkillCatalog.load(Path.of("no/such/directory"))

        assertTrue(catalog.isEmpty())
        assertTrue(catalog.names.isEmpty())
    }

    @Test
    fun `reading a skill returns its whole body`() {
        val directory = skillsDir("greet" to skill("greet", "Say hello.", "Step one.\nStep two."))

        val body = SkillCatalog.load(directory).read("greet")

        assertContains(body!!, "Step one.")
        assertContains(body, "Step two.")
    }

    @Test
    fun `an edited skill takes effect without a restart`() {
        val directory = skillsDir("greet" to skill("greet", "Say hello.", "Old instructions."))
        val catalog = SkillCatalog.load(directory)

        directory.resolve("greet.md").writeText(skill("greet", "Say hello.", "New instructions."))

        assertContains(catalog.read("greet")!!, "New instructions.")
    }

    @Test
    fun `an unknown name yields nothing, and a path cannot be smuggled in`() {
        val catalog = SkillCatalog.load(skillsDir("greet" to skill("greet", "Say hello.", "Body.")))

        assertNull(catalog.read("nope"))
        // Lookup goes through the catalogue, so this never touches the filesystem.
        assertNull(catalog.read("../../../etc/passwd"))
    }

    @Test
    fun `the tool offers the skill names as a closed set`() {
        val catalog = SkillCatalog.load(
            skillsDir(
                "greet" to skill("greet", "Say hello.", "Body."),
                "weather" to skill("weather", "Fetch weather.", "Body."),
            ),
        )

        val parameter = SkillTool(catalog).descriptor.requiredParameters.single()

        // An enum rather than a free string: the model cannot invent a skill name.
        assertContains(parameter.type.toString(), "greet")
        assertContains(parameter.type.toString(), "weather")
    }

    @Test
    fun `the tool explains itself when the model asks for a skill that is not there`() = runTest {
        val catalog = SkillCatalog.load(skillsDir("greet" to skill("greet", "Say hello.", "Body.")))

        val answer = SkillTool(catalog).execute(JsonObject(mapOf("name" to JsonPrimitive("nope"))))

        assertContains(answer, "no skill called 'nope'")
        assertContains(answer, "greet", message = "the model needs to be told what does exist")
    }

    // --- fixtures --------------------------------------------------------------

    private fun skill(name: String, description: String, body: String) =
        "---\nname: $name\ndescription: $description\n---\n\n$body\n"

    private fun skillsDir(vararg files: Pair<String, String>): Path {
        val directory = createTempDirectory("skills")
        files.forEach { (fileName, content) -> directory.resolve("$fileName.md").writeText(content) }
        return directory
    }
}
