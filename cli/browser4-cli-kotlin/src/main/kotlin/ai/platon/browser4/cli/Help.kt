package ai.platon.browser4.cli

/**
 * Generates the global help text listing all non-hidden commands grouped by category.
 */
fun generateHelp(): String = buildString {
    appendLine("Usage: browser4-cli <command> [args] [options]")
    appendLine("Usage: browser4-cli -s=<session> <command> [args] [options]")

    val cmds = allCommands().filter { !it.hidden }
    val catOrder = linkedMapOf(
        "Core" to "core",
        "Navigation" to "navigation",
        "Keyboard" to "keyboard",
        "Mouse" to "mouse",
        "Export" to "export",
        "Tabs" to "tabs",
        "Storage" to "storage",
        "Browser sessions" to "browsers",
        "Agent" to "agent",
    )

    for ((catTitle, catName) in catOrder) {
        val catCmds = cmds.filter { it.category.name.lowercase() == catName }
        if (catCmds.isEmpty()) continue
        appendLine()
        appendLine("$catTitle:")
        catCmds.forEach { appendLine(helpEntry(it)) }
    }

    appendLine()
    appendLine("Global options:")
    appendLine(formatWithGap("  --help [command]", "print help", GAP_THRESHOLD))
    appendLine(formatWithGap("  --version", "print version", GAP_THRESHOLD))
    appendLine(formatWithGap("  --json", "emit machine-parseable JSON", GAP_THRESHOLD))
    appendLine(formatWithGap("  -q, --quiet", "suppress normal output", GAP_THRESHOLD))
    appendLine(formatWithGap("  -s=<name>", "named session label", GAP_THRESHOLD))
    appendLine(formatWithGap("  --server=<url>", "override server URL", GAP_THRESHOLD))
}

/**
 * Generates detailed help for a single command.
 */
fun generateCommandHelp(cmd: CommandDef): String = buildString {
    val argsText = cmd.args.joinToString(" ") { a ->
        if (a.optional) "[${a.name}]" else "<${a.name}>"
    }
    appendLine("browser4-cli ${cmd.name} $argsText".trimEnd())
    appendLine()
    appendLine(cmd.description)

    if (cmd.args.isNotEmpty()) {
        appendLine()
        appendLine("Arguments:")
        cmd.args.forEach { a ->
            val label = if (a.optional) "  [${a.name}]" else "  <${a.name}>"
            appendLine(formatWithGap(label, a.description, GAP_THRESHOLD))
        }
    }

    if (cmd.options.isNotEmpty()) {
        appendLine()
        appendLine("Options:")
        cmd.options.forEach { o ->
            appendLine(formatWithGap("  --${o.name}", o.description, GAP_THRESHOLD))
        }
    }
}

// ---- private helpers ----

private const val GAP_THRESHOLD = 30

private fun helpEntry(cmd: CommandDef): String {
    val argsText = cmd.args.joinToString(" ") { a ->
        if (a.optional) "[${a.name}]" else "<${a.name}>"
    }
    val prefix = "  ${cmd.name} $argsText".trimEnd()
    return formatWithGap(prefix, cmd.description, GAP_THRESHOLD)
}

private fun formatWithGap(prefix: String, text: String, threshold: Int): String {
    val gap = if (prefix.length < threshold) threshold - prefix.length else 1
    return "$prefix${" ".repeat(gap)}$text"
}
