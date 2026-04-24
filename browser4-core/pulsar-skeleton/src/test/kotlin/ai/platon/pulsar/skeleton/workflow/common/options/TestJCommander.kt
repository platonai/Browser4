package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.common.options.PulsarOptions
import ai.platon.pulsar.skeleton.common.options.WeightedKeywordsConverter
import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import java.util.*
import kotlin.test.*

class Args {
    @Parameter(names = ["-expires"])
    var expires = "1s"

    @Parameter(names = ["-incognito"])
    var incognito = false

    @Parameter(names = ["-parse"])
    var parse = false

    @Parameter(names = ["-persist"], arity = 1)
    var persist = true

    @Parameter(names = ["-retry"])
    var retry = false

    @Parameter(names = ["-storeContent"], arity = 1)
    var storeContent = true

    @Parameter(names = ["-cacheContent"], arity = 1)
    var cacheContent = false
}

class Args2 {
    @Parameter
    var parameters: MutableList<String> = mutableListOf()

    @Parameter(names = ["-log", "-verbose"], description = "Level of verbosity")
    var verbose = 1

    @Parameter(names = ["-groups"], description = "Comma-separated list of group names to be run")
    var groups: String? = null

    @Parameter(names = ["-debug"], description = "Debug mode")
    var debug = false

    @DynamicParameter(names = ["-D"], description = "Dynamic parameters go here")
    var dynamicParams: Map<String, String> = HashMap()
}

/**
 * Created by Vincent on 16-7-20.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
class TestJCommander {
    private val conf = ImmutableConfig()

    @Test
    fun testJCommanderParseArgs() {
        val args1 = "-parse -incognito -expires 1s -persist false -storeContent false -cacheContent false"
        val args2 = "-incognito -expires 1d -storeContent true -cacheContent true"
//        val args3 = "-expires 1d -storeContent true"
        val args3 = "$args1 $args2"

        val args = Args()
        val argv = args3.split(" ").toTypedArray()
        JCommander.newBuilder()
            .addObject(args)
            .allowParameterOverwriting(true)
            .build()
            .parse(*argv)

        assertTrue { args.storeContent }
        assertTrue { args.storeContent }
        assertTrue { args.parse }
        assertFails("A JCommand bug") {
            assertTrue { args.incognito }
        }
        assertFalse { args.retry }
    }

    @Test
    fun testParameterOverwriting() {
        val command = "-storeContent false -storeContent true"

        val args = Args()
        val argv = command.split(" ").toTypedArray()

        JCommander.newBuilder().addObject(args).allowParameterOverwriting(true).build().parse(*argv)
        // failed
        assertTrue { args.storeContent }
    }

    @Test
    fun testParameterOverwriting2() {
        val command = "-storeContent false -storeContent false"

        val args = Args()
        val argv = command.split(" ").toTypedArray()

        JCommander.newBuilder().addObject(args).allowParameterOverwriting(true).build().parse(*argv)
        assertFalse { args.storeContent }
    }

    @Test
    fun testParameterOverwriting3() {
        val args3 = "-incognito -incognito"

        val args = Args()
        val argv = args3.split(" ").toTypedArray()
        JCommander.newBuilder()
            .addObject(args)
            .allowParameterOverwriting(true)
            .build()
            .parse(*argv)

        // There is a JCommand bug to handling boolean parameter overwriting
        assertFails("There is a JCommand bug to handling boolean parameter overwriting") {
            assertTrue { args.incognito }
        }
    }

    @Test
    fun testJCommanderParseArgs2() {
        val args = Args2()
        val argv = arrayOf(
            "-log", "2", "-groups", "unit1,unit2,unit3",
            "-debug", "-Doption=value", "a", "b", "c"
        )
        JCommander.newBuilder()
            .addObject(args)
            .build()
            .parse(*argv)

        assertEquals(2, args.verbose)
        assertEquals("unit1,unit2,unit3", args.groups)
        assertEquals(true, args.debug)
        assertEquals("value", args.dynamicParams["option"])
        assertEquals(listOf("a", "b", "c"), args.parameters)
    }

    @Test
    fun testQuotedOptions() {
        class Cmd {
            @Parameter(names = ["-instance", "-ins"], required = true, description = "Instance ID")
            var instances: List<String> = LinkedList()
        }

        val cmd = Cmd()
        JCommander.newBuilder().addObject(cmd)
                .args(arrayOf("-ins", "\"string one\"", "-ins", "\"string two\""))
                .build()
        assertEquals(cmd.instances.size.toLong(), 2)

        val args = "-ins \"string one\" -ins \"ul li > a[href~=item]\""
        val argv = PulsarOptions.split(args)
        val cmd2 = Cmd()
        JCommander.newBuilder().addObject(cmd2).args(argv).build()
        assertEquals(cmd2.instances.size.toLong(), 2)
        // Notice: the official document says: there are problems to strip "\""
        assertEquals("\"ul li > a[href~=item]\"", cmd2.instances[1])
        // System.out.logPrintln(String.join(" | ", argv));
    }

    @Test
    fun testWeightedKeywordsConverter() {
        val converter = WeightedKeywordsConverter()
        val answer: MutableMap<String, Double> = HashMap()
        answer["a"] = 1.1
        answer["b"] = 2.0
        answer["c"] = 0.2
        answer["d"] = 1.0
        answer["e^"] = 1.0
        answer["^1"] = 1.0
        answer["^"] = 1.0

        val question = converter.convert("a^1.1,     b^2.0,c^0.2,d,e^,^1,^,")
        assertEquals(answer["a"], question["a"])
    }
}

