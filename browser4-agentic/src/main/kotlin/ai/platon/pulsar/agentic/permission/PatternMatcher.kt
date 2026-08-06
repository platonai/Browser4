package ai.platon.pulsar.agentic.permission

// Matches resource values (commands, paths, URLs, scripts) against permission rule patterns.
// EXACT: literal equality (case-insensitive for COMMAND). GLOB: anchored, * single-segment,
// ** any depth, ? single char. REGEX: full-string match. Windows backslashes normalized to /.
object PatternMatcher {

    // Returns true when value matches pattern according to patternType and resourceType.
    fun matches(
        pattern: String,
        value: String,
        patternType: PatternType,
        resourceType: ResourceType,
    ): Boolean {
        return when (patternType) {
            PatternType.EXACT -> matchesExact(pattern, value, resourceType)
            PatternType.GLOB -> matchesGlob(pattern, value, resourceType)
            PatternType.REGEX -> matchesRegex(pattern, value)
        }
    }

    // ---------- exact ----------

    private fun matchesExact(pattern: String, value: String, resourceType: ResourceType): Boolean {
        return when (resourceType) {
            ResourceType.COMMAND -> pattern.equals(value, ignoreCase = true)
            else -> pattern == value
        }
    }

    // ---------- glob ----------

    private fun matchesGlob(pattern: String, value: String, resourceType: ResourceType): Boolean {
        val normalizedPattern = normalizeForGlob(pattern, resourceType)
        val normalizedValue = normalizeForGlob(value, resourceType)

        // Fast path: no wildcards → exact (case-insensitive for commands)
        if (!containsWildcards(normalizedPattern)) {
            return if (resourceType == ResourceType.COMMAND) {
                normalizedPattern.equals(normalizedValue, ignoreCase = true)
            } else {
                normalizedPattern == normalizedValue
            }
        }

        val regex = globToRegex(normalizedPattern)
        return regex.matches(normalizedValue)
    }

    // Converts a glob pattern to an anchored Regex.
    // ** matches across path segments, * matches within a single segment,
    // ? matches a single non-separator char. Regex-special chars are escaped.
    // When ** is immediately followed by /, the pair becomes (?:.*/)? so that
    // ** can match zero path segments (the slash is optional).
    internal fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        sb.append("^")
        var i = 0
        while (i < glob.length) {
            when {
                // ** followed by /  → optional-any-path + slash
                i + 2 < glob.length && glob[i] == '*' && glob[i + 1] == '*' && glob[i + 2] == '/' -> {
                    sb.append("(?:.*/)?")
                    i += 3
                }
                // ** at end or followed by non-/  → greedy any (including /)
                i + 1 < glob.length && glob[i] == '*' && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                }
                glob[i] == '*' -> {
                    sb.append("[^/]*?")
                    i++
                }
                glob[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                // Escape regex-special characters
                glob[i] in REGEX_SPECIAL -> {
                    sb.append('\\').append(glob[i])
                    i++
                }
                else -> {
                    sb.append(glob[i])
                    i++
                }
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    // ---------- regex ----------

    private fun matchesRegex(pattern: String, value: String): Boolean {
        return try {
            Regex(pattern).matches(value)
        } catch (_: Exception) {
            false
        }
    }

    // ---------- normalization ----------

    // Normalize: backslashes to forward slashes for PATH/URL, lowercase for COMMAND.
    private fun normalizeForGlob(input: String, resourceType: ResourceType): String {
        var s = input
        if (resourceType == ResourceType.PATH || resourceType == ResourceType.URL) {
            s = s.replace('\\', '/')
        }
        if (resourceType == ResourceType.COMMAND) {
            s = s.lowercase()
        }
        return s
    }

    private fun containsWildcards(s: String): Boolean {
        return s.contains('*') || s.contains('?')
    }

    private val REGEX_SPECIAL = setOf('.', '+', '^', '$', '(', ')', '[', ']', '{', '}', '|', '\\')
}
