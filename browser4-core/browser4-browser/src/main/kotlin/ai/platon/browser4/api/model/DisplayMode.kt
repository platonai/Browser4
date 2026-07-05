package ai.platon.pulsar.browser.common

/**
 * The browser display mode.
 *
 * Three display modes are supported:
 * 1. GUI: open as a normal browser
 * 2. HEADLESS: open in headless mode
 * 3. SUPERVISED: supervised by other programs
 * */
enum class DisplayMode {
    SUPERVISED, GUI, HEADLESS;

    companion object {
        @JvmStatic
        fun fromString(name: String?): DisplayMode {
            return if (name.isNullOrEmpty()) {
                GUI
            } else try {
                valueOf(name.uppercase())
            } catch (e: Throwable) {
                GUI
            }
        }
    }
}
