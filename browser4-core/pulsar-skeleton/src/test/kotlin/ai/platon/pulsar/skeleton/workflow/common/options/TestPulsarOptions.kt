package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.options.OptionUtils
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.options.PulsarOptions
import com.google.common.collect.Lists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Created by Vincent on 16-7-20.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
class TestPulsarOptions {
    private val links = Lists.newArrayList(
        "http://www.news.cn/comments/index.htm",
        "http://xinhuanet.com/silkroad/index.htm",
        "http://www.news.cn/video/xhwsp/index.htm",
        "http://www.xinhuanet.com/company/legal.htm",
        "http://www.xinhuanet.com/politics/xhll.htm",
        "http://www.xinhuanet.com/datanews/index.htm",
        "http://www.news.cn/politics/leaders/index.htm",
        "http://www.xinhuanet.com/company/copyright.htm",
        "http://www.xinhuanet.com/company/contact-us.htm",
        "http://forum.home.news.cn/detail/140976763/1.html",
        "http://www.bjnews.com.cn/news/2017/06/20/447390.html",
        "http://www.bjnews.com.cn/news/2017/06/20/447403.html",
        "http://www.bjnews.com.cn/news/2017/06/19/447269.html",
        "http://www.bjnews.com.cn/news/2017/06/20/447414.html",
        "http://www.bjnews.com.cn/news/2017/06/20/447354.html",
        "http://www.bjnews.com.cn/news/2017/06/19/447316.html"
    )

    private val conf = VolatileConfig()

    @Test
    fun testRebuildOptions() {
        val args = "-ps -rpl -nlf -notSupport"
        val loadOptions = LoadOptions.parse(args, conf)
        val finalArgs = loadOptions.toString()
        assertTrue(loadOptions.params.asMap().containsKey("-persist"))
        assertTrue(!finalArgs.contains("-notSupport"))
    }

    @Test
    fun testQuotedArguments() {
        val args = "-expires PT1S -incognito -outlinkSelector \".products a\" -retry"
        val argv = PulsarOptions.split(args)
        assertEquals("-expires", argv[0])
        assertEquals("PT1S", argv[1])

        assertEquals("-outlinkSelector", argv[3])
        assertEquals("\".products a\"", argv[4])
    }

    @Test
    fun testOptionHelp() {
        LoadOptions.helpList.forEach {
            printlnPro(it)
        }
    }

    @Test
    fun testFixArity1() {
        val search = "-cacheContent"
        val argsList = listOf(
            "-label test $search",
            "-label test $search -parse",
            "$search -parse"
        )
        argsList.forEach { args ->
            val args1 = OptionUtils.arity0ToArity1(args, search)
            assertTrue("$search true" in args1, args)
        }
    }
}

