package ai.platon.browser4.api.snapshot

/**
 * Parses a viewport specification string into a list of 0-based viewport indices.
 *
 * Supported formats:
 * - `"all"` or `""` or `null` — returns `null` to indicate "no filtering" (full page).
 * - `"3"` — single viewport.
 * - `"0,2,4"` — comma-separated list.
 * - `"1-3"` — inclusive range (expands to 1, 2, 3).
 * - `"0,2-4,7"` — mix of individual indices and ranges.
 *
 * Invalid tokens are silently ignored. Indices less than 0 are clamped to 0.
 */
object ViewportSpec {
    /**
     * Parses a viewport specification string.
     *
     * @param spec The viewport specification string (e.g., `"3"`, `"0,2,4"`, `"1-3"`, `"all"`).
     * @return A sorted, deduplicated list of 0-based viewport indices, or `null` if the spec means "all viewports".
     */
    fun parse(spec: String?): List<Int>? {
        if (spec.isNullOrBlank() || spec.trim().equals("all", ignoreCase = true)) {
            return null
        }

        val indices = mutableSetOf<Int>()
        for (token in spec.split(",")) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue

            // Try as a single integer first (handles negative numbers like "-1")
            val singleIdx = trimmed.toIntOrNull()
            if (singleIdx != null) {
                indices.add(singleIdx)  // Negative allowed: scroll-relative offset (e.g. -1 = above current viewport)
            } else if (trimmed.contains("-")) {
                val parts = trimmed.split("-", limit = 2)
                val start = parts[0].trim().toIntOrNull() ?: continue
                val end = parts[1].trim().toIntOrNull() ?: continue
                val lo = start.coerceAtLeast(0)
                val hi = end.coerceAtLeast(0)
                if (lo <= hi) {
                    for (i in lo..hi) indices.add(i)
                }
            }
        }

        return if (indices.isEmpty()) null else indices.sorted()
    }
}
