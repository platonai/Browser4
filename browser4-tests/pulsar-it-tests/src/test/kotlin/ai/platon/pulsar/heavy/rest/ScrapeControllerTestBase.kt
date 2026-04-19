package ai.platon.pulsar.heavy.rest

import ai.platon.pulsar.common.sql.SQLTemplate
// import ai.platon.pulsar.test.BasicTestHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

open class ScrapeControllerTestBase : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @BeforeAll
                @DisplayName("Ensure resources are prepared")
        fun ensureResourcesArePrepared() {
        }
    }

    val sqlTemplates = mapOf(
        "productListPage" to """
        select
            dom_base_uri(dom) as `url`,
            str_substring_after(dom_base_uri(dom), '&rh=') as `nodeID`,
            dom_first_text(dom, 'a span.a-price:first-child span.a-offscreen') as `price`,
            dom_first_text(dom, 'a:has(span.a-price) span:containsOwn(/Item)') as `priceperitem`,
            dom_first_text(dom, 'a span.a-price[data-a-strike] span.a-offscreen') as `listprice`,
            dom_first_text(dom, 'h2 a') as `title`,
            dom_height(dom_select_first(dom, 'a img[srcset]')) as `pic_height`
        from load_and_select(@url, 'div[class*=search-result]');
    """.trimIndent(),
        "productDetailPage" to """
            select
              llm_extract(dom, 'product name, price, ratings') as llm_extracted_data,
              dom_base_uri(dom) as url,
              dom_first_text(dom, '#productTitle') as title,
              dom_first_slim_html(dom, 'img:expr(width > 400)') as img
            from load_and_select(@url, 'body');
        """.trimIndent()
    ).entries.associate { it.key to SQLTemplate(it.value) }

    @BeforeEach
    fun setUp() {
    }

    @Test
        @DisplayName("When say hello then returns hello")
    fun whenSayHelloThenReturnsHello() {
        assertThat(getHtml("/api/system/hello").body).contains("hello")
    }
}
