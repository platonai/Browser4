package ai.platon.pulsar.common

import ai.platon.pulsar.common.config.CapabilityTypes.*
import org.apache.commons.lang3.SystemUtils
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicReference

object AppContext {

    enum class State {
        NEW, RUNNING, TERMINATING, TERMINATED
    }

    /**
     * The application is production environment
     * */
    val isProd get() = System.getenv("ENV").equals("prod", true)

    val isCN get() = Locale.getDefault().language.equals("zh", ignoreCase = true)

    /**
     * The node id in the cluster.
     * */
    val nodeId: Long = System.getProperty(APP_NODE_ID)?.toLongOrNull()
        ?: System.getenv(APP_NODE_ID)?.toLongOrNull()
        ?: 0L

    /**
     * The number of processors available to the Java virtual machine
     */
    val NCPU get() = Runtime.getRuntime().availableProcessors()

    /**
     * Gets the host name for this IP address.
     * If this InetAddress was created with a host name, this host name will be remembered and returned;
     * otherwise, a reverse name lookup will be performed and the result will be returned based on the
     * system configured name lookup service.
     * */
    val HOST_NAME: String get() = InetAddress.getLocalHost().hostName

    /**
     * The name of the current user
     * */
    val USER_NAME: String get() = SystemUtils.USER_NAME

    /**
     * The java.io.tmpdir System Property. Default temp file path.
     * Defaults to null if the runtime does not have security access to read this property or the property does not exist.
     * This value is initialized when the class is loaded.
     */
    val TMP_DIR: String get() = SystemUtils.JAVA_IO_TMPDIR

    /**
     * User's current working directory
     * */
    val USER_DIR: String get() = SystemUtils.USER_DIR

    /**
     * User's home directory
     * */
    val USER_HOME: String get() = SystemUtils.USER_HOME

    /**
     * The application version
     * */
    val APP_VERSION_RT get() = sniffVersion()
    /**
     * The application version
     * */
    val APP_VERSION by lazy { sniffVersion() }
    /**
     * The real time application name, can be specified by system environment variable or system property.
     * The real time version is used for test only.
     * */
    val APP_NAME_RT get() = System.getenv(APP_NAME_KEY) ?: System.getProperty(APP_NAME_KEY, "browser4")
    /**
     * The application name, can be specified by system environment variable or system property.
     * */
    val APP_NAME = APP_NAME_RT
    /**
     * The real time application identity string, can be specified by system environment variable or system property.
     * The real time version is used for test only.
     *
     * The default value is the current username.
     * */
    val APP_IDENT_RT get() = System.getenv(APP_ID_KEY) ?: System.getProperty(APP_ID_KEY, USER_NAME)
    /**
     * The application identity string, can be specified by system environment variable or system property.
     * */
    val APP_IDENT = APP_IDENT_RT
    /**
     * The real time user specified temp dir used by the application, can be specified by system environment
     * variable or system property.
     * The real time version is used for test only.
     * */
    val APP_TMP_BASE_SPECIFIED_RT get() = System.getenv(APP_TMP_BASE_DIR_KEY) ?: System.getProperty(APP_TMP_BASE_DIR_KEY)
    /**
     * The real time temp directory used by all processes.
     * The real time version is used for test only.
     * */
    val APP_TMP_DIR_RT get() = when {
        APP_TMP_BASE_SPECIFIED_RT != null -> Paths.get(APP_TMP_BASE_SPECIFIED_RT).resolve(APP_NAME_RT)
        else -> Paths.get(TMP_DIR).resolve(APP_NAME_RT)
    }
    /**
     * The temp directory used by all processes
     * */
    val APP_TMP_DIR = APP_TMP_DIR_RT
    /**
     * The real time temp directory used by processes with APP_IDENT
     * The real time version is used for test only.
     * */
    val APP_PROC_TMP_DIR_RT get() =
        APP_TMP_DIR_RT.resolveSibling("$APP_NAME_RT-$APP_IDENT_RT")
    /**
     * The temp directory used by processes with APP_IDENT
     * */
    val APP_PROC_TMP_DIR = APP_PROC_TMP_DIR_RT
    /**
     * The real time user specified data dir used by the application, can be specified by system environment
     * */
    val APP_DATA_DIR_SPECIFIED_RT get() = System.getenv(APP_DATA_DIR_KEY) ?: System.getProperty(APP_DATA_DIR_KEY)
    /**
     * The user specified data dir used by the application, can be specified by system environment
     * */
    val APP_DATA_DIR_SPECIFIED = APP_DATA_DIR_SPECIFIED_RT
    /**
     * The data directory used by the application, the default data dir is $HOME/.pulsar.
     * Special users such as tomcat do not have its own home, $TMP_DIR/.$APP_NAME is used in such case.
     * */
    val APP_DATA_DIR_RT get() = when {
        APP_DATA_DIR_SPECIFIED_RT != null -> Paths.get(APP_DATA_DIR_SPECIFIED_RT)
        else -> listOf(USER_HOME, TMP_DIR).map { Paths.get(it) }
            .first { Files.isWritable(it) }.resolve(".$APP_NAME_RT")
    }
    val APP_DATA_DIR = APP_DATA_DIR_RT

    /**
     * The application's runtime state
     * */
    val state = AtomicReference(State.NEW)
    /**
     * The application is active, it can serve and can be terminated.
     * TODO: consider a more flexible way to manage the state.
     * */
    val isActive get() = state.get().ordinal < State.TERMINATING.ordinal
    /**
     * The application is inactive, it can not serve, it's terminating, or terminated.
     * */
    val isInactive get() = !isActive
    /**
     * Start the application.
     * */
    fun start() = state.set(State.RUNNING)

    fun shouldTerminate() {
        if (state.get() != State.TERMINATED) {
            state.set(State.TERMINATING)
        }
    }

    fun terminate() {
        if (state.get() == State.TERMINATED) {
            return
        }
        state.set(State.TERMINATING)
    }

    fun endTermination() = state.set(State.TERMINATED)

    private fun sniffVersion(): String {
        var version = System.getProperty("app.version")
        if (version == null) {
            version = Paths.get(USER_DIR).resolve("VERSION").takeIf { Files.exists(it) }
                ?.let { Files.readAllLines(it).firstOrNull() }
        }
        return version ?: "unknown"
    }
}
