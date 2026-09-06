package agent

import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import config.AppConfig
import config.LlmProvider
import conversation.ConversationStore
import conversation.HistoryWindow
import harness.AgentLoop
import harness.AgentTool
import harness.KoogLlm
import harness.ToolBox
import io.github.oshai.kotlinlogging.KotlinLogging
import sandbox.Sandbox
import skills.SkillCatalog
import tools.DateTimeTool
import tools.ExecTool
import tools.SkillTool
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Assembles the agent stack from [AppConfig].
 *
 * This is the only place that knows about concrete providers and models. Everything
 * downstream talks to the [AgentService] interface.
 */
object AgentFactory {

    /**
     * Builds the [AgentService] used for the whole process lifetime.
     *
     * The returned service owns a shared [PromptExecutor] — and with it one HTTP
     * connection pool — while every request runs its own loop on its own stack.
     * [store] is owned by the caller, which is also responsible for closing it.
     */
    fun create(config: AppConfig, store: ConversationStore, sandbox: Sandbox? = null): AgentService {
        val executor = buildPromptExecutor(config)
        val model = resolveModel(config)

        // Skills are instructions for driving command-line programs, so they are only
        // useful to a caller that has a shell. Loading them regardless keeps the
        // startup log honest about what was found on disk.
        val catalog = SkillCatalog.load(Path.of(config.skillsDir))

        // Two profiles, not one profile that checks permissions: a capability the
        // caller must not have is best expressed by never telling the model it exists.
        val guest = AgentProfile(
            systemPrompt = config.systemPrompt,
            tools = buildToolBox(sandbox = null, catalog = null, config),
        )
        val owner = if (sandbox == null) {
            guest
        } else {
            AgentProfile(
                systemPrompt = systemPromptWithSkills(config.systemPrompt, catalog),
                tools = buildToolBox(sandbox, catalog, config),
            )
        }
        val owners = config.ownerChatIds

        logger.info {
            "AI agent ready: provider=${config.llmProvider}, model='${model.id}', " +
                "maxSteps=${config.agentMaxSteps}, tools=[${guest.tools.names.joinToString()}]"
        }
        if (sandbox != null) {
            logger.info {
                "Shell enabled for ${owners.size} owner chat(s); their tools: " +
                    "[${owner.tools.names.joinToString()}]"
            }
        }

        val llm = KoogLlm(
            executor = executor,
            model = model,
            callTimeout = config.llmCallTimeout,
            maxAttempts = config.llmMaxAttempts,
        )

        return HarnessAgentService(
            loop = AgentLoop(llm = llm, maxSteps = config.agentMaxSteps),
            store = store,
            window = HistoryWindow(config.conversationMaxChars),
            profileFor = { chatId -> if (chatId in owners) owner else guest },
        )
    }

    /**
     * Tools the agent may call.
     *
     * To extend the agent, implement [AgentTool] (see [DateTimeTool]) and add one
     * line here. No other file needs to change.
     *
     * [sandbox] is null for everyone who is not an owner, and `exec` is left out of
     * their tool list entirely.
     */
    private fun buildToolBox(sandbox: Sandbox?, catalog: SkillCatalog?, config: AppConfig): ToolBox =
        ToolBox(
            buildList<AgentTool> {
                add(DateTimeTool())
                if (sandbox != null) {
                    add(ExecTool(sandbox, config.execTimeout, config.execOutputLimit))
                }
                // A skill nobody can act on is worse than no skill, so the catalogue is
                // only offered alongside the shell it tells the model how to use.
                if (sandbox != null && catalog != null && !catalog.isEmpty()) {
                    add(SkillTool(catalog))
                }
            },
        )

    /** Appends the skill catalogue to the system prompt, if there is one. */
    private fun systemPromptWithSkills(systemPrompt: String, catalog: SkillCatalog): String =
        if (catalog.isEmpty()) systemPrompt else "$systemPrompt\n\n${catalog.promptSection()}"

    /**
     * Builds the shared executor.
     *
     * `LLM_BASE_URL` lets the bot point at an OpenAI-compatible gateway — Azure
     * OpenAI, OpenRouter, LiteLLM, a self-hosted vLLM — without a code change.
     */
    private fun buildPromptExecutor(config: AppConfig): PromptExecutor {
        val builder = PromptExecutorBuilder()
        val baseUrl = config.llmBaseUrl

        when (config.llmProvider) {
            LlmProvider.OPENAI ->
                if (baseUrl == null) {
                    builder.openAI(config.llmApiKey)
                } else {
                    builder.openAI(config.llmApiKey, OpenAIClientSettings(baseUrl = baseUrl))
                }

            LlmProvider.ANTHROPIC ->
                if (baseUrl == null) {
                    builder.anthropic(config.llmApiKey)
                } else {
                    builder.anthropic(config.llmApiKey, AnthropicClientSettings(baseUrl = baseUrl))
                }

            LlmProvider.OLLAMA ->
                if (baseUrl == null) builder.ollama() else builder.ollama(baseUrl)
        }
        return builder.build()
    }

    /**
     * Maps the `LLM_MODEL` string onto a Koog [LLModel].
     *
     * Known ids resolve to Koog's catalogue entry, which carries the model's real
     * capabilities and context length. An unknown id is still accepted — useful for
     * models newer than this Koog release, or for local Ollama tags — and gets a
     * conservative capability set.
     */
    private fun resolveModel(config: AppConfig): LLModel {
        val catalogue: LLModelDefinitions? = when (config.llmProvider) {
            LlmProvider.OPENAI -> OpenAIModels
            LlmProvider.ANTHROPIC -> AnthropicModels
            LlmProvider.OLLAMA -> null
        }

        val known = catalogue?.models?.firstOrNull { it.id.equals(config.llmModel, ignoreCase = true) }
        if (known != null) return known

        // Ollama tags are always user-defined, so there is nothing to warn about there.
        if (catalogue != null) {
            logger.warn {
                "LLM_MODEL='${config.llmModel}' is not in Koog's ${config.llmProvider} catalogue; " +
                    "using it as a custom model id with default capabilities."
            }
        }

        val provider = when (config.llmProvider) {
            LlmProvider.OPENAI -> LLMProvider.OpenAI
            LlmProvider.ANTHROPIC -> LLMProvider.Anthropic
            LlmProvider.OLLAMA -> LLMProvider.Ollama
        }

        return LLModel(
            provider = provider,
            id = config.llmModel,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.Tools,
                LLMCapability.Completion,
            ),
            contextLength = DEFAULT_CONTEXT_LENGTH,
        )
    }

    private const val DEFAULT_CONTEXT_LENGTH = 128_000L
}
