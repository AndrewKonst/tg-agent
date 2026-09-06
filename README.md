# tg-bot-ai

A Telegram bot that is a genuine agent: it decides what to do, runs real commands in
a sandbox to do it, and follows written instructions — skills — for tasks it has been
taught.

The agentic loop is written here rather than taken from a framework. Koog contributes
one thing: turning a conversation and a set of tool schemas into a provider's wire
format, and parsing the reply back. Deciding what to do with that reply — running the
tools, feeding the results back, knowing when to stop — is [`AgentLoop`](src/main/kotlin/harness/AgentLoop.kt),
about 150 lines.

## What it does

- **Agentic loop** — calls the model repeatedly until it produces an answer, running
  whatever tools it asks for along the way, with hard limits on both steps and retries.
- **`exec`** — a universal shell tool, so anything with a command-line interface or a
  REST API is reachable. It runs inside a Docker container, and only for chats on an
  allowlist.
- **Skills** — Markdown instruction sheets the agent reads on demand: how to drive a
  particular CLI, or the steps of a routine.
- **Memory** — a chat is one long conversation, stored in SQLite so it survives a
  restart. `/new` starts a fresh one.

## Stack

| Purpose | Library |
| --- | --- |
| Language / runtime | Kotlin 2.3.21 on JVM 21 |
| Telegram | [tgbotapi](https://github.com/InsanusMokrassar/TelegramBotAPI) 36.1.0 |
| LLM plumbing | [Koog](https://github.com/JetBrains/koog) 1.2.0 (prompt executor only) |
| Concurrency | kotlinx-coroutines 1.11.0 |
| HTTP | Ktor Client 3.5.2 |
| History | SQLite via sqlite-jdbc 3.53.4.0 |
| Sandbox | Docker (alpine 3.22, ~13 MB) |
| Build | Gradle 9.7.1 (Kotlin DSL) |

## Requirements

- **JDK 21+** — the only hard requirement. Gradle arrives via the wrapper.
- A **Telegram bot token** from [@BotFather](https://t.me/BotFather).
- An **LLM**: an API key for a hosted provider, or a local [Ollama](https://ollama.com).
- **Docker**, if you want the `exec` tool. Without it the bot runs fine; the shell is
  simply unavailable.

## Quick start

```bash
cp .env.example .env     # then fill in TELEGRAM_BOT_TOKEN and the model settings
./gradlew run
```

Send the bot `/whoami`, put the number it replies with into `OWNER_CHAT_IDS` in
`.env`, and restart. That is what enables the shell — for you and nobody else.

## Configuration

Everything comes from environment variables, falling back to `.env`. Real environment
variables always win, so a stray `.env` on a server cannot override a deployment.

### Required

| Variable | Description |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `LLM_MODEL` | Model id, e.g. `gpt-4o-mini` or `qwen3:14b` |
| `LLM_API_KEY` | API key for the provider. Not needed for Ollama |

### The model

| Variable | Default | Description |
| --- | --- | --- |
| `LLM_PROVIDER` | `openai` | `openai` \| `anthropic` \| `ollama` |
| `LLM_BASE_URL` | provider default | Point at a gateway: Azure, OpenRouter, LiteLLM, vLLM |
| `LLM_TIMEOUT_MS` | `30000` | Deadline for a whole run, every step together |
| `LLM_CALL_TIMEOUT_MS` | `60000` | Deadline for one call to the model |
| `LLM_MAX_ATTEMPTS` | `3` | Retries after a transient failure, counted separately from steps |
| `SYSTEM_PROMPT` | built-in | Instructions given to the agent |
| `AGENT_MAX_STEPS` | `8` | How many times the model may be asked for one message |

### Memory

| Variable | Default | Description |
| --- | --- | --- |
| `CONVERSATION_STORE` | `sqlite` | `sqlite` \| `memory` |
| `CONVERSATION_DB_PATH` | `data/conversations.db` | Where history is kept |
| `CONVERSATION_MAX_CHARS` | `12000` | How much history is replayed into each run |

### Shell access

| Variable | Default | Description |
| --- | --- | --- |
| `OWNER_CHAT_IDS` | *(empty)* | Chats allowed to run commands. Empty means nobody, and `exec` stays off |
| `EXEC_SANDBOX` | `docker` | `docker` \| `host` \| `off` |
| `EXEC_TIMEOUT_MS` | `30000` | Hard limit on one command |
| `EXEC_OUTPUT_LIMIT` | `4000` | How much output reaches the model, in characters |
| `EXEC_IMAGE` | `tg-agent-sandbox:1` | Built from `sandbox/Dockerfile` on first use |
| `EXEC_CONTAINER` | `tg-agent-sandbox` | Container name |
| `EXEC_WORKDIR` | `data/workspace` | Working directory for `EXEC_SANDBOX=host` |
| `PROJECT_GIT_DIR` | `.git` | Repository mounted read-only at `/project/.git` |
| `SKILLS_DIR` | `skills` | Directory of Markdown skill files |

## Architecture

```
Telegram
    │
    ▼
TelegramBot              long polling; /start /help /new /whoami
    │
    ▼
MessageHandler           one coroutine per message, whole-run timeout
    │
    ▼
HarnessAgentService      per-chat lock: load history → run → append
    │
    ▼
AgentLoop  ★             the agentic loop
    │
    ├── Llm  ─────────►  Ollama / OpenAI / Anthropic
    │
    └── tools
         ├── current_datetime
         ├── skill   ──►  skills/*.md
         └── exec    ──►  Docker container ──► the network, /project/.git
```

### The loop

```
messages = [system prompt] + [history] + [the new message]

repeat up to AGENT_MAX_STEPS:
    reply = llm(messages, tools)          ← retried up to LLM_MAX_ATTEMPTS
    if reply has no tool calls:
        return reply                      ← done
    for each tool call:
        run it, append the result
```

Three guards keep a run finite, and they are independent on purpose:

- **`AGENT_MAX_STEPS`** bounds reasoning: how many times the model may be asked.
- **`LLM_MAX_ATTEMPTS`** bounds flakiness: retries on network failures, HTTP 429 and
  5xx, with exponential backoff and jitter. A flaky connection must not consume the
  budget the model needs to think.
- **Duplicate-call detection** bounds stubbornness. A model that re-issues an
  identical call is not making progress, so the second one is answered with "you
  already asked that, the result is above" instead of being executed, and the third
  ends the run. Small local models do this often enough to be worth handling
  explicitly rather than waiting for the step budget to run out.

A tool call that fails — an unknown name, malformed arguments, a tool that throws —
comes back to the model as a readable error it can correct on the next step, never as
an exception that ends the run.

### Memory

A chat is one long conversation. Before each run the stored history is replayed;
afterwards the whole exchange is appended.

Trimming to `CONVERSATION_MAX_CHARS` happens by **turn**, not by message. Dropping
individual messages would eventually separate a tool call from its result, and a
provider rejects a conversation containing an orphaned result. A turn — a user
message plus every reply and tool result it produced — is dropped whole, which makes
that impossible by construction.

The model's chain-of-thought is stripped before storage: it was what the model needed
to reach *that* answer, and replaying it on every later turn spends context on
nothing.

Reading, running and appending happen under a per-chat lock. Without it, two quick
messages from one person would both read the same history and write interleaved
transcripts over each other. Different chats never wait on each other.

### The sandbox, and why the bot is not in it

```
Telegram ──► bot (host, JVM) ──HTTP──► Ollama (host, :11434)
                  │
                  └──docker exec──► sandbox container ──curl──► the internet
                                         /project/.git (read-only)
```

The bot stays on the host and only commands travel into the container. That way a
command cannot read the bot's `.env` or kill its own process, and the model provider
stays reachable with no container networking.

Four layers stand between a stranger on Telegram and a shell:

1. **The allowlist.** `exec` is not merely refused to non-owners — it is absent from
   the tool list they are shown, along with the skills that depend on it.
2. **The container.** 512 MB, one CPU, 256 processes. Only `/work` is writable.
3. **The mount.** Only `.git` is exposed, read-only. The working tree — where `.env`
   lives — is not mounted at all.
4. **The bounds.** A 30-second timeout that kills the whole process tree, truncated
   output, and closed stdin so a command that decides to prompt dies instead of
   hanging.

Network access is deliberately left on, because reaching a REST API with `curl` is
the point. That is also the sharpest edge: anything fetched arrives in the model's
context, and a web page can try to talk the agent into running something. Every skill
states that command output is data, never instructions.

`EXEC_SANDBOX=host` runs commands with no isolation at all. It exists so a machine
without Docker can still demonstrate the agent, and it says so loudly in the log.

### Skills

A skill is a Markdown file with front matter:

```markdown
---
name: weather-wttr
description: Fetch weather for a city from wttr.in using curl.
---

# body: how to use the thing
```

Only the **description** goes into the system prompt — one line per skill. The body is
fetched with the `skill` tool once the model decides the skill applies. With two
skills the difference is invisible; with twenty it is the difference between a usable
prompt and a drowned one, and most of a catalogue is irrelevant to any single
question.

Two skills ship with the bot:

- [`weather-wttr`](skills/weather-wttr.md) — instructions for a CLI/API: `curl`
  against wttr.in, which format to pick, what the failures look like.
- [`morning-brief`](skills/morning-brief.md) — a routine: today's date, the weather,
  yesterday's commits, then one short summary.

Editing a skill takes effect immediately — bodies are re-read from disk on every call.
Adding one needs a restart, since the catalogue is scanned at startup.

## Running against a local model (Ollama)

No API key and no outbound calls.

```bash
brew install ollama && brew services start ollama
ollama pull qwen3:14b
```

```
LLM_PROVIDER=ollama
LLM_MODEL=qwen3:14b
LLM_TIMEOUT_MS=180000
```

**Pick a model that supports tool calling** — without it the agent has no way to act.
Known-good tags: `qwen3`, `llama3.1`, `llama3.2`, `mistral-nemo`. `phi` and `gemma`
will not work here.

Two caveats specific to local models. Ollama's default context window is small, and
overflowing it silently drops the beginning of a conversation rather than failing —
raise it if runs get long. And a 14B model is markedly worse at multi-step tool use
than a hosted one: it invents tool names, repeats calls and loses track of long
histories. The guards above exist largely because of that.

## Tests

```bash
./gradlew test
```

95 tests, no network required. The two integration suites that do need something
(`OllamaIntegrationTest` needs Ollama, `DockerSandboxIntegrationTest` needs Docker)
skip themselves when it is absent.

The interesting ones:

- **`AgentLoopTest`** — every guard, provoked deliberately against a scripted model:
  the step limit, duplicate calls, unknown tool names, malformed arguments, a tool
  that throws.
- **`ChatHistoryTest`** — sweeps every budget from 1 to 400 characters and asserts no
  orphaned tool result ever survives trimming.
- **`SandboxTest`** — runs real processes: timeouts, output caps, closed stdin, the
  scrubbed environment. Mocking the code that hands a model a shell would be
  self-deception.
- **`ConversationServiceTest`** — history replay, `/new`, chat isolation, and that two
  concurrent messages in one chat cannot interleave.

## Extending it

**A tool** — implement [`AgentTool`](src/main/kotlin/harness/AgentTool.kt) and add one
line to `AgentFactory.buildToolBox`:

```kotlin
class CalendarTool : AgentTool {
    override val descriptor = ToolDescriptor(
        name = "calendar_events",
        description = "Lists the user's events for a given day.",
        requiredParameters = listOf(
            ToolParameterDescriptor("date", "Date as YYYY-MM-DD", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): String = TODO()
}
```

**A skill** — drop a `.md` file with front matter into `skills/` and restart. No code.

## Project layout

```
src/main/kotlin/
  App.kt                  entry point: config, wiring, shutdown
  harness/                the agentic loop, tool abstraction, retries
  agent/                  the AI boundary and what each chat may do
  conversation/           history: trimming, storage, per-chat locks
  sandbox/                where commands run
  tools/                  current_datetime, exec, skill
  skills/                 the skill catalogue
  telegram/               long polling, commands, replies
  error/                  failures → short, non-technical replies
skills/                   the skill files themselves
sandbox/Dockerfile        the sandbox image
```
