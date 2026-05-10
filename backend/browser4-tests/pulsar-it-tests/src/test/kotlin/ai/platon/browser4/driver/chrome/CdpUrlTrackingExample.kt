package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserFiles.CDP_URL_FILE_NAME
import java.nio.file.Files
import java.nio.file.Path

/**
 * Example demonstrating CDP URL tracking and browser reuse.
 *
 * This example shows:
 * 1. How CDP URL is automatically saved when launching Chrome
 * 2. How to read the CDP URL from the file
 * 3. How browser reuse works with the same userDataDir
 */
fun main() {
    val userDataDir = BrowserFiles.computeTestContextDir()
    val cdpUrlPath = userDataDir.resolveSibling(CDP_URL_FILE_NAME)

    println("=".repeat(60))
    println("CDP URL Tracking Example")
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

        // Read CDP URL from file
        if (Files.exists(cdpUrlPath)) {
            val cdpUrl = Files.readString(cdpUrlPath).trim()
            println("  CDP URL saved: $cdpUrl")
        } else {
            println("  ✗ CDP URL file not found!")
        }

        println()
        println("Step 2: Simulating browser reuse...")
        println("  (In a real scenario, you would launch with the same userDataDir)")

        // In a real scenario, you might do something like:
         val launcher2 = ChromeLauncher(userDataDir, options = LauncherOptions())
         val chrome2 = launcher2.launch(ChromeOptions().apply { headless = true })
        // This would reuse the existing browser and log the CDP URL

        println()
        println("Step 3: Checking CDP URL file...")
        if (Files.exists(cdpUrlPath)) {
            val cdpUrl = Files.readString(cdpUrlPath).trim()
            println("  CDP URL: $cdpUrl")
            println("  ✓ CDP URL is accessible and can be used to connect to the browser")
        }
    }

    println()
    println("=".repeat(60))
    println("Example completed!")
    println("=".repeat(60))

    readln()
}

/**
 * Helper function to demonstrate reading CDP URL from file.
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
        println("Failed to read CDP URL: ${e.message}")
        null
    }
}
