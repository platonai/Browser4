package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import ai.platon.pulsar.test.TestUrls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(MockEcServerConfiguration::class)
open class RestAPITestBase : IntegrationTestBase() {

    companion object {
        val urls = mapOf(
            "productListPage" to TestUrls.MOCK_PRODUCT_LIST_URL,
            "productDetailPage" to TestUrls.MOCK_PRODUCT_DETAIL_URL
        )

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
            dom_height(dom_select_first(dom, 'a img[srcset]')) as `pic_height`,
            dom_width(dom) as `width`,
            dom_height(dom) as `height`
        from load_and_select(@url, 'div[class*=search-result]');
    """.trimIndent(),
        "productDetailPage" to """
            select
              dom_base_uri(dom) as url,
              dom_first_text(dom, '#productTitle') as title,
              dom_first_slim_html(dom, 'img:expr(width > 400)') as img,
              dom_width(dom) as `width`,
              dom_height(dom) as `height`
            from load_and_select(@url, 'body');
        """.trimIndent()
    ).entries.associate { it.key to SQLTemplate(it.value) }

    @BeforeEach
    fun setUp() {
        // wait for the mock EC server to be ready before running tests
        assertThat(getJson("/actuator/health").body).contains("UP")
    }

    @Test
    @DisplayName("When say hello then returns hello")
    fun whenSayHelloThenReturnsHello() {
        assertThat(getHtml("/api/system/hello").body).contains("hello")
    }
}
