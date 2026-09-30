package ai.platon.pulsar.coding

/**
 * Parser for the on-disk `ModuleMap.kt` static snapshot.
 *
 * `validate repo-consistency` compares the static ModuleMap snapshot against
 * the live pom graph. The loaded [ModuleMap] class can be STALE (it comes from
 * the running backend's build, which may predate ModuleMap.kt edits — e.g.
 * right after `scaffoldToDir` synced a new module). Parsing the source file
 * instead anchors the comparison to what will actually be compiled next.
 *
 * Pure string parsing of the generated file format — no Kotlin compiler, no
 * I/O. Returns null when the file does not match the expected shape (callers
 * then fall back to the loaded class and warn).
 */
object ModuleMapSource {

    /** The parsed static snapshot. */
    data class Parsed(
        val modules: List<String>,
        val dependents: Map<String, List<String>>,
    )

    private val QUOTED = Regex("\"([^\"]+)\"")
    private val DEPENDENTS_KEY = Regex("\"([^\"]+)\"\\s+to listOf\\(")
    private const val MODULES_MARKER = "val MODULES: List<String> = listOf("
    private const val DEPENDENTS_MARKER = "val DEPENDENTS: Map<String, List<String>> = mapOf("

    /**
     * Parse [source] (the text of ModuleMap.kt) into a [Parsed] snapshot.
     *
     * @return the parsed snapshot, or null when the expected structure
     *   (MODULES block + DEPENDENTS block ending with `\n    )`) is absent
     */
    fun parse(source: String): Parsed? {
        val modulesBlock = extractBlock(source, MODULES_MARKER) ?: return null
        val dependentsBlock = extractBlock(source, DEPENDENTS_MARKER) ?: return null

        val modules = quotedStrings(modulesBlock)
        if (modules.isEmpty()) return null

        val dependents = linkedMapOf<String, List<String>>()
        val keyRanges = DEPENDENTS_KEY.findAll(dependentsBlock).map { it.range.first }.toMutableList()
        keyRanges += dependentsBlock.length
        for (i in 0 until keyRanges.size - 1) {
            val segment = dependentsBlock.substring(keyRanges[i], keyRanges[i + 1])
            val keyMatch = DEPENDENTS_KEY.find(segment) ?: continue
            val values = quotedStrings(segment.substringAfter("listOf("))
            dependents[keyMatch.groupValues[1]] = values
        }
        if (dependents.isEmpty()) return null

        return Parsed(modules, dependents)
    }

    // -- internal ------------------------------------------------------------

    /** Extract the block between [marker] and the closing `\n    )` that ends the list. */
    private fun extractBlock(source: String, marker: String): String? {
        val start = source.indexOf(marker)
        if (start < 0) return null
        val blockStart = start + marker.length
        val close = source.indexOf("\n    )", blockStart)
        if (close < 0) return null
        return source.substring(blockStart, close)
    }

    private fun quotedStrings(block: String): List<String> =
        QUOTED.findAll(block).map { it.groupValues[1] }.toList()
}
