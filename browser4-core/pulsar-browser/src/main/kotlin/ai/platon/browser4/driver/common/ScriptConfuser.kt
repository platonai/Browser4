package ai.platon.browser4.driver.common

interface ScriptConfuser {
    /**
     * Confuse the script.
     * */
    fun confuse(script: String): String
    /**
     * Reset the confuser to the initial state.
     * */
    fun reset()
    /**
     * Clear the confuser, so no confuse will be done.
     * */
    fun clear()
}
