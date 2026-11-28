package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * `${xxx}` 占位符展开器。
 *
 * 展开优先级（从高到低）：
 * 1. [ImmutableConfig.get] — session / launch config 级别覆盖（如 `ego.path`）
 * 2. [System.getProperty] — JVM 系统属性
 * 3. [System.getenv] — 环境变量（Windows 环境变量名大小写不敏感）
 * 4. 原样保留（由调用方处理或报错）
 *
 * 同时支持 `${VAR:-default}` 语法：当前三级都找不到时使用 default 值。
 *
 * 单例，无状态，线程安全。
 */
object PlaceholderExpander {

    /** 匹配 `${name}` 或 `${name:-default}` 的正则 */
    private val PLACEHOLDER_RE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_.]*)(?::-([^}]*))?\}""")

    /**
     * 对 [input] 中的所有 `${xxx}` 占位符做展开。
     * 找不到且无默认值的占位符**保留原样**（方便后续报错或外部处理）。
     */
    fun expand(input: String?, config: ImmutableConfig?): String {
        if (input == null || input.isEmpty()) return input ?: ""
        return PLACEHOLDER_RE.replace(input) { match ->
            val name = match.groupValues[1]
            // 注意：Kotlin MatchResult.groupValues 是 List<String>（非 nullable），
            // 缺失的捕获组是空字符串 ""，不是 null —— 所以要用 takeIf { it.isNotEmpty() }
            val default = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            resolve(name, config) ?: default ?: match.value
        }
    }

    private fun resolve(name: String, config: ImmutableConfig?): String? {
        // 1. ImmutableConfig 级别（session/launch config 覆盖全局）
        config?.get(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // 2. System property
        System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // 3. Environment variable —— 尝试原名和大写名（Windows env vars 大小写不敏感）
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv(name.uppercase())?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        return null
    }

    /**
     * 对逗号分隔的字符串做展开 + trim + 过滤空项，返回 List。
     * 如 `"chrome, google-chrome, ${foo:-edge}"` → `["chrome", "google-chrome", "<foo 的值或 edge>"]`
     */
    fun expandList(input: String?, config: ImmutableConfig?): List<String> {
        if (input == null) return emptyList()
        return input.split(',').map { expand(it.trim(), config) }.filter { it.isNotEmpty() }
    }
}
