# 🛠️ Browser4 Configuration Guide

## 📋 Configuration Sources

Browser4 supports multiple configuration sources in order of precedence:

1. 🔧 **Environment Variables**
2. ⚙️ **JVM System Properties**
3. 📝 **Spring Boot `application.properties`**

---

## 🔧 Configuration Methods

### 📝 Spring Boot Configuration Files

Browser4 uses Spring Boot-style `application.properties` files. A sample is located at the project root.

**🔐 Best practice:** Create an `application-private.properties` for secrets (API keys, proxy URLs). It's gitignored by default — never commit credentials.

#### LLM Provider Configuration

Browser4 supports multiple LLM providers. Set the API key (and optionally model/base-url) for your chosen provider:

```properties
# OpenRouter (default)
openrouter.api.key=sk-or-v1-...
openrouter.model.name=openai/gpt-5.4       # optional
openrouter.base.url=https://openrouter.ai/api/v1/  # optional

# DeepSeek (official)
deepseek.api.key=sk-...

# Volcengine / ByteDance
volcengine.api.key=...
volcengine.model.name=doubao-seed-2-0-pro-260215
volcengine.base.url=https://ark.cn-beijing.volces.com/api/v3

# OpenAI-compatible
openai.api.key=sk-...
openai.model.name=gpt-4o
openai.base.url=https://api.openai.com/v1

# Aliyun Qwen (DashScope) — uses OpenAI-compatible keys
openai.api.key=sk-...
openai.model.name=qwen-plus
openai.base.url=https://dashscope.aliyuncs.com/compatible-mode/v1
```

#### Desktop usage

```properties
# Optional: use your system's default browser profile
# browser.profile.mode=SYSTEM_DEFAULT
browser.display.mode=GUI
```

#### [**Advanced**] High-performance parallel crawling

```properties
proxy.rotation.url=https://your-proxy-provider.com/rotation-endpoint
browser.profile.mode=SEQUENTIAL
browser.context.number=2
browser.max.active.tabs=8
browser.display.mode=HEADLESS
```

---

### 🌍 Environment Variables

All Spring Boot properties can be set as environment variables. Convert dots to underscores and uppercase: `browser.profile.mode` → `BROWSER_PROFILE_MODE`.

#### Property → Env Var Quick Reference

| Spring Property | Environment Variable | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `8182` |
| `openrouter.api.key` | `OPENROUTER_API_KEY` | — |
| `deepseek.api.key` | `DEEPSEEK_API_KEY` | — |
| `volcengine.api.key` | `VOLCENGINE_API_KEY` | — |
| `openai.api.key` | `OPENAI_API_KEY` | — |
| `browser.profile.mode` | `BROWSER_CONTEXT_MODE` | `DEFAULT` |
| `browser.display.mode` | `BROWSER_DISPLAY_MODE` | `GUI` |
| `browser.context.number` | `BROWSER_CONTEXT_NUMBER` | `2` |
| `browser.max.active.tabs` | `BROWSER_MAX_OPEN_TABS` | `8` |
| `proxy.rotation.url` | `PROXY_ROTATION_URL` | — |

#### 💻 Desktop usage

Linux/macOS:
```bash
export OPENROUTER_API_KEY=sk-yourllmproviderapikey
```

Windows (PowerShell):
```powershell
$env:OPENROUTER_API_KEY = "sk-yourllmproviderapikey"
```

To use your daily-use browser profile (close the browser first):

Linux/macOS:
```bash
export BROWSER_CONTEXT_MODE=SYSTEM_DEFAULT
```

Windows (PowerShell):
```powershell
$env:BROWSER_CONTEXT_MODE = "SYSTEM_DEFAULT"
```

#### [**Advanced**] High-performance parallel crawling

Linux/macOS:
```bash
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
export BROWSER_CONTEXT_MODE=SEQUENTIAL
export BROWSER_CONTEXT_NUMBER=2
export BROWSER_MAX_OPEN_TABS=8
export BROWSER_DISPLAY_MODE=HEADLESS
```

Windows (PowerShell):
```powershell
$env:PROXY_ROTATION_URL = "https://your-proxy-provider.com/rotation-endpoint"
$env:BROWSER_CONTEXT_MODE = "SEQUENTIAL"
$env:BROWSER_CONTEXT_NUMBER = 2
$env:BROWSER_MAX_OPEN_TABS = 8
$env:BROWSER_DISPLAY_MODE = "HEADLESS"
```

---

### ☕ JVM System Properties

Set configuration via command-line JVM arguments (dot-separated, same keys as `application.properties`):

```
-D"openrouter.api.key=sk-yourllmproviderapikey"
-D"browser.profile.mode=SEQUENTIAL"
```

Use quotes around the key to avoid shell interpretation issues on Windows.

---

### 🐳 Docker Configuration

For Docker deployments, pass configuration as environment variables.

**Desktop usage:**

Linux/macOS:
```bash
docker run -d -p 8182:8182 \
  -e OPENROUTER_API_KEY=${OPENROUTER_API_KEY} \
  galaxyeye88/browser4:latest
```

