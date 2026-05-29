package ai.platon.browser4.common

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

    const val BROWSER4_CONTEXT_CONFIG_LOCATION = "classpath*:/browser4-beans/app-context.xml"
}
