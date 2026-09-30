package ai.platon.pulsar.agentic.tools.advanced.agent

/**
 * Detects whether a plain agent command is a pure coding task that needs no
 * browser page.
 *
 * Coding tasks (read/write files, run maven, build plugins) still run through the
 * browser-centric agent loop, which normally navigates a blank driver to a search
 * engine and captures DOM snapshots/screenshots every step. That page work is pure
 * overhead for coding tasks and was the source of the 30s DOM timeouts
 * (`doResolve.cancelled ... Timed out waiting for 30000 ms`) that killed pure coding
 * runs. When [detect] returns true the runner switches the agent into coding mode:
 * driver health check, search-engine navigation and screenshots are all skipped.
 *
 * The detection is deliberately conservative: any URL or explicit page-intent verb
 * wins over coding signals, so browse tasks are never misclassified.
 */
object CodingTaskDetector {

    /** Lowercased substrings that suggest the task operates on repository files/build. */
    private val CODING_SIGNALS = listOf(
        // file extensions
        ".kt", ".java", ".js", ".ts", ".py", ".rs", ".go", ".xml", ".json", ".md", ".yml",
        // repo/build artifacts
        "pom.xml", "build.gradle", "readme", "scaffold", "plugin", "module",
        // actions
        "mvn ", "compile", "rebuild",
        // paths
        "src/", "src\\", "browser4-",
        // Chinese coding verbs (used in task descriptions)
        "读取", "写入", "修改", "编译", "插件", "代码", "文件",
    )

    /** Lowercased substrings that signal a browser/page task — these always win. */
    private val PAGE_SIGNALS = listOf(
        "http://", "https://", "www.",
        "打开网页", "打开页面", "打开网站", "浏览网页", "浏览页面",
        "点击", "填写", "登录", "搜索框",
    )

    /**
     * Returns true when the command looks like a pure coding task:
     * no URL, no page-intent verbs, and at least one coding signal.
     */
    fun detect(command: String): Boolean {
        val c = command.lowercase().trim()
        if (c.isEmpty()) return false
        if (PAGE_SIGNALS.any { c.contains(it) }) return false
        return CODING_SIGNALS.any { c.contains(it) }
    }
}
