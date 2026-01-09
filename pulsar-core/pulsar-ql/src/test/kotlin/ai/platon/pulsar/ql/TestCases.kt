package ai.platon.pulsar.ql

import ai.platon.pulsar.test.TestResourceUtil
import org.junit.jupiter.api.Tag
import kotlin.test.Test

class TestCases: TestBase() {

    @Test
    fun testXSqlHelp() {
        execute("SELECT * FROM XSQL_HELP()")
    }

    @Test
    fun projectFields() {
        execute("SELECT DOM_TEXT(DOM) AS `breadcrumb` FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '#breadcrumb')")
        execute("SELECT DOM_TEXT(DOM) FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '.shoplist li a', 1, 5)")
        execute("SELECT DOM_SRC(DOM) FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '.shoplist li a', 1, 5)")

        execute("SELECT DOM_TITLE(DOM), DOM_ABS_HREF(DOM) FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '.shoplist li a', 1, 5)")
        execute("SELECT DOM_TITLE(DOM), DOM_ABS_HREF(DOM) FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), 'a[href~=product]', 1, 5)")
    }

    @Test
    fun projectFields2() {
        val sql = """
            SELECT
                DOM_HEIGHT(DOM), DOM_TEXT(DOM), DOM_BASE_URI(DOM), DOM_CSS_SELECTOR(DOM)
            FROM
                DOM_SELECT(DOM_LOAD('$productIndexUrl'), '#breadcrumb');
        """.trimIndent()
        execute(sql)
    }

    @Test
    fun testCondition() {
        execute("SELECT * FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '.shoplist li');")
        execute("SELECT * FROM LOAD_AND_GET_FEATURES('$productIndexUrl', 'div', 1, 10000) " +
                "WHERE WIDTH BETWEEN 200 AND 400 AND HEIGHT BETWEEN 200 AND 400 AND TXT_ND > 0;")
    }

    @Test
    fun extractByCssBox() {
        execute("SELECT * FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '*:in-box(*,*,323,31)')") // TODO: failed
        execute("SELECT * FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '*:in-box(*,*,229,36)', 1, 5)")

        execute("SELECT IN_BOX_FIRST_TEXT(DOM_LOAD('$productIndexUrl'), '229x36')")
    }

    @Test
    fun testExtractTable() {
        val url = TestResourceUtil.PRODUCT_DETAIL_URL
        val sql = """
            select
                dom_all_texts(dom, '#comparison_title, tr.comparison_table_image_row th a[href~=/dp/]') as `Product name`,
                dom_all_attrs(dom, 'tr.comparison_table_image_row center > img[alt]', 'data-src') as `Product image`,
                dom_all_texts(dom, 'tr.comparison_table_image_row th i span:contains(Best Seller)') as `Label best seller`,
                dom_all_texts(dom, 'tr#comparison_custormer_rating_row > td') as `Customer Rating`,
                dom_all_texts(dom, 'tr#comparison_price_row > td') as `Price`,
                dom_all_texts(dom, 'tr#comparison_sold_by_row > td') as `Sold By`,
                dom_all_texts(dom, 'tr.comparison_other_attribute_row:contains(Color) > td') as `Color`,
                dom_all_texts(dom, 'tr.comparison_other_attribute_row:contains(Item Dimensions) > td') as `Item Dimensions`,
                dom_all_texts(dom, 'tr.comparison_other_attribute_row:contains(Material) > td') as `Material`
            from load_and_select('$url', '#HLCXComparisonTable')
        """.trimIndent()
        execute(sql)
    }

    @Test
    @Tag("MustManuallyRun")
    fun loadAndSelectNeeq() {
        val url = "http://www.neeq.com.cn/nq/listedcompany.html"
        // execute("select dom, dom_css_selector(dom), dom_text(dom), dom_text_length(dom) from dom_load_and_select('$url', 'tbody > tr');", remote = true)
        val sql = """
        select
            dom,
            dom_css_selector(dom),
            dom_nth_text(dom, 'td', 1) as code,
            dom_nth_text(dom, 'td', 2) as short_name,
            dom_nth_text(dom, 'td', 3) as category,
            dom_nth_text(dom, 'td', 4) as industry,
            dom_nth_text(dom, 'td', 5) as trader,
            dom_nth_text(dom, 'td', 6) as city,
            dom_nth_text(dom, 'td', 7) as links
        from load_and_select('$url', 'tbody:not(:first-child) > tr');
        """.trimIndent()
        execute(sql)
    }

    @Test
    @Tag("MustManuallyRun")
    @Tag("ExternalResourceDependent")
    fun loadAndGetLinksCityflower() {
        val sql = """
select *
from load_and_get_links('https://www.cityflower.net/attribute/21.html -i 1d', '.recommend a');
        """.trimIndent()

        execute(sql)
    }
}
