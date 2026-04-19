package ai.platon.browser4.driver.chrome.dom.model

/**
 * Parses a viewport specification string into a list of 1-based viewport indices.
 *
 * Supported formats:
 * - `"all"` or `""` or `null` — returns `null` to indicate "no filtering" (full page).
 * - `"3"` — single viewport.
 * - `"1,3,5"` — comma-separated list.
 * - `"2-4"` — inclusive range (expands to 2, 3, 4).
 * - `"1,3-5,8"` — mix of individual indices and ranges.
 *
 * Invalid tokens are silently ignored. Indices less than 1 are clamped to 1.
 */
object ViewportSpec {
    /**
     * Parses a viewport specification string.
     *
     * @param spec The viewport specification string (e.g., `"3"`, `"1,3,5"`, `"2-4"`, `"all"`).
     * @return A sorted, deduplicated list of 1-based viewport indices, or `null` if the spec means "all viewports".
     */
    fun parse(spec: String?): List<Int>? {
        if (spec.isNullOrBlank() || spec.trim().equals("all", ignoreCase = true)) {
            return null
        }

        val indices = mutableSetOf<Int>()
        for (token in spec.split(",")) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("-")) {
                val parts = trimmed.split("-", limit = 2)
                val start = parts[0].trim().toIntOrNull() ?: continue
                val end = parts[1].trim().toIntOrNull() ?: continue
                val lo = start.coerceAtLeast(1)
                val hi = end.coerceAtLeast(1)
                if (lo <= hi) {
                    for (i in lo..hi) indices.add(i)
                }
            } else {
                val idx = trimmed.toIntOrNull() ?: continue
                indices.add(idx.coerceAtLeast(1))
            }
        }

        return if (indices.isEmpty()) null else indices.sorted()
    }
}
