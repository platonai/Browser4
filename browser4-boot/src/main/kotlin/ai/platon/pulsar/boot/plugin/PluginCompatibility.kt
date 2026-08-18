package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import ai.platon.pulsar.skeleton.plugin.SdkVersions

/**
 * Decides whether a plugin is compatible with the running Browser4 host,
 * based on the plugin's declared SDK version vs the host version.
 *
 * Compatibility contract: within the same major version the SDK API is
 * binary-compatible, so plugins built with an older (same-major) SDK keep
 * working. Only a plugin whose SDK major version is NEWER than the host's is
 * refused — it may depend on APIs the host does not provide.
 *
 * | Plugin sdkVersion            | Verdict            |
 * |------------------------------|--------------------|
 * | same major as host           | [Compatible]       |
 * | older major (legacy plugin)  | [Warn] (still loads) |
 * | newer major than host        | [Blocked] (refused) |
 * | missing / unparseable        | [Warn] (best-effort) |
 * | host version unknown         | [Compatible]       |
 */
sealed class PluginCompatibility(
    /** The SDK version the plugin declared, or null when absent. */
    open val sdkVersion: String?,
) {

    /** Plugin loads normally. */
    data class Compatible(override val sdkVersion: String?) : PluginCompatibility(sdkVersion)

    /** Plugin loads, but the operator should know about the risk. */
    data class Warn(
        val reason: String,
        override val sdkVersion: String?,
    ) : PluginCompatibility(sdkVersion)

    /** Plugin must NOT be loaded/installed. */
    data class Blocked(
        val reason: String,
        override val sdkVersion: String?,
    ) : PluginCompatibility(sdkVersion)

    companion object {
        /**
         * Evaluates [manifest] against the host version (default:
         * [Browser4Version.version], the version of the running application).
         */
        fun check(
            manifest: PluginManifest,
            hostVersion: String = Browser4Version.version,
        ): PluginCompatibility {
            val sdk = manifest.sdkVersion.trim().takeIf { it.isNotEmpty() }
            if (sdk == null) {
                return Warn(
                    "plugin '${manifest.name}' declares no sdkVersion (built before 4.14); " +
                        "loaded on a best-effort basis",
                    null,
                )
            }

            val pluginMajor = SdkVersions.majorOf(sdk)
            if (pluginMajor == null) {
                return Warn(
                    "plugin '${manifest.name}' declares unparseable sdkVersion '$sdk'; " +
                        "loaded on a best-effort basis",
                    sdk,
                )
            }

            val hostMajor = SdkVersions.majorOf(hostVersion)
            if (hostMajor == null) {
                // Cannot judge compatibility when the host version is unknown.
                return Compatible(sdk)
            }

            return when {
                pluginMajor > hostMajor -> Blocked(
                    "plugin '${manifest.name}' requires SDK $sdk but host is $hostVersion — " +
                        "rebuild the plugin with SDK $hostMajor.x or upgrade Browser4",
                    sdk,
                )
                pluginMajor < hostMajor -> Warn(
                    "plugin '${manifest.name}' was built with older SDK $sdk (host $hostVersion); " +
                        "kept for backward compatibility — rebuild with the current SDK to use new APIs",
                    sdk,
                )
                else -> Compatible(sdk)
            }
        }
    }
}
