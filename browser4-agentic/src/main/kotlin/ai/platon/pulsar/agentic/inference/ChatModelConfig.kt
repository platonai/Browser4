package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig

/** PulsarRPA `ChatModelSettings` key for the per-request input length cap. */
const val LLM_MAX_INPUT_TOKEN_LENGTH_KEY = "llm.max.input.token.length"

/**
 * Forced per-request LLM input cap.
 *
 * PulsarRPA's `ChatModelSettings` truncates user messages above
 * `llm.max.input.token.length` and defaults to 64_000 — a conservative floor
 * chosen for legacy 64K-context models. Browser4 pins `deepseek-v4-flash`,
 * whose context window is 1M tokens, so the cap is raised in code here and can
 * no longer silently fall back to 64K when the key is absent from config.
 */
const val LLM_MAX_INPUT_TOKEN_LENGTH = 900_000

/**
 * Returns [conf] with [LLM_MAX_INPUT_TOKEN_LENGTH_KEY] force-set to [limit].
 *
 * The chat model is created from the returned config,
 * so every agent LLM call honors the raised cap regardless of how the backend
 * was configured or started.
 */
fun ImmutableConfig.forceLlmMaxInputTokenLength(limit: Int = LLM_MAX_INPUT_TOKEN_LENGTH): ImmutableConfig =
    toMutableConfig().apply { set(LLM_MAX_INPUT_TOKEN_LENGTH_KEY, limit.toString()) }
