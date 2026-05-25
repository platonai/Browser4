package ai.platon.pulsar.heavy.ql

import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assumptions
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals

/**
 * The base class for all tests
 */
abstract class TestBase {

    companion object {
        init {
            /**
             * Load options are in webpage scope, so it should be initialized after PulsarContextInitializer
             * */
            LoadOptionDefaults.apply {
                parse = true
                ignoreFailure = true
                nJitRetry = 3
                test = 1
                browser = BrowserType.PULSAR_CHROME
            }
        }

        val logger = LoggerFactory.getLogger(TestBase::class.java)

        val history = mutableListOf<String>()
        val startTime = Instant.now()

        val context = SQLContexts.create()
        val session = context.getOrCreateSession()

        suspend fun ensurePage(url: String) {
            val pageCondition = { page: WebPage -> page.protocolStatus.isSuccess && page.persistedContentLength > 8000 }
            val page = session.load(url).takeIf(pageCondition) ?: session.load(url, "-refresh")

            Assumptions.assumeTrue(page.protocolStatus.isSuccess)
            Assumptions.assumeTrue(page.contentLength > 0)
            if (page.isFetched) {
                Assumptions.assumeTrue(page.persistedContentLength > 0)
            }
        }
    }

    val logger = getLogger(this)

    val productIndexUrl = TestUrls.PRODUCT_INDEX_URL_ZH
    val productDetailUrl = TestUrls.PRODUCT_DETAIL_URL_ZH

    fun execute(sql: String, printResult: Boolean = true) {
        context.run { connection ->
            connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)
                .use { stat ->
                    val regex = "^(SELECT|CALL).+".toRegex()
                    if (sql.uppercase(Locale.getDefault()).filter { it != '\n' }.trimIndent().matches(regex)) {
                        val rs = stat.executeQuery(sql)
                        if (printResult) {
                            printlnPro(ResultSetFormatter(rs, withHeader = true))
                        }
                    } else {
                        val r = stat.execute(sql)
                        if (printResult) {
                            printlnPro(r)
                        }
                    }
                    // SysProperties.serializeJavaObject = lastSerializeJavaObject
                    history.add("${sql.trim { it.isWhitespace() }};")
                }
        }
    }

    fun query(sql: String, action: (ResultSet) -> Unit): ResultSet {
        return context.runQuery { connection ->
            val stat = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)
            val rs = stat.executeQuery(sql)
            action(rs)
            history.add("${sql.trim { it.isWhitespace() }};")
            rs.beforeFirst()
            rs
        }
    }

    fun query(sql: String, printResult: Boolean = true): ResultSet {
        return context.runQuery { connection ->
            val stat = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)
            val rs = stat.executeQuery(sql)
            if (printResult) {
                printlnPro(ResultSetFormatter(rs, withHeader = true))
            }
            history.add("${sql.trim { it.isWhitespace() }};")
            rs.beforeFirst()
            rs
        }
    }

    fun assertResultSetEquals(expected: String, sql: String) {
        assertEquals(expected, ResultSetFormatter(query(sql)).toString())
    }
}

