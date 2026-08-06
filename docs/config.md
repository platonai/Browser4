# LLM Configuration

Browser4 supports multiple LLM providers. Configure **one** provider with its API key, and optionally the model name and base URL.

## Configuration methods

Properties can be set in two ways (in order of precedence):

1. **Environment variables** — recommended for secrets (API keys)
2. **`application.properties`** — the project's Spring Boot config file

Property names use dots (e.g. `openrouter.api.key`). For environment variables, uppercase and replace dots with underscores: `OPENROUTER_API_KEY`.

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
| `OPENROUTER_MODEL_NAME` | `openrouter.model.name` | — |

OpenRouter gives access to many models through one API. `model.name` defaults to a reasonable choice; override it to use any model available on OpenRouter (e.g. `bytedance-seed/seed-2.0-lite`).

### DeepSeek

```properties
deepseek.api.key=sk-...
deepseek.model.name=deepseek-v4-pro[1m]
```

| Env var               | Property              | Default |
|-----------------------|-----------------------|---|
| `DEEPSEEK_API_KEY`    | `deepseek.api.key`    | — |
| `DEEPSEEK_MODEL_NAME` | `deepseek.model.name` | — |


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

Works with any OpenAI-compatible API by changing `base.url`. For example, Aliyun Qwen (DashScope):

```properties
openai.api.key=sk-...
openai.model.name=qwen-plus
openai.base.url=https://dashscope.aliyuncs.com/compatible-mode/v1
```

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
docker run -d -p 8182:8182 \
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
docker run -d -p 8182:8182 `
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
  Your OpenRouter API key. Check [LLM Configuration Guide](../docs/config/llm/llm-config.md) for more LLM providers.

- **`browser.profile.mode`** (`DEFAULT` | `SYSTEM_DEFAULT` | `PROTOTYPE` | `SEQUENTIAL` | `TEMPORARY`)
  Defines how the user data directory is assigned for each browser instance.

  - `DEFAULT`: Uses the default Browser4-managed user data directory.
  - `SYSTEM_DEFAULT`: Uses the system's default browser profile (e.g., your personal Chrome/Edge profile).
  - `PROTOTYPE` **[Advanced]**: Uses a predefined prototype user data directory.
    - All `SEQUENTIAL` and `TEMPORARY` modes inherit from this prototype.
  - `SEQUENTIAL` **[Advanced]**: Selects a user data directory from a managed pool to enable sequential isolation.
  - `TEMPORARY` **[Advanced]**: Generates a new, isolated user data directory for each browser instance.

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
    * `SUPERVISED`: Linux-only; uses Xvfb for headless GUI simulation.

* **`browser.enabled`** *(default: `true`)*
  Enables the built-in `browser4-browser` runtime plugin wiring.
  Set `browser.enabled=false` to disable browser runtime beans.

### 📦 `browser.profile.mode` Comparison Table

| Mode           | Description                                                                 | User Data Directory Behavior                             | Use Case            |
|----------------|-----------------------------------------------------------------------------|-----------------------------------------------------------|---------------------|
| `DEFAULT`      | Uses the Browser4-managed default profile.                                 | Shared across Pulsar sessions (not your system browser).  | General purpose     |
| `SYSTEM_DEFAULT` | Uses the system browser's default profile.                                | Shares your daily-used browser profile.                   | For quick integration or debugging with real session data |
| `PROTOTYPE` ⚠️ | **[Advanced]** Uses a predefined prototype profile.                         | Acts as the base for `SEQUENTIAL` and `TEMPORARY`.        | Controlled state inheritance |
| `SEQUENTIAL` ⚠️ | **[Advanced]** Picks a profile from a pool sequentially.                   | Rotates through a pool of pre-initialized directories.     | Avoid session reuse in batch runs |
| `TEMPORARY` ⚠️  | **[Advanced]** Creates a new, isolated profile for each browser instance. | Discarded after session ends.                             | Maximum isolation / stateless crawling |

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

## 💡 Configuration Best Practices

1. 🔐 Use **environment variables** for credentials or sensitive values.
2. 📁 Use **configuration files** for structured or shared settings.
3. ⚡ Use **system properties** for quick runtime overrides.
4. 📝 Always **document changes** to ensure team transparency.
