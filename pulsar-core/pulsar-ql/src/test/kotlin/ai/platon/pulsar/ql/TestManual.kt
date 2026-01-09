package ai.platon.pulsar.ql

import org.junit.jupiter.api.Tag
import kotlin.test.Ignore
import kotlin.test.Test

class TestManual : TestBase() {

    @Test
    fun load() {
        execute("CALL DOM_LOAD('$productIndexUrl')")
    }

    /**
     * Extract by css selectors, {@link http://www.w3school.com.cn/cssref/css_selectors.asp}
     * */
    @Test
    fun extractByCss() {
        execute("SELECT * FROM LOAD_AND_SELECT('$productIndexUrl', '.welcome')")
    }

    @Test
    fun projectFields() {
        execute("SELECT DOM_TEXT(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '.welcome')")
    }

    @Test
    fun extractByCssBox() {
        execute("SELECT * FROM LOAD_AND_SELECT('$productIndexUrl', '*:in-box(*,*,229,36)')")
        execute("SELECT IN_BOX_FIRST_TEXT(DOM_LOAD('$productIndexUrl'), '229x36')")
    }

    @Test
    fun extractByCssExpression() {
        execute("SELECT * FROM LOAD_AND_SELECT('$productIndexUrl', '*:expr(width==248 && height==228)')")
        execute("SELECT DOM_TITLE(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '*:expr(width==248 && height==228) a')")
    }

    @Test
    fun extractBySql() {
        execute("SELECT DOM_TITLE(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '.nfPic a')")
        execute("SELECT DOM_TITLE(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '.nfPic a') WHERE LOCATE('白金版', DOM_TITLE(DOM)) > 0")

        execute("SELECT * FROM LOAD_AND_GET_FEATURES('$productIndexUrl') WHERE WIDTH=248 AND HEIGHT=228 LIMIT 100")
    }

    @Test
    fun loadAndGetLinks() {
        execute("SELECT LOAD_AND_SELECT('$productIndexUrl', '.nfList a')")
        execute("SELECT DOM_ABS_HREF(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '.nfList a')")
    }

    @Test
    fun loadAndGetLinksWithCssExpression() {
        val expr = "width > 240 && width < 250 && height > 360 && height < 370"
        execute("SELECT DOM_ABS_HREF(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '*:expr($expr) a')")
    }

    @Test
    fun testLoadAndGetLinksWithSqlCondition() {
        execute("""SELECT *
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl')
            WHERE WIDTH BETWEEN 240 AND 250 AND HEIGHT BETWEEN 360 AND 370 LIMIT 10""")

        execute("""SELECT DOM_ABS_HREF(DOM_SELECT_FIRST(DOM, 'a')) AS HREF
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl')
            WHERE WIDTH BETWEEN 240 AND 250 AND HEIGHT BETWEEN 360 AND 370 LIMIT 10""")

        execute("""SELECT DOM_ABS_HREF(DOM_SELECT_FIRST(DOM, 'a')) AS HREF
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl')
            WHERE SIBLING > 250 LIMIT 10""")
    }

    @Test
    fun loadAndGetLinksUsingPreDefinedFunction() {
        val expr = "width > 240 && width < 250 && height > 360 && height < 370"
        execute("SELECT * FROM LOAD_AND_GET_LINKS('$productIndexUrl', '*:expr($expr)')")
    }

    @Test
    fun loadOutPages() {
        execute("SELECT DOM, DOM_TEXT(DOM) FROM LOAD_AND_SELECT('$productIndexUrl', '.nfList')")
    }

    @Test
    fun loadAndGetFeatures() {
        execute("SELECT * FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '.nfList', 1, 20)")
        execute("SELECT * FROM LOAD_AND_GET_FEATURES('$productDetailUrl', 'DIV,UL,UI,P', 1, 20)")
    }

    /**
     * Get vivid links - the most interested links in a page, for example, product list or news list
     * */
    @Test
    fun getVividLinks() {
        val expr = "sibling > 20 && char > 40 && char < 100 && width > 200"
        execute("""SELECT
            DOM, DOM_FIRST_HREF(DOM), TOP, LEFT, WIDTH, HEIGHT, CHAR, IMG, A, SIBLING, DOM_TEXT(DOM)
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '*:expr($expr)')
            ORDER BY SIBLING DESC, CHAR DESC LIMIT 500""")
    }

    /**
     * Get the container element for all vivid links
     * */
    @Test
    fun getVividLinkParent() {
        val expr = "sibling > 20 && char > 40 && char < 100 && width > 200"
        execute("""SELECT
            DOM_PARENT(DOM), DOM, DOM_FIRST_HREF(DOM), TOP, LEFT, WIDTH, HEIGHT, CHAR, IMG, A, SIBLING, DOM_TEXT(DOM)
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '*:expr($expr)')
            ORDER BY SIBLING DESC, CHAR DESC LIMIT 50""")
    }

    /**
     * Get the element who has the most direct children, it probably be a container of vivid links
     * */
    @Ignore("Test failed and ignore temporary")
    @Test
    fun getElementWithMostChildren() {
        val expr = "child > 20 && char > 100 && width > 800"
        execute("""SELECT
            DOM, DOM_FIRST_HREF(DOM), TOP, LEFT, WIDTH, HEIGHT, CHAR, IMG, A, CHILD, SIBLING
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '*:expr($expr)')
            ORDER BY CHILD DESC, CHAR DESC LIMIT 50""")
    }

    /**
     * A simple Web page monitor, monitoring news
     * */
    @Test
    @Tag("MustManuallyRun")
    fun monitorNewsColumnForQQ() {
        val portal = "https://news.qq.com/ch/world"

        // Tencent news have a redirect mechanism, we have to fix this
        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$portal') " +
                "WHERE SIBLING>20 AND DOM_TEXT_LENGTH(DOM) > 10 AND TOP > 300 AND TOP < 3000")

        val detail = "https://news.qq.com/rain/a/20250507A052WD00"
        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, CHAR, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$detail') WHERE SEQ > 170 AND SEQ < 400")
    }

    /**
     * A simple Web page monitor, monitoring news
     * */
    @Test
    @Tag("MustManuallyRun")
    fun monitorNewsColumnForCnHuBei() {
        val portal = "http://news.cnhubei.com/"

        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$portal') " +
                "WHERE SIBLING>30 AND DOM_TEXT_LENGTH(DOM) > 10 AND TOP > 300 AND TOP < 3000")

        val detail = "http://news.cnhubei.com/xw/jj/201804/t4102239.shtml"
        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, CHAR, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$detail') WHERE SEQ > 170 AND SEQ < 400")
    }

    /**
     * A simple Web page monitor, monitoring products
     * */
    @Test
    @Tag("MustManuallyRun")
    @Ignore("TimeConsumingTest")
    fun monitorProductColumn2() {
        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$productIndexUrl') " +
                "WHERE SIBLING>30 AND DOM_TEXT_LENGTH(DOM) > 10 AND TOP > 300 AND TOP < 3000")

        execute("SELECT DOM, TOP, LEFT, WIDTH, HEIGHT, IMG, A, CHAR, SIBLING, DOM_TEXT(DOM), DOM_FIRST_HREF(DOM) " +
                "FROM LOAD_AND_GET_FEATURES('$productDetailUrl') WHERE SEQ > 170 AND SEQ < 400")
    }
}
