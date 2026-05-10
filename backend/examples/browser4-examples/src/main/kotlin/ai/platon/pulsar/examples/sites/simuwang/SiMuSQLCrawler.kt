package ai.platon.pulsar.examples.sites.simuwang

open class SiMuSQLCrawler: SiMuCrawler() {
    val sql = """
select
    dom_first_attr(dom, '> div', 'name') as code,
    dom_first_attr(dom, '.ranking-table-ellipsis a[href~=product]', 'title') as name,
    dom_first_href(dom, '.ranking-table-ellipsis a[href~=product]') as productUrl,
    dom_first_text(dom, 'div a[href~=company]') as company,
    dom_first_href(dom, 'div a[href~=company]') as companyUrl,
    -- 排名期最新净值
    dom_first_text(dom, 'div div.nav.dc-home-333') as latestWorth,
    dom_first_text(dom, 'div div.nav.dc-home-333 ~ .price-date') as dateOfLatestWorth,
    dom_first_text(dom, 'div .ranking-table-profit-tbody') as profit
from
    load_and_select('$portalUrl', '.ranking-table-tbody .ranking-table-tbody-tr')
        """.trimIndent()

    override fun crawl() {
        // load the page and perform event handlers
        session.load(portalUrl, options)
        // note: event handlers are not performed in SQL mode
        context.executeQuery(sql)
        // wait for all done
        context.await()
    }
}

fun main() = SiMuSQLCrawler().crawl()
