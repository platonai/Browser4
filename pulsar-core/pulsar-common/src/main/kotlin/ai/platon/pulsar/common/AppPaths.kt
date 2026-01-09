package ai.platon.pulsar.common

import ai.platon.pulsar.common.DateTimes.PATH_SAFE_FORMATTER_11
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.common.urls.URLUtils
import com.google.common.net.InternetDomainName
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.RandomStringUtils
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class RequiredFile

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class RequiredDirectory

/**
 * Created by vincent on 18-3-23.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
object AppPaths {

    private val CACHE = ConcurrentExpiringLRUCache<String, Any>()

    val SYS_TMP_DIR = Paths.get(AppContext.TMP_DIR)
    val SYS_USER_DIR = Paths.get(AppContext.USER_DIR)
    val SYS_USER_HOME = Paths.get(AppContext.USER_HOME)

    /**
     * The directory for the user's default browser.
     *
     * The placeholder directory for the user's default browser. This is a placeholder, actually no data dir
     * should be specified, so the browser driver opens a browser just like a normal user opens it.
     * The actual data dir of user's browser are different on different operating systems, for example,
     * on linux, chrome's data dir is: ~/.config/google-chrome/
     */
    val SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER = SYS_TMP_DIR.resolve(".SYSTEM_DEFAULT_DATA_DIR_PLACEHOLDER")
    val SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER = SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER

    // Directory for symbolic links, this path should be as short as possible
    @RequiredDirectory
    val SYS_TMP_LINKS_DIR = SYS_TMP_DIR.resolve("ln")

    @RequiredDirectory
    val DATA_DIR = AppContext.APP_DATA_DIR

    @RequiredDirectory
    val CONFIG_DIR = AppContext.APP_DATA_DIR.resolve("config")

    @RequiredDirectory
    val CONFIG_AVAILABLE_DIR = CONFIG_DIR.resolve("conf-available")

    @RequiredDirectory
    val CONFIG_ENABLED_DIR = CONFIG_DIR.resolve("conf-enabled")

    @RequiredDirectory
    val BROWSER_DATA_DIR = DATA_DIR.resolve("browser")

    @RequiredDirectory
    val CHROME_DATA_DIR_PROTOTYPE = BROWSER_DATA_DIR.resolve("chrome/prototype/google-chrome")

    @RequiredDirectory
    val CONTEXT_DEFAULT_DIR = BROWSER_DATA_DIR.resolve("chrome/default")

    @RequiredDirectory
    val LOCAL_DATA_DIR = DATA_DIR.resolve("data")

    @RequiredDirectory
    val LOCAL_STORAGE_DIR = LOCAL_DATA_DIR.resolve("store")

    @RequiredDirectory
    val LOCAL_TEST_DATA_DIR = LOCAL_DATA_DIR.resolve("test")

    @RequiredDirectory
    val LOCAL_TEST_WEB_PAGE_DIR = LOCAL_TEST_DATA_DIR.resolve("web")

    @RequiredDirectory
    val TMP_DIR = AppContext.APP_TMP_DIR

    @RequiredDirectory
    val PROC_TMP_DIR = AppContext.APP_PROC_TMP_DIR

    @RequiredDirectory
    val PROC_TMP_TMP_DIR = PROC_TMP_DIR.resolve("tmp")

    @RequiredDirectory
    val CACHE_DIR = PROC_TMP_DIR.resolve("cache")

    @RequiredDirectory
    val WEB_CACHE_DIR = CACHE_DIR.resolve("web")

    @RequiredDirectory
    val DOC_EXPORT_DIR = WEB_CACHE_DIR.resolve("export")

    @RequiredDirectory
    val WEB_SCREENSHOT_DIR = WEB_CACHE_DIR.resolve("screenshot")

    @RequiredDirectory
    val FILE_CACHE_DIR = CACHE_DIR.resolve("files")

    @RequiredDirectory
    val PROMPT_CACHE_DIR = CACHE_DIR.resolve("prompts")

    @RequiredDirectory
    val REPORT_DIR = PROC_TMP_DIR.resolve("report")

    @RequiredDirectory
    val METRICS_DIR = REPORT_DIR.resolve("metrics")

    @RequiredDirectory
    val SCRIPT_DIR = PROC_TMP_DIR.resolve("scripts")

    @RequiredDirectory
    val TEST_DIR = PROC_TMP_DIR.resolve("test")

    @RequiredDirectory
    val CONTEXT_BASE_DIR = PROC_TMP_DIR.resolve("context")

    @RequiredDirectory
    val CONTEXT_GROUP_BASE_DIR = CONTEXT_BASE_DIR.resolve("groups")

    @RequiredDirectory
    val CONTEXT_DEFAULT_GROUP_DIR = CONTEXT_GROUP_BASE_DIR.resolve("default")

    @RequiredDirectory
    val CONTEXT_TMP_DIR = CONTEXT_BASE_DIR.resolve("tmp")

    @RequiredDirectory
    val CONTEXT_TMP_GROUP_BASE_DIR = CONTEXT_TMP_DIR.resolve("groups")

    @RequiredDirectory
    val CONTEXT_TMP_DEFAULT_GROUP_DIR = CONTEXT_TMP_GROUP_BASE_DIR.resolve("default")

    /**
     * Proxy directory
     * */
    @RequiredDirectory
    val PROXY_BASE_DIR = DATA_DIR.resolve("proxy")

    @RequiredDirectory
    val PROXY_ARCHIVE_DIR = PROXY_BASE_DIR.resolve("proxies-archived")

    @RequiredFile
    val PATH_LOCAL_COMMAND = TMP_DIR.resolve("pulsar-commands")

    @RequiredFile
    val PATH_UNREACHABLE_HOSTS = REPORT_DIR.resolve("unreachable-hosts.txt")

    private val tmpDirStr get() = TMP_DIR.toString()
    private val procTmpDirStr get() = PROC_TMP_DIR.toString()
    private val homeDirStr get() = DATA_DIR.toString()

    init {
        AppPaths::class.java.declaredFields
            .filter { it.annotations.any { it is RequiredDirectory } }
            .mapNotNull { it.get(AppPaths) as? Path }
            .forEach { it.takeUnless { Files.exists(it) }?.let { Files.createDirectories(it) } }

        AppPaths::class.java.declaredFields
            .filter { it.annotations.any { it is RequiredFile } }
            .mapNotNull { it.get(AppPaths) as? Path }
            .forEach {
                it.parent.takeUnless { Files.exists(it) }?.let { Files.createDirectories(it) }
                it.takeUnless { Files.exists(it) }?.let { Files.createFile(it) }
            }
    }

    /**
     * Resolve the given path parts to a path.
     * Copy from JDK 22 for backward compatibility.
     * */
    fun resolve(base: Path, first: String, vararg more: String): Path {
        var result = base.resolve(first)
        for (s in more) {
            result = result.resolve(s)
        }
        return result
    }

    /**
     * Converts a path string, or a sequence of strings that when joined form a path string, to a [Path],
     * but only in the application's home directory.
     *
     * @param   first
     *          the path string or initial part of the path string
     * @param   more
     *          additional strings to be joined to form the path string
     *
     * @return  the resulting [Path]
     *
     * @throws  java.nio.file.InvalidPathException if the path string cannot be converted to a [Path]
     *
     * @see java.io.FileSystem#getPath
     * @see Path#of(String,String...)
     */
    fun get(first: String, vararg more: String): Path = Paths.get(homeDirStr, first.removePrefix(homeDirStr), *more)

    /**
     * Get a path of the application's temporary directory.
     *
     * A typical application temporary directory is:
     *
     * ```powershell
     * $env:Temp/pulsar-$env:USERNAME/
     * ```
     *
     * TODO: fix me: assertTrue(path2.startsWith(AppPaths.TMP_DIR))
     * ```kotlin
     * assertTrue(path2.startsWith(AppPaths.TMP_DIR), "$path -> $path2")
     * ```
     *
     * @param first the first part of the path
     * @param more the rest parts of the path
     */
    fun getTmpDirectory(first: String, vararg more: String): Path = resolve(TMP_DIR, first, *more)

    @Deprecated("Inappropriate name", ReplaceWith("AppPaths.getTmpDirectory(first, *more)"))
    fun getTmp(first: String, vararg more: String) = getTmpDirectory(first, *more)

    /**
     * Get a random path of the process's temporary directory.
     *
     * A typical process temporary directory is:
     *
     * ```powershell
     * $env:TMP/pulsar-$env:USERNAME/
     * ```
     *
     * @param prefix the prefix of the directory name
     * @param suffix the suffix of the directory name
     *
     * @return the path in the process's temporary directory
     * */
    fun getRandomTmpDirectory(prefix: String = "", suffix: String = ""): Path =
        getTmpDirectory(prefix, RandomStringUtils.secure().nextAlphabetic(18), suffix)

    @Deprecated("Inappropriate name", ReplaceWith("AppPaths.getRandomTmpDirectory(prefix, suffix)"))
    fun getRandomTmp(prefix: String = "", suffix: String = "") = getRandomTmpDirectory(prefix, suffix)

    /**
     * Get a path of the process's temporary directory.
     *
     * A typical process temporary directory is:
     *
     * ```powershell
     * $env:TMP/pulsar-$env:USERNAME/
     * ```
     *
     * @param first the first part of the path
     * @param more the rest parts of the path
     *
     * @return the path in the process's temporary directory
     * */
    fun getProcTmpDirectory(first: String, vararg more: String): Path = resolve(PROC_TMP_DIR, first, *more)

    @Deprecated("Inappropriate name", ReplaceWith("AppPaths.getProcTmpDirectory(first, *more)"))
    fun getProcTmp(first: String, vararg more: String) = getProcTmpDirectory(first, *more)

    /**
     * Get a path of the temporary directory in the process's temporary directory.
     *
     * A typical process temporary directory is:
     *
     * ```powershell
     * $env:TMP/pulsar-$env:USERNAME/
     * ```
     *
     * And the tmp-tmp directory is:
     *
     * ```powershell
     * $env:TMP/pulsar-$env:USERNAME/tmp
     * ```
     *
     * * @param first the first part of the path
     * @param more the rest parts of the path
     *
     * @return the path in the process's temporary directory
     * */
    fun getProcTmpTmpDirectory(first: String, vararg more: String): Path =
        resolve(PROC_TMP_DIR.resolve("tmp"), first, *more)

    @Deprecated("Inappropriate name", ReplaceWith("AppPaths.getProcTmpTmpDirectory(first, *more)"))
    fun getProcTmpTmp(first: String, vararg more: String) = getProcTmpTmpDirectory(first, *more)

    /**
     * Get a path of the temporary directory in the process's temporary directory.
     *
     * A typical process temporary directory is:
     *
     * ```powershell
     * $env:TMP/pulsar-$env:USERNAME/tmp/
     * ```
     *
     * @param prefix the prefix of the directory name
     * @param suffix the suffix of the directory name
     * @return the path in the process's temporary directory
     * */
    fun getRandomProcTmpTmpPath(prefix: String = "", suffix: String = ""): Path =
        getProcTmpTmpDirectory(prefix + RandomStringUtils.secure().nextAlphabetic(18) + suffix)

    fun getContextGroupDir(group: String) = CONTEXT_GROUP_BASE_DIR.resolve(group)

    fun getContextBaseDir(group: String, browserType: BrowserType) = getContextGroupDir(group).resolve(browserType.name)

    fun getTmpContextGroupDir(group: String) = CONTEXT_TMP_GROUP_BASE_DIR.resolve(group)

    fun getTmpContextBaseDir(group: String, browserType: BrowserType) =
        getTmpContextGroupDir(group).resolve(browserType.name)

    /**
     * Detect the auxiliary log directory which is used for non-logback-logs.
     * */
    fun detectAuxiliaryLogDir(): Path {
        return CACHE.computeIfAbsent("AUXILIARY_LOG_DIR") { detectAuxiliaryLogDir0() } as Path
    }

    /**
     * Generates a random string that is compatible with file paths and can be used as a file name.
     * The generated string consists of an optional prefix, a random alphabetic string of 18 characters,
     * and an optional suffix.
     *
     * @param prefix the prefix of the name
     * @param suffix the suffix of the name
     * @return the random string
     * */
    fun random(prefix: String = "", suffix: String = ""): String =
        "$prefix${RandomStringUtils.secure().nextAlphabetic(18)}$suffix"

    /**
     * Generates a path-compatible, hex string that can be used as a file name.
     *
     * This function takes a URI, hashes it using MD5, and then constructs a string by
     * concatenating the provided prefix, the hashed value, and the provided suffix.
     * The resulting string is suitable for use as a file name.
     *
     * @param uri The URI to be hashed. This is the input string that will be converted
     *            into a hex string using MD5 hashing.
     * @param prefix The prefix to be added before the hashed value. This is optional
     *               and defaults to an empty string if not provided.
     * @param suffix The suffix to be added after the hashed value. This is optional
     *               and defaults to an empty string if not provided.
     * @return A hex string that is the result of concatenating the prefix, the MD5
     *         hash of the URI, and the suffix. This string is suitable for use as a
     *         file name.
     */
    fun hex(uri: String, prefix: String = "", suffix: String = ""): String {
        // Generate the MD5 hash of the URI and construct the final string by
        // concatenating the prefix, the hash, and the suffix.
        return DigestUtils.md5Hex(uri).let { "$prefix$it$suffix" }
    }

    /**
     * Generates a path-compatible, hex string that can be used as a file name.
     *
     * This function takes a URI, hashes it using MD5.
     * The resulting string is suitable for use as a file name.
     *
     * @param uri The URI to be hashed. This is the input string that will be converted
     *            into a hex string using MD5 hashing.
     * @return A hex string that is the result of concatenating the prefix, the MD5
     *         hash of the URI, and the suffix. This string is suitable for use as a
     *         file name.
     */
    fun md5Hex(uri: String) = DigestUtils.md5Hex(uri)

    @Deprecated("Inappropriate name", ReplaceWith("md5Hex(uri)"))
    fun fileId(uri: String) = md5Hex(uri)

    /**
     * Create a mock page path.
     * */
    fun mockPagePath(uri: String): Path {
        val filename = fromUri(uri, "", ".htm")
        return LOCAL_TEST_WEB_PAGE_DIR.resolve(filename)
    }

    /**
     * Create a filename compatible string from the given url.
     * */
    fun fromHost(url: URL): String {
        var host = url.host
        host = if (Strings.isIpLike(host) || Strings.isIpPortLike(host) || host == "localhost") {
            host
        } else {
            runCatching { InternetDomainName.from(host).topPrivateDomain().toString() }.getOrNull() ?: "unknown"
        }

        return host.replace('.', '-')
    }

    /**
     * Create a filename compatible string from the given url.
     * */
    fun fromHost(url: String): String {
        val u = URLUtils.getURLOrNull(url) ?: return "unknown"
        return fromHost(u)
    }

    @Deprecated("Use AppPaths.fromHost instead", replaceWith = ReplaceWith("AppPaths.fromHost(url)"))
    fun fromDomain(url: String) = fromHost(url)

    /**
     * Create a filename compatible string from the given uri.
     * */
    fun fromUri(uri: String, prefix: String = "", suffix: String = ""): String {
        val u = URLUtils.getURLOrNull(uri) ?: return "${prefix}unknown$suffix"

        val dirForDomain = fromHost(u)
        val fileId = fileId(uri)
        return "$prefix$dirForDomain-$fileId$suffix"
    }

    fun fromTime(time: LocalDateTime): String {
        return time.format(PATH_SAFE_FORMATTER_11)
    }

    fun fromNow(): String {
        return LocalDateTime.now().format(PATH_SAFE_FORMATTER_11)
    }

    /**
     * Create a symbolic link from the given uri.
     *
     * The symbolic link is url based, unique, shorter but not readable filename
     * */
    fun uniqueSymbolicLinkForUri(uri: String, suffix: String = ".htm"): Path {
        return SYS_TMP_LINKS_DIR.resolve(hex(uri, "", suffix))
    }

    private fun detectAuxiliaryLogDir0(): Path {
        val candidates = listOf(
            Paths.get("."),
            getProcTmpDirectory(""),
            getProcTmpTmpDirectory(""),
            getTmpDirectory(""),
            DATA_DIR
        ).map { it.resolve("logs") }
            .filter { it.isDirectory() && it.exists() }
            .sortedByDescending { it.getLastModifiedTime() }

        if (candidates.isNotEmpty()) {
            return candidates[0]
        }

        val fallback = SYS_TMP_DIR.resolve("pulsar").resolve("logs")
        Files.createDirectories(fallback)

        return fallback
    }
}
