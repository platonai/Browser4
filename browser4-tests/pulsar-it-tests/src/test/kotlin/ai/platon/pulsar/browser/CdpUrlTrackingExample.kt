package ai.platon.pulsar.browser

import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.chrome.ChromeLauncher
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserFiles.CDP_URL_FILE_NAME
import java.nio.file.Files
import java.nio.file.Path

/**
 * Example demonstrating BrowserProtocol URL tracking and browser reuse.
 *
 * This example shows:
 * 1. How BrowserProtocol URL is automatically saved when launching Chrome
 * 2. How to read the BrowserProtocol URL from the file
 * 3. How browser reuse works with the same userDataDir
 */
fun main() {
    val userDataDir = BrowserFiles.computeRandomTmpContextDir()
    val cdpUrlPath = userDataDir.resolveSibling(CDP_URL_FILE_NAME)

    println("=".repeat(60))
    println("BrowserProtocol URL Tracking Example")
    println("=".repeat(60))
    println("User Data Dir: $userDataDir")
    println()

    // First launch - new browser instance
    println("Step 1: Launching new Chrome instance...")
    val launcher1 = ChromeLauncher(userDataDir, options = LauncherOptions())
    launcher1.use {
        val chrome = launcher1.launch(ChromeOptions().apply { headless = true })

        println("✓ Chrome launched successfully")
        println("  Browser version: ${chrome.version.browser}")

        // Read BrowserProtocol URL from file
        if (Files.exists(cdpUrlPath)) {
            val cdpUrl = Files.readString(cdpUrlPath).trim()
            println("  BrowserProtocol URL saved: $cdpUrl")
        } else {
            println("  ✗ BrowserProtocol URL file not found!")
        }

        println()
        println("Step 2: Simulating browser reuse...")
        println("  (In a real scenario, you would launch with the same userDataDir)")

        // In a real scenario, you might do something like:
         val launcher2 = ChromeLauncher(userDataDir, options = LauncherOptions())
         val chrome2 = launcher2.launch(ChromeOptions().apply { headless = true })
        // This would reuse the existing browser and log the BrowserProtocol URL

        println()
        println("Step 3: Checking BrowserProtocol URL file...")
        if (Files.exists(cdpUrlPath)) {
            val cdpUrl = Files.readString(cdpUrlPath).trim()
            println("  BrowserProtocol URL: $cdpUrl")
            println("  ✓ BrowserProtocol URL is accessible and can be used to connect to the browser")
        }
    }

    println()
    println("=".repeat(60))
    println("Example completed!")
    println("=".repeat(60))

    readln()
}

/**
 * Helper function to demonstrate reading BrowserProtocol URL from file.
 */
fun readCdpUrl(userDataDir: Path): String? {
    val cdpUrlPath = userDataDir.resolveSibling(CDP_URL_FILE_NAME)
    return try {
        if (Files.exists(cdpUrlPath)) {
            Files.readString(cdpUrlPath).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    } catch (e: Exception) {
        println("Failed to read BrowserProtocol URL: ${e.message}")
        null
    }
}