Windows (PowerShell):
```powershell
docker run -d -p 8182:8182 `
  -e OPENROUTER_API_KEY=$env:OPENROUTER_API_KEY `
  galaxyeye88/browser4:latest
```

**Advanced parallel crawling:**

Linux/macOS:
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

Windows (PowerShell):
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

> ⚠️ **Note**: When crawling sites with bot detection, warm up the browser first — visit the home page and browse a few pages before submitting scrape jobs.

---

## ⚙️ Common Configuration Options

### LLM API Keys

Browser4 supports multiple LLM providers. Configure **one** of:

| Property | Env Var | Description |
|---|---|---|
| `openrouter.api.key` | `OPENROUTER_API_KEY` | OpenRouter API key (default provider) |
| `deepseek.api.key` | `DEEPSEEK_API_KEY` | DeepSeek official API key |
| `volcengine.api.key` | `VOLCENGINE_API_KEY` | Volcengine / ByteDance API key |
| `openai.api.key` | `OPENAI_API_KEY` | OpenAI or OpenAI-compatible API key |

Each provider also supports optional `<provider>.model.name` and `<provider>.base.url` properties. See the [LLM Provider Configuration](#llm-provider-configuration) section above for full examples.

### Browser & Server

- **`server.port`** *(default: `8182`)* — Env: `SERVER_PORT`

  HTTP port the Browser4 server listens on.

- **`browser.profile.mode`** (`DEFAULT` | `SYSTEM_DEFAULT` | `PROTOTYPE` | `SEQUENTIAL` | `TEMPORARY`) — Env: `BROWSER_CONTEXT_MODE`

  Defines how the user data directory is assigned for each browser instance.

  - `DEFAULT`: Uses the default Browser4-managed user data directory.
  - `SYSTEM_DEFAULT`: Uses the system's default browser profile (e.g., your personal Chrome/Edge profile). Close the browser before using this mode.
  - `PROTOTYPE` **[Advanced]**: Uses a predefined prototype user data directory. All `SEQUENTIAL` and `TEMPORARY` modes inherit from this prototype.
  - `SEQUENTIAL` **[Advanced]**: Selects a user data directory from a managed pool to enable sequential isolation.
  - `TEMPORARY` **[Advanced]**: Generates a new, isolated user data directory for each browser instance.

- **`browser.display.mode`** (`GUI` | `HEADLESS` | `SUPERVISED`) — Env: `BROWSER_DISPLAY_MODE`

  Controls how the browser is displayed:

  - `GUI`: Launches a visible browser window.
  - `HEADLESS`: Runs without a graphical window.
  - `SUPERVISED`: Linux-only; uses Xvfb for headless GUI simulation.

### Advanced: Parallel Crawling

- **`proxy.rotation.url`** — Env: `PROXY_ROTATION_URL`

  Only applies to `SEQUENTIAL` and `TEMPORARY` profile modes. The URL provided by your proxy service — each access should return one or more fresh proxy IPs. Ask your proxy provider for such a URL.

- **`browser.context.number`** *(default: `2`)* — Env: `BROWSER_CONTEXT_NUMBER`

  Only applies to `SEQUENTIAL` and `TEMPORARY` modes. Number of browser contexts (isolated, incognito-like sessions). Each context has its own cookies, local storage, and cache. For `DEFAULT`, `SYSTEM_DEFAULT`, and `PROTOTYPE` modes, this value is always **1**.

- **`browser.max.active.tabs`** *(default: `8`)* — Env: `BROWSER_MAX_OPEN_TABS`

  Maximum number of tabs per browser instance. For `DEFAULT`, `SYSTEM_DEFAULT`, and `PROTOTYPE` modes, there is **no limit**.

### 📦 `browser.profile.mode` Comparison Table

| Mode           | Description                                                                 | User Data Directory Behavior                             | Use Case            |
|----------------|-----------------------------------------------------------------------------|-----------------------------------------------------------|---------------------|
| `DEFAULT`      | Uses the Browser4-managed default profile.                                 | Shared across sessions (not your system browser).          | General purpose     |
| `SYSTEM_DEFAULT` | Uses the system browser's default profile.                                | Shares your daily-used browser profile.                   | For quick integration or debugging with real session data |
| `PROTOTYPE` ⚠️ | **[Advanced]** Uses a predefined prototype profile.                         | Acts as the base for `SEQUENTIAL` and `TEMPORARY`.        | Controlled state inheritance |
| `SEQUENTIAL` ⚠️ | **[Advanced]** Picks a profile from a pool sequentially.                   | Rotates through a pool of pre-initialized directories.     | Avoid session reuse in batch runs |
| `TEMPORARY` ⚠️  | **[Advanced]** Creates a new, isolated profile for each browser instance. | Discarded after session ends.                             | Maximum isolation / stateless crawling |

---

## 💡 Configuration Best Practices

1. 🔐 Use **environment variables** for credentials or sensitive values.
2. 📁 Use **configuration files** for structured or shared settings.
3. ⚡ Use **system properties** for quick runtime overrides.
4. 📝 Always **document changes** to ensure team transparency.
