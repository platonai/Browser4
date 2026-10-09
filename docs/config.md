# LLM Configuration

Browser4 supports multiple LLM providers. Configure **one** provider with its API key, and optionally the model name and base URL.

> ⚠️ Configure **exactly one** provider. When several provider keys are present, only one of
> them is used — see [Which provider is used?](#which-provider-is-used) — and the others are
> silently ignored, which is the most common cause of "my new key has no effect".

## Configuration methods

Properties can be set in two ways (in order of precedence):

1. **Environment variables** — recommended for secrets (API keys)
2. **`application.properties`** — the project's Spring Boot config file

Property names use dots (e.g. `openrouter.api.key`). For environment variables, uppercase and replace dots with underscores: `OPENROUTER_API_KEY`.

The backend reads these files at startup, so **restart the backend** after editing
`~/.browser4/config/conf-enabled/application-private.properties`:
`browser4-cli stop`, then any command (e.g. `browser4-cli open <url>`) starts it again with
the new configuration.

`browser4-cli doctor` prints the exact file it will read (`Config file: …`), and
`browser4-cli doctor --fix` writes a **commented template** there — every provider commented
out, so it changes nothing until you uncomment one block and add a real key. The template
ships inside the runtime; the `--fix` step only materializes and enables it, and it never
overwrites a file you already edited.

## Which provider is used?

When **more than one** provider key is configured, the first one in the built-in detection
order wins. `OPENAI_API_KEY` is deliberately near the end of that order, so a generic
OpenAI-compatible key never shadows a dedicated provider:

```
openrouter → groq → together → mistral → xai → perplexity → fireworks → deepseek →
dashscope(bailian) → volcengine → zhipu → moonshot → baichuan → yi → stepfun →
hunyuan → qianfan → openai → anthropic → gemini → minimax → (alias keys, e.g. KIMI_API_KEY)
```

Consequences worth knowing:

- A leftover `deepseek.api.key` (still uncommented in the same
  `application-private.properties`, or exported in the backend's environment) **wins over**
  `openai.api.key`, so `openai.base.url` / `openai.model.name` appear to be ignored.
- A key that is set but **empty** (`deepseek.api.key=`) has no value and must never win.
  `browser4-base` 4.11.20+ ignores it; in older Browser4 releases remove or comment the line.
- Two ways to force the provider:
  - `llm.provider.deny.list=deepseek` — the denied provider is skipped during detection
    (works on every version).
  - `llm.provider=openai` — explicit selection, wins over the order above. Available since
    `browser4-base` 4.11.20 (Browser4 4.13.x pins it); in older Browser4 releases it is only
    consulted when no provider key is configured at all.

`browser4-cli doctor` reports what the backend will actually do:

```
-- LLM Status --
  ✓ LLM is configured.
  Configured keys: DEEPSEEK_API_KEY (configuration file), OPENAI_API_KEY (environment variable)
  Config file: ~/.browser4/config/conf-enabled/application-private.properties
  Selected key: DEEPSEEK_API_KEY (first in the built-in priority list)
  Active model: deepseek-v4-flash (OpenAiChatModel)
  Source: configuration file
  ⚠  Several LLM providers are configured: ...
```

When nothing is configured yet, the same line names the file to create and how:

```
-- LLM Status --
  LLM is not configured, you can only use non-LLM commands. …
  Config file: ~/.browser4/config/conf-enabled/application-private.properties (not present — 'browser4-cli doctor --fix' writes a commented template)
```

`Selected key` + `Active model` are the authoritative answer to "where do my requests go?".
The backend log line `Using LLM provider | provider=… model=… baseUrl=… apiKey=…` carries the
same information (see `browser4-cli doctor --verbose`).

## Providers

### OpenRouter (default)

```properties
openrouter.api.key=sk-or-v1-...
openrouter.model.name=openai/gpt-5.4
openrouter.base.url=https://openrouter.ai/api/v1/  # optional
```

| Env var                 | Property                | Default |
|-------------------------|-------------------------|---|
| `OPENROUTER_API_KEY`    | `openrouter.api.key`    | — |
| `OPENROUTER_MODEL_NAME` | `openrouter.model.name` | `bytedance-seed/seed-1.6` |
| `OPENROUTER_BASE_URL`   | `openrouter.base.url`   | `https://openrouter.ai/api/v1` |

OpenRouter gives access to many models through one API. `model.name` defaults to a reasonable choice; override it to use any model available on OpenRouter (e.g. `bytedance-seed/seed-2.0-lite`).

### DeepSeek

```properties
deepseek.api.key=sk-...
deepseek.model.name=deepseek-v4-flash[1m]
```

| Env var               | Property              | Default |
|-----------------------|-----------------------|---|
| `DEEPSEEK_API_KEY`    | `deepseek.api.key`    | — |
| `DEEPSEEK_MODEL_NAME` | `deepseek.model.name` | `deepseek-v4-flash` |
| `DEEPSEEK_BASE_URL`   | `deepseek.base.url`   | `https://api.deepseek.com/v1` |


Uses DeepSeek's official API. Model defaults to DeepSeek's latest.

### OpenAI / OpenAI-compatible

```properties
openai.api.key=sk-...
openai.model.name=gpt-5.4
openai.base.url=https://api.openai.com/v1       # optional
```

| Env var | Property | Default |
|---|---|---|
| `OPENAI_API_KEY` | `openai.api.key` | — |
| `OPENAI_MODEL_NAME` | `openai.model.name` | `gpt-5.6-sol` |
| `OPENAI_BASE_URL` | `openai.base.url` | `https://api.openai.com/v1` |

Works with any OpenAI-compatible API by changing `base.url`. For example, Aliyun Qwen (DashScope):

```properties
openai.api.key=sk-...
openai.model.name=qwen-plus
openai.base.url=https://dashscope.aliyuncs.com/compatible-mode/v1
```

> 💡 `openai.*` is the generic "any OpenAI-compatible endpoint" slot (DeepInfra, vLLM,
> LM Studio, DashScope, ...). Because it sits near the end of the detection order, remove or
> deny every other provider key — see [Which provider is used?](#which-provider-is-used).

### Volcengine (ByteDance)

```properties
volcengine.api.key=...
volcengine.model.name=doubao-seed-2-0-pro-260215
volcengine.base.url=https://ark.cn-beijing.volces.com/api/v3  # optional
```

Windows (PowerShell)
```powershell
$env:BROWSER_CONTEXT_MODE = "SYSTEM_DEFAULT"
```

For high-performance parallel crawling:

Linux/MacOS
```bash
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
export BROWSER_CONTEXT_MODE=SEQUENTIAL
export BROWSER_CONTEXT_NUMBER=2
export BROWSER_MAX_OPEN_TABS=8
export BROWSER_DISPLAY_MODE=HEADLESS
```

Windows (PowerShell)
```powershell
$env:PROXY_ROTATION_URL = "https://your-proxy-provider.com/rotation-endpoint"
$env:BROWSER_CONTEXT_MODE = "SEQUENTIAL"
$env:BROWSER_CONTEXT_NUMBER = 2
$env:BROWSER_MAX_OPEN_TABS = 8
$env:BROWSER_DISPLAY_MODE = "HEADLESS"
```

### Anthropic Claude

```properties
anthropic.api.key=sk-ant-...
anthropic.model.name=claude-sonnet-4-6
```

Uses the native Anthropic Messages protocol (not OpenAI-compatible). Default model: `claude-sonnet-4-6`.

### Google Gemini

```properties
google.generative.ai.api.key=your-key   # primary key name
# gemini.api.key=your-key               # alias
# google.api.key=your-key               # alias
gemini.model.name=gemini-3.1-flash-lite
```

Uses the native Gemini protocol (not OpenAI-compatible). Default model: `gemini-3.1-flash-lite`.

### xAI / Grok

```properties
xai.api.key=your-key
xai.model.name=grok-4.5
xai.base.url=https://api.x.ai/v1        # optional
```

### Groq

```properties
groq.api.key=your-key
groq.model.name=llama-3.3-70b-versatile
groq.base.url=https://api.groq.com/openai/v1   # optional
```

### Together AI

```properties
together.api.key=your-key
together.model.name=meta-llama/Llama-3.3-70B-Instruct-Turbo
together.base.url=https://api.together.xyz/v1  # optional
```

### Mistral

```properties
mistral.api.key=your-key
mistral.model.name=mistral-large-latest
mistral.base.url=https://api.mistral.ai/v1     # optional
```

### Perplexity

```properties
perplexity.api.key=your-key
perplexity.model.name=llama-3.1-sonar-large-128k-online
```

### Fireworks AI

```properties
fireworks.api.key=your-key
fireworks.model.name=accounts/fireworks/models/llama-v3p3-70b-instruct
```

### Alibaba DashScope / Qwen (阿里云-百炼)

```properties
dashscope.api.key=sk-...
dashscope.model.name=qwen3.6-plus
dashscope.base.url=https://dashscope.aliyuncs.com/compatible-mode/v1   # optional
```

### Zhipu AI / GLM (智谱AI)

```properties
zhipu.api.key=your-key
zhipu.model.name=glm-5.1
zhipu.base.url=https://open.bigmodel.cn/api/paas/v4/   # optional
```

### Moonshot / Kimi (月之暗面)

```properties
moonshot.api.key=your-key
# kimi.api.key=your-key              # alias for moonshot
moonshot.model.name=kimi-k2.6
moonshot.base.url=https://api.moonshot.cn/v1   # optional
```

### Baichuan (百川智能)

```properties
baichuan.api.key=your-key
baichuan.model.name=Baichuan4
baichuan.base.url=https://api.baichuan-ai.com/v1   # optional
```

### 01.AI / Yi (零一万物)

```properties
yi.api.key=your-key
# lingyi.api.key=your-key            # alias for yi
yi.model.name=yi-large
yi.base.url=https://api.lingyiwanwu.com/v1   # optional
```

### MiniMax / Hailuo (稀宇科技)

```properties
minimax.api.key=your-key
minimax.model.name=MiniMax-M3
```

Uses the Anthropic Messages protocol (not OpenAI-compatible).

### StepFun (阶跃星辰)

```properties
stepfun.api.key=your-key
stepfun.model.name=step-3.5-flash
stepfun.base.url=https://api.stepfun.com/v1   # optional
```

### Tencent Hunyuan (腾讯混元)

```properties
hunyuan.api.key=your-key
# tencent.api.key=your-key           # alias for hunyuan
hunyuan.model.name=hunyuan-pro
hunyuan.base.url=https://api.lkeap.cloud.tencent.com/v1   # optional
```

### Baidu Qianfan / Ernie (百度千帆/文心)

```properties
qianfan.api.key=your-key
# baidu.api.key=your-key             # alias for qianfan
qianfan.model.name=ernie-4.0-8k
qianfan.base.url=https://qianfan.baidubce.com/v2   # optional
```

#### ☕ Example – JVM Arguments

Set configuration via command-line JVM args:

```
-D"openrouter.api.key=sk-yourllmproviderapikey"
```

---

### 🐳 Docker Configuration

For Docker deployments, use environment variables in the `docker run` command.

**Linux/macOS:**

```bash
docker run -d -p 18182:18182 \
  -e OPENROUTER_API_KEY=${OPENROUTER_API_KEY} \
  -e PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint \
  -e BROWSER_CONTEXT_MODE=SEQUENTIAL \
  -e BROWSER_CONTEXT_NUMBER=2 \
  -e BROWSER_MAX_OPEN_TABS=8 \
  -e BROWSER_DISPLAY_MODE=HEADLESS \
  galaxyeye88/browser4:latest
```

**Windows (PowerShell):**

```powershell
docker run -d -p 18182:18182 `
  -e OPENROUTER_API_KEY=$env:OPENROUTER_API_KEY `
  -e PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint `
  -e BROWSER_CONTEXT_MODE=SEQUENTIAL `
  -e BROWSER_CONTEXT_NUMBER=2 `
  -e BROWSER_MAX_OPEN_TABS=8 `
  -e BROWSER_DISPLAY_MODE=HEADLESS `
  galaxyeye88/browser4:latest
```

> ⚠️ **Note**: Docker users may need to warm up the before crawling to avoid bot detection,
> for example, visit the home page and open some arbitrary pages.

---

## ⚙️ Common Configuration Options

* **`openrouter.api.key`**
  Your OpenRouter API key. Check the [Providers section](#providers) above for all supported LLM providers.

- **`browser.profile.mode`** (`DEFAULT` | `SYSTEM_DEFAULT` | `PROTOTYPE` | `SEQUENTIAL` | `TEMPORARY`)
  Defines how the user data directory is assigned for each browser instance.

  - `DEFAULT`: Uses the default Browser4-managed user data directory.
  - `SYSTEM_DEFAULT` ⚠️ **[Deprecated]**: Uses the system's default browser profile (e.g., your personal Chrome/Edge profile).
    - Not supported by Chrome ≥ 143 (see [Browser4 issue #162](https://github.com/platonai/Browser4/issues/162)). To reuse system browser state, use `attach` + `state-save`/`state-load` instead — see [browser-state-import.md](../skills/browser4-cli/references/browser-state-import.md).
  - `PROTOTYPE` **[Advanced]**: Uses a predefined prototype user data directory.
    - All `SEQUENTIAL` and `TEMPORARY` modes inherit from this prototype.
  - `SEQUENTIAL` **[Advanced]**: Selects a user data directory from a managed pool to enable sequential isolation.
  - `TEMPORARY` **[Advanced]**: Generates a new, isolated user data directory for each browser instance.
  - **Named sessions** (`open --name <n>` / `--sessionId <id>`): the backend binds a **dedicated profile directory** keyed by the stable session id (`~/.browser4/context/groups/named/PULSAR_CHROME/cx.<sessionUuid>`), so reopening the session always restores the same cookies / login state instead of rotating through the `SEQUENTIAL` pool. An explicit `TEMPORARY` request on a named session is honored as-is.

* **`proxy.rotation.url`**
  [**Advanced**] Only for `SEQUENTIAL` and `TEMPORARY` modes.
  Defines the URL provided by your proxy service.
  Each time the rotation URL is accessed, it should return a response containing one or more fresh proxy IPs.
  Ask your proxy provider for such a URL.

* **`browser.context.number`** *(default: 2)*
  [**Advanced**] Only for `SEQUENTIAL` and `TEMPORARY` modes.
  Number of browser contexts (isolated, incognito-like sessions).
  Each context has its own cookies, local storage, and cache.

  > For `DEFAULT`, `SYSTEM_DEFAULT`, and `PROTOTYPE` browser contexts, this value is **1**.

* **`browser.max.active.tabs`** *(default: 8)*
  Maximum number of tabs per browser instance.

  > For `DEFAULT`, `SYSTEM_DEFAULT`, and `PROTOTYPE` browser contexts, there is **no limit**.

* **`browser.display.mode`** (`GUI` | `HEADLESS` | `SUPERVISED`)
  Controls how the browser is displayed:

    * `GUI`: Launches a visible browser window.
    * `HEADLESS`: Runs without a graphical window.
    * `SUPERVISED`: Linux-only; wraps the browser launch in an external supervisor process — in practice an Xvfb-based program such as `xvfb-run`, to simulate a GUI without a display.
      The supervisor must be configured: set `browser.launch.supervisor.process` (and optionally `browser.launch.supervisor.process.args`), e.g.
      `browser.launch.supervisor.process=xvfb-run`. Without a configured — and locatable — supervisor the mode has no effect (Chrome is launched normally), and it does **not** imply headless.

  > **Session-level override wins:** when a session is created with an explicit
  > display mode (e.g. `open --headed` / `open --headless` sets the `headed`
  > capability, or an explicit `displayMode` capability), the session's choice
  > is honored by the browser launch — see
  > `AbstractPulsarSession.createBoundDriver`. The server-level default above
  > only applies to sessions created without a display preference.

* **`browser.enabled`** *(default: `true`)*
  Enables the built-in `browser4-browser` runtime plugin wiring.
  Set `browser.enabled=false` to disable browser runtime beans.

* **`crawl.autoResume`** *(default: `false`)*
  Whether a crawl that was **running when the backend process died** is resumed
  automatically at startup, from its checkpoint.

  - `false` (default): such a task is reported as `Interrupted` and waits for an
    explicit `browser4-cli crawl resume <task-id>`. A restart is not consent to keep
    hitting third-party sites, so nothing is fetched until someone asks.
  - `true`: every interrupted task is continued at startup — already-fetched URLs are
    not requested again, the URLs that were in flight and the discovered frontier are
    fetched, and the result is merged with per-row provenance.

  Checkpoints live in `~/.browser4/data/crawl/checkpoints/<taskId>.json` (override the
  root with `browser4.data.dir`), survive the task store's LRU and TTL, and are
  discarded by `crawl clear --all`. See
  [Crawl checkpoint & resume](crawl-checkpoint-resume.md).

* **`knowledge.dir`** *(JVM system property; default: `knowledge`)*
  Root of the progressive experience memory (PEM) store: the `traces/`, `experience/` and
  `facts/` YAML trees live under it (see [experience-memory.md](experience-memory.md#storage-layout)).
  A relative path resolves against the backend process's working directory.
  The engine's agent-memory L1 layer reads the same property, so one setting keeps explicit
  `experience save|query|list|deep-learn` calls and the engine's automatic deposits in **one**
  knowledge base instead of two — and lets several backends (or a test harness) avoid sharing a store.

  Because it is a JVM system property rather than an `application.properties` key, pass it on the
  command line:

  ```bash
  # Backend started by the CLI (the CLI forwards BROWSER4_SERVER_OPTS to the JVM)
  BROWSER4_SERVER_OPTS="-Dknowledge.dir=/var/lib/browser4/knowledge" browser4-cli open

  # Backend started by you
  java -Dknowledge.dir=/var/lib/browser4/knowledge -jar Browser4.jar
  ```

### 📦 `browser.profile.mode` Comparison Table

| Mode           | Description                                                                 | User Data Directory Behavior                             | Use Case            |
|----------------|-----------------------------------------------------------------------------|-----------------------------------------------------------|---------------------|
| `DEFAULT`      | Uses the Browser4-managed default profile.                                 | Shared across Pulsar sessions (not your system browser).  | General purpose     |
| `SYSTEM_DEFAULT` ⚠️ | **[Deprecated]** Uses the system browser's default profile.               | Shares your daily-used browser profile.                   | **Not supported by Chrome ≥ 143** — use attach + state-save/state-load |
| `PROTOTYPE` ⚠️ | **[Advanced]** Uses a predefined prototype profile.                         | Acts as the base for `SEQUENTIAL` and `TEMPORARY`.        | Controlled state inheritance |
| `SEQUENTIAL` ⚠️ | **[Advanced]** Picks a profile from a pool sequentially.                   | Rotates through a pool of pre-initialized directories.     | Avoid session reuse in batch runs |
| `TEMPORARY` ⚠️  | **[Advanced]** Creates a new, isolated profile for each browser instance. | Discarded after session ends.                             | Maximum isolation / stateless crawling |

---

### Reusing state from the system browser

To copy your system Chrome/Edge state (cookies + localStorage, or a full
profile) into a Browser4-managed browser, see
[browser-state-import.md](../skills/browser4-cli/references/browser-state-import.md).
The supported flow is: `attach --extension` (or `attach --cdp`) →
`state-save <file>` → `open --fresh` → `state-load <file>`. The
`SYSTEM_DEFAULT` profile mode is deprecated and does not work with Chrome ≥ 143.

---

## 🤖 CAPTCHA Solving (Optional Plugin)

The CAPTCHA solving feature is an **optional plugin** — it only activates when
`browser4-captcha.jar` is on the classpath. Without the JAR, the application
starts normally with no captcha functionality.

### Enabling CAPTCHA

1. **Runtime bundle**: drop `browser4-captcha.jar` into the `plugins/` directory
   (or `lib/`) — picked up automatically via the `lib/*:plugins/*` classpath
   wildcard. No rebuild needed.
2. **Development** (`spring-boot:run`): add as a Maven dependency.
3. **Fat JAR**: add `browser4-captcha` as a dependency before building.

From the source tree you can also install via the CLI:

```bash
browser4-cli plugin install browser4-plugins/browser4-captcha/target/browser4-captcha-*.jar
browser4-cli stop    # the next browser4-cli command auto-restarts the dev backend
```

### Calling the CAPTCHA tools

The plugin registers MCP tools named `captcha_detect`, `captcha_solve`,
`captcha_solve_image`, and `captcha_get_balance` (domain.method `captcha.detect`
maps to snake_case `captcha_detect`). Invoke them from the CLI with the generic
passthrough:

```bash
browser4-cli tool call captcha_detect
browser4-cli tool call captcha_get_balance
browser4-cli tool call captcha_solve --json '{"type": "RECAPTCHA_V2", "siteKey": "<site-key>"}'
```

With no API key configured, `captcha_get_balance` returns
`{configured: false, balance: null, ...}` and `captcha_solve` fails with
"No CAPTCHA solving provider configured".

### Configuration Properties

| Property | Default | Description |
|---|---|---|
| `captcha.auto.solve.enabled` | `true` | Master switch; set to `false` to disable even when JAR is present |
| `captcha.service.provider` | `CAPSOLVER` | Primary solving service: `CAPSOLVER`, `TWO_CAPTCHA`, `ANTI_CAPTCHA` |
| `captcha.capsolver.api.key` | (none) | API key for CapSolver |
| `captcha.twocaptcha.api.key` | (none) | API key for 2Captcha |
| `captcha.anticaptcha.api.key` | (none) | API key for Anti-Captcha |
| `captcha.solve.timeout.seconds` | `120` | Max wait for a solution |
| `captcha.poll.interval.ms` | `1000` | Interval between status polls |
| `captcha.detection.enabled` | `true` | Auto-detect CAPTCHAs on page load |
| `captcha.auto.solve.types` | `RECAPTCHA_V2,HCAPTCHA,TURNSTILE` | CAPTCHA types to auto-solve (comma-separated, or `ALL`) |
| `captcha.report.failed.enabled` | `true` | Report failed solves for refund (2Captcha / Anti-Captcha only) |
| `captcha.solve.max.retries` | `3` | Max retry attempts per solve |

### Example

```properties
captcha.auto.solve.enabled=true
captcha.service.provider=CAPSOLVER
captcha.capsolver.api.key=CAP-XXXXXXXXXXXX
captcha.solve.timeout.seconds=180
captcha.auto.solve.types=RECAPTCHA_V2,RECAPTCHA_V3,HCAPTCHA,TURNSTILE
```

### Behavior Matrix

| JAR on classpath | `auto.solve.enabled` | Result |
|---|---|---|
| Yes | `true` (or absent) | CAPTCHA fully active |
| Yes | `false` | CAPTCHA disabled (property blocks it) |
| No | any value | CAPTCHA silently skipped (no error) |

---

## 🔎 Troubleshooting

### "I configured `OPENAI_*` (or DeepInfra / vLLM / DashScope) but requests still go to DeepSeek"

The routing follows the detection order, not the key you added last. Work through this list:

1. **Look at what won:** `browser4-cli doctor` now prints `Selected key`, `Active model` and a
   warning when several providers compete. `Selected key: DEEPSEEK_API_KEY` while
   `OPENAI_API_KEY` is also listed means a DeepSeek key is still configured somewhere.
2. **Find the other key.** The backend reads, in this order:
   `-D` system properties, environment variables of the **backend process**, and every
   `application.properties` / `application-private.properties` in the working directory,
   `./config/`, the project root, and `~/.browser4/config/conf-enabled/`.
   The shipped `application-private.properties` lists *all* providers — an uncommented
   `deepseek.*` block left over from an earlier setup wins over `openai.*`.
3. **Remove it** (comment the unused block out) and restart the backend: the configuration
   is read once at startup, so editing the file alone changes nothing.
4. **Or force the choice** without editing keys:
   ```properties
   # Every version: skip the provider you do not want
   llm.provider.deny.list=deepseek
   # browser4-base 4.11.20+: select explicitly
   llm.provider=openai
   ```
5. **Check for an empty key.** A line like `deepseek.api.key=` counts as *present* on
   `browser4-base` < 4.11.20 and can hijack the selection; `doctor` reports such keys under
   `emptyKeys` with a warning. Comment the line out.
6. **Let the tooling create the file.** `browser4-cli doctor --fix` writes a commented
   template to `~/.browser4/config/conf-enabled/application-private.properties` (it never
   overwrites an existing file) and prints the path, so the location never has to be guessed.

### "✓ LLM is configured" but every LLM command fails

The key is visible to the backend but rejected (wrong provider for that key, expired key,
or a base URL that does not serve the configured model). `doctor` shows the model and client
the backend built: `Active model: … (OpenAiChatModel)`. Compare it against the provider that
issued the key. Requests fail loudly in the backend log
(`browser4-cli doctor --verbose --log-filter "Chat"`).

### The key works in my shell but not in Browser4

The CLI only sends HTTP requests; the **backend process** calls the LLM. When the CLI
auto-starts the backend it inherits the CLI's environment, so export the key before the first
`browser4-cli` command of the session — or put it in
`~/.browser4/config/conf-enabled/application-private.properties` and restart the backend.

---

## 💡 Configuration Best Practices

1. 🔐 Use **environment variables** for credentials or sensitive values.
2. 📁 Use **configuration files** for structured or shared settings.
3. ⚡ Use **system properties** for quick runtime overrides.
4. 📝 Always **document changes** to ensure team transparency.
