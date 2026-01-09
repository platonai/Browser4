package ai.platon.pulsar.examples.sites.topEc.chinese

import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.examples.sites.topEc.chinese.login.taobao.TaobaoLoginHandler

fun main() {
    val urls = """
            http://category.dangdang.com/cid4010209.html -ol a[href~=product]
            https://list.gome.com.cn/cat10000092.html -ol a[href~=item]
            https://list.jd.com/list.html?cat=652,12345,12349 -ol a[href~=item]
            https://list.tmall.com/search_product.htm?q=大家电 -ol a[href~=item]
            https://search.suning.com/微单/&zw=0?safp=d488778a.shuma.44811515285.1 -ol a[href~=detail]
            https://s.taobao.com/search?spm=a21bo.jianhua.201867-main.24.5af911d9wFOWsc&q=收纳 -ol a[href~=item]
    """.trimIndent().split("\n").filter { it.startsWith("http") }
    val args = "-i 1s -ii 5d -parse -ignoreFailure"

    val session = PulsarContexts.createSession()
    val options = session.options(args)

    val event = options.eventHandlers
    event.browseEventHandlers.onBrowserLaunched.addLast { page, driver ->
        // TODO: rotate accounts
        val username = System.getenv("PULSAR_TAOBAO_USERNAME") ?: "MustFallUsername"
        val password = System.getenv("PULSAR_TAOBAO_PASSWORD") ?: "MustFallPassword"
        val taobaoLoginHandler = TaobaoLoginHandler(username, password, warnUpUrl = urls.first { it.contains("taobao") })
        taobaoLoginHandler.invoke(page, driver)

        // sign in all websites requiring login
    }

    event.loadEventHandlers.onHTMLDocumentParsed.addLast { page, document ->
        println(document.title + " | " + document.baseURI)
    }
    urls.forEach { session.submitForOutPages(it, options) }

    PulsarContexts.await()
}
