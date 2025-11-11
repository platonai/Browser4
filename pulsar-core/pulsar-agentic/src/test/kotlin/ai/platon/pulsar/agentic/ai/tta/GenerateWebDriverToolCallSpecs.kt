package ai.platon.pulsar.agentic.ai.tta

import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver

/**
 * A simple application to generate ToolCallSpec JSON file from WebDriver interface.
 * 
 * This can be run manually to generate the webdriver-toolcall-specs.json file
 * in the CODE_RESOURCE_DIR directory.
 */
fun main() {
    println("Generating ToolCallSpec from WebDriver interface using reflection...")
    
    val specs = SourceCodeToToolCallSpec.generateFromReflection(
        domain = "driver",
        interfaceClass = WebDriver::class,
        outputFileName = "webdriver-toolcall-specs.json"
    )
    
    println("✓ Generated ${specs.size} tool call specs")
    println("✓ Saved to: pulsar-core/pulsar-resources/src/main/resources/code-mirror/webdriver-toolcall-specs.json")
    println()
    println("Sample of generated specs:")
    specs.take(5).forEach { spec ->
        val args = spec.arguments.joinToString(", ") { arg ->
            if (arg.defaultValue != null) {
                "${arg.name}: ${arg.type} = ${arg.defaultValue}"
            } else {
                "${arg.name}: ${arg.type}"
            }
        }
        println("  - ${spec.domain}.${spec.method}($args): ${spec.returnType}")
    }
}
