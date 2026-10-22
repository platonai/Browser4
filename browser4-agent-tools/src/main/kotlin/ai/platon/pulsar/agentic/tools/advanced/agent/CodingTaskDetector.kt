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
 * The detection is deliberately conservative: a URL or an explicit navigation verb
 * always wins, so browse tasks are never misclassified. See [detect] for the exact
 * precedence.
 */
object CodingTaskDetector {

    /** Lowercased substrings that suggest the task operates on repository files/build. */
    private val CODING_SIGNALS = listOf(
        // file extensions
        ".kt", ".java", ".js", ".ts", ".py", ".rs", ".go", ".xml", ".json", ".md", ".yml",
        // repo/build artifacts
        "pom.xml", "build.gradle", "readme", "scaffold", "plugin", "module",
        // build/verification actions
        "mvn ", "compile", "rebuild",
        // paths
        "src/", "src\\", "browser4-",
        // Chinese coding verbs (used in task descriptions)
        "读取", "写入", "修改", "编译", "插件", "代码", "文件",
        // Refactoring / engineering verbs. A bare class name ("重构 Browser4WebDriver")
        // carries none of the file-path signals above, and misclassifying it exposes the
        // browsing tool profile: the model then burns its first rounds on `b4.run`,
        // `system.listTools`/`exposeTools` and `.browser4` snapshots instead of touching
        // the file (observed: `cli-agent tools exposed: … profile=browsing`).
        "重构", "重写", "改写", "实现", "修复", "优化", "迁移", "改造", "拆分", "抽取",
        "单测", "单元测试", "测试用例", "回归测试",
        "refactor", "rewrite", "implement", "fix ", "migrate", "rename", "dedupe", "deduplicate",
        "unit test", "test case",
    )

    /** Explicit navigation intent (`open the page`, `browse the site`) — wins over coding. */
    private val PAGE_NAV_SIGNALS = listOf(
        "打开网页", "打开页面", "打开网站", "浏览网页", "浏览页面",
    )

    /**
     * In-page interaction verbs. They do **not** outrank a coding verb: "重构登录流程"
     * is a refactor while "点击登录按钮" is a browse task — the verb decides.
     */
    private val PAGE_SIGNALS = listOf("点击", "填写", "登录", "搜索框")

    /** Lowercased substrings that name a page by address — these always win. */
    private val URL_SIGNALS = listOf("http://", "https://", "www.")

    /** Dotted type names (`ai.platon.pulsar.chrome.Browser4WebDriver`). */
    private val QUALIFIED_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*){2,}""")

    /** Candidate CamelCase words — at least two humps are needed to look like a symbol. */
    private val CODE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]{5,}""")

    /**
     * Endings that only a source symbol carries. Deliberately *not* generic nouns
     * (`Page`, `Module`, `Plugin`, `Service`, `Manager`, `Client`, `Test`): the bare
     * identifier path must not turn prose ("Open the LoginPage") into a coding task.
     * Tasks that also contain a coding verb are already covered by [CODING_SIGNALS].
     */
    private val STRONG_CODE_SUFFIXES = listOf(
        "Driver", "Executor", "Controller", "Builder", "Factory", "Handler", "Provider",
        "Validator", "Exception", "Extension", "Extensions", "Listener", "Parser", "Guard",
        "Configuration", "Repository", "Mapper", "Adapter", "Strategy", "Registry", "Impl",
    )

    /**
     * Returns true when the command looks like a pure coding task.
     *
     * Precedence, most specific first:
     * 1. a URL, or explicit navigation intent (`打开页面` / `浏览网页`), is a page task;
     * 2. an explicit build/file/refactor signal is coding — this is what catches a bare
     *    class name ("重构 Browser4WebDriver"), which carries no file path at all;
     * 3. an in-page interaction verb alone (`点击`, `填写`, `登录`) is browsing, so
     *    "打开网页并点击登录按钮" stays a browse task;
     * 4. otherwise, a named source symbol is coding.
     */
    fun detect(command: String): Boolean {
        val c = command.lowercase().trim()
        if (c.isEmpty()) return false
        if (URL_SIGNALS.any { c.contains(it) }) return false
        if (PAGE_NAV_SIGNALS.any { c.contains(it) }) return false
        if (CODING_SIGNALS.any { c.contains(it) }) return true
        if (PAGE_SIGNALS.any { c.contains(it) }) return false
        return namesASourceSymbol(command)
    }

    /**
     * True when [text] names a source symbol: a qualified name
     * (`ai.platon.pulsar.chrome.Browser4WebDriver`), or a CamelCase token (two or
     * more capitals) ending in one of [STRONG_CODE_SUFFIXES] — `Browser4WebDriver`
     * ends in `Driver`, so "重构 Browser4WebDriver" is recognised even without a
     * file path.
     */
    fun namesASourceSymbol(text: String): Boolean {
        if (QUALIFIED_NAME.containsMatchIn(text)) return true
        return CODE_IDENTIFIER.findAll(text).any { m ->
            val token = m.value
            token.count { it.isUpperCase() } >= 2 && STRONG_CODE_SUFFIXES.any { token.endsWith(it) }
        }
    }
}
