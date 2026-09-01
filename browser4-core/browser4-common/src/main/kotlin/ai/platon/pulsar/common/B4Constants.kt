package ai.platon.pulsar.common

object B4Constants {
    /**
     * The mode of browser profile, case-insensitive.
     * default, system_default, prototype, sequential, temporary
     *
     * A replacement to BROWSER_CONTEXT_MODE
     */
    const val BROWSER_PROFILE_MODE = "browser.profile.mode"


    const val SESSION_ID_CAPABILITY = "sessionId"
    const val PROFILE_MODE_CAPABILITY = "profileMode"
    const val PROFILE_PATH_CAPABILITY = "profilePath"

    /**
     * The session-level browser profile path, set from the `profilePath`
     * capability (e.g. `open --profile <path>`). When present, the session's
     * browser launches with this directory as the Chrome user data dir
     * instead of a Browser4-managed profile directory.
     */
    const val BROWSER_PROFILE_PATH = "browser.profile.path"

    /**
     * The session-level browser context directory capability. Set by the
     * backend for CLI named sessions so the same session always binds the
     * same dedicated chrome user data dir instead of rotating through the
     * SEQUENTIAL pool on every launch.
     */
    const val CONTEXT_DIR_CAPABILITY = "contextDir"

    /**
     * The session-level browser context directory, set from the `contextDir`
     * capability. When present, the session's browser launches with a
     * Browser4-managed context directory dedicated to this session
     * (e.g. .../context/groups/named/PULSAR_CHROME/cx.<sessionUuid>).
     */
    const val BROWSER_CONTEXT_DIR = "browser.context.dir"

    /**
     * The REST level session id - DEFAULT
     * */
    const val DEFAULT_SESSION_ID = "DEFAULT"
    /**
     * The REST level session id - SWARM
     * */
    const val SWARM_SESSION_ID = "SWARM"
    /**
     * The SWARM session label
     * */
    const val SWARM_SESSION_LABEL = "SWARM"

    const val VAR_IS_SCRAPE = "IS_SCRAPE"

    const val BROWSER4_CONTEXT_CONFIG_LOCATION = "classpath:browser4-beans/app-context.xml"
}
