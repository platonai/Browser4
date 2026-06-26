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

| Env var | Property | Default |
|---|---|---|
| `VOLCENGINE_API_KEY` | `volcengine.api.key` | — |
