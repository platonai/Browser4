package ai.platon.pulsar.test

object TestUrls {

    var PRODUCT_LIST_URL = "https://www.amazon.com/b?node=1292115011"

    var PRODUCT_DETAIL_URL = "https://www.amazon.com/dp/B08PP5MSVB"

    var NEWS_INDEX_URL = "https://news.baidu.com"

    var NEWS_DETAIL_URL = "https://shuhua.gscn.com.cn/system/2025/09/30/013392374.shtml"

    var PRODUCT_INDEX_URL_ZH = "https://e.dangdang.com/index_page.html"

    var PRODUCT_DETAIL_URL_ZH = "https://e.dangdang.com/products/1900089800.html"

    // Using mock EC server URLs instead of real Amazon URLs
    const val MOCK_PRODUCT_LIST_URL = "http://localhost:18080/ec/b?node=1292115012"

    const val MOCK_PRODUCT_DETAIL_URL = "http://localhost:18080/ec/dp/B0E000001"

    var urlGroups = mutableMapOf<String, Array<String>>()

    init {
        urlGroups["baidu"] = arrayOf(
            "https://www.baidu.com/s?wd=马航&oq=马航&ie=utf-8"
        )
        urlGroups["jd"] = arrayOf(
            "https://list.jd.com/list.html?cat=652,12345,12349",
            "https://item.jd.com/1238838350.html",
            "http://search.jd.com/Search?keyword=长城葡萄酒&enc=utf-8&wq=长城葡萄酒",
            "https://detail.tmall.com/item.htm?id=536690467392",
            "https://detail.tmall.com/item.htm?id=536690467392",
            "http://item.jd.com/1304924.html",
            "http://item.jd.com/3564062.html",
            "http://item.jd.com/1304923.html",
            "http://item.jd.com/3188580.html",
            "http://item.jd.com/1304915.html"
        )
        urlGroups["mogujie"] = arrayOf(
            "https://list.mogu.com/book/bags",
            "http://list.mogujie.com/book/skirt",
            "http://shop.mogujie.com/detail/1kcnxeu",
            "http://shop.mogujie.com/detail/1lrjy2c"
        )
        urlGroups["vip"] = arrayOf(
            "http://category.vip.com/search-1-0-1.html?q=3|29736",
            "https://category.vip.com/search-5-0-1.html?q=3|182725",
            "http://detail.vip.com/detail-2456214-437608397.html",
            "https://detail.vip.com/detail-2640560-476811105.html"
        )
        urlGroups["wikipedia"] = arrayOf(
            "https://en.wikipedia.org/wiki/URL",
            "https://en.wikipedia.org/wiki/URI",
            "https://en.wikipedia.org/wiki/URN"
        )
    }
}
