package ai.platon.pulsar.skeleton.crawl.common.options

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import kotlin.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Parameters(separators = "=", commandDescription = "Just for testing quote handling.")
class TestParameterQuoteHandling {
    @Parameter(names = ["--aParameter"], description = "A String.")
    var aParameter: String? = null
    @Parameter(names = ["--aParameterList"], description = "A String list.")
    var aParameterList: List<String>? = null

    @Test
    fun testParameterHavingQuotes() {
        val jc = JCommander(this)
        jc.parse("--aParameter=\"X\"")
        assertNotNull(aParameter)
        // as of JCommander 1.74/75
        assertEquals("\"X\"", aParameter, "Expect \"X\" for JCommander 1.74/75 and more recent")
    }

    @Test
    fun testParameterListHavingQuotes() {
        val jc = JCommander(this)
        jc.parse("--aParameterList=\"X,Y\"")
        assertNotNull(aParameterList)
        assertEquals(2, aParameterList!!.size)
        assertEquals("\"X", aParameterList!![0])
        assertEquals("Y\"", aParameterList!![1])
    }
}
