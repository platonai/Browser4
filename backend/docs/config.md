# 🛠️ Browser4 Configuration Guide

## 📋 Configuration Sources

Browser4 supports multiple configuration sources in order of precedence:

1. 🔧 **Environment Variables**
2. ⚙️ **JVM System Properties**
3. 📝 **Spring Boot `application.properties`**

---

## 🔧 Configuration Methods

### 📝 Spring Boot Configuration Files

Browser4 supports Spring Boot-style configuration files.

A sample `application.properties` is located at the project root. For privacy, recommend to renaming it to `application-private.properties`.

#### For desktop usage:

```properties
# browser.context.mode=SYSTEM_DEFAULT # Optional: use your system's default browser profile
openrouter.api.key=
```

#### [**Advanced**] For high-performance, parallel crawling:
```properties
proxy.rotation.url=https://your-proxy-provider.com/rotation-endpoint
browser.context.mode=SEQUENTIAL
browser.context.number=2
browser.max.active.tabs=8
browser.display.mode=HEADLESS
```

---

### 🌍 Environment Variables / JVM System Properties

You can configure Browser4 using either OS environment variables or JVM system properties.

#### 💻 Example - OS environment variables

For standard desktop usage:

Linux/MacOS
```bash
export OPENROUTER_API_KEY=sk-yourllmproviderapikey
```

Windows (PowerShell)
```powershell
$env:OPENROUTER_API_KEY = "sk-yourllmproviderapikey"
```

If you want to use your daily used browser profile (remember closed the browser first):

Linux/MacOS
```bash
export BROWSER_CONTEXT_MODE=SYSTEM_DEFAULT
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

- **`browser.context.mode`** (`DEFAULT` | `SYSTEM_DEFAULT` | `PROTOTYPE` | `SEQUENTIAL` | `TEMPORARY`)
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

### 📦 `browser.context.mode` Comparison Table

| Mode           | Description                                                                 | User Data Directory Behavior                             | Use Case            |
|----------------|-----------------------------------------------------------------------------|-----------------------------------------------------------|---------------------|
| `DEFAULT`      | Uses the Browser4-managed default profile.                                 | Shared across Pulsar sessions (not your system browser).  | General purpose     |
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
