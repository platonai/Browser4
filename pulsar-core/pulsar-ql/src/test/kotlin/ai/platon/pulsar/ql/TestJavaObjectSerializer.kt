package ai.platon.pulsar.ql

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.dom.nodes.node.ext.uniqueName
import ai.platon.pulsar.ql.common.PulsarObjectSerializer
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.h2.H2Db
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.ql.h2.H2DbConfig
import org.apache.commons.io.FileUtils
import org.h2.engine.SysProperties
import org.h2.tools.Server
import org.h2.util.JdbcUtils
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import kotlin.test.*

class TestJavaObjectSerializer : TestBase() {

    companion object {

        private val baseDir = AppPaths.TEST_DIR.resolve("unittests/TestJavaObjectSerializer")
        val conf = H2DbConfig(baseDir = baseDir, networked = true)
        val remoteDB = H2Db(conf)
        var server: Server? = null
    }

    @BeforeEach
    fun init() {
        try {
            initializeDatabase()
        } catch (e: Throwable) {
            TestBase.logger.info(e.stringify())
        }
    }

    @AfterEach
    fun destroy() {
        destroyDatabase()
        runCatching { FileUtils.deleteDirectory(baseDir.toFile()) }.onFailure { it.printStackTrace() }
    }

    /**
     * This method is called before a complete set of tests is run. It deletes
     * old database files in the test directory and trace files. It also starts
     * a TCP server if the test uses remote connections.
     */
    private fun initializeDatabase() {
        TestBase.logger.info("Initializing database")

        val config = remoteDB.conf
        val args = if (config.ssl) mutableListOf("-tcpSSL", "-tcpPort", config.port.toString())
        else mutableListOf("-tcpPort", config.port.toString())

        args.add("-trace")

        server = Server.createTcpServer(*args.toTypedArray())
        try {
            server?.start()
            server?.let { TestBase.logger.info("H2 Server status: {}", it.status) }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    /**
     * Clean test environment
     * TODO: database destroy causes the SQLContext closing, which is required by other DB connections
     */
    private fun destroyDatabase() {
        server?.stop()
        server?.let { logger.info("[Destroy database] H2 Server status: {}", it.status) }

        FileUtils.deleteDirectory(remoteDB.conf.baseDir.toFile())

        logger.info("Database destroyed")
    }

    @Test
    fun testLocalSerialization() {
        val serializer = PulsarObjectSerializer()

        val baseURI = "https://example.com/"
        val doc = Jsoup.parseBodyFragment("<div>Hello World</div>", baseURI)
        val baseDom = ValueDom.get(doc.body().selectFirst("div"))

        val bytes = serializer.serialize(baseDom)
        val obj = serializer.deserialize(bytes)
        assertTrue(obj is ValueDom)
        val dom = obj as ValueDom

        // logPrintln(dom.outHtml)

        val deserializeBytes = serializer.serialize(dom)
        val deserializeObject = serializer.deserialize(deserializeBytes)
        assertTrue(deserializeObject is ValueDom)
        val dom2 = deserializeObject as ValueDom
        assertTrue { dom == dom2 }

        assertTrue { obj !== deserializeObject }
        assertTrue { dom.element.baseUri() == dom2.element.baseUri() }
        // assertTrue { dom.element.ownerDocument.extension() != dom2.element.ownerDocument() }
    }

    @Test
    fun testNetworkSerialization() {
        val conn = remoteDB.getConnection("testNetworkSerialization")
        val stat = conn.createStatement()
        stat.execute("drop table t if exists")
        stat.execute("create table t(id int, val other)")

        val baseURI = "http://example.com/"
        val doc = Jsoup.parseBodyFragment("<div>Hello World</div>", baseURI)
        val baseDom = ValueDom.get(doc.body().selectFirst("div"))

        val ins = conn.prepareStatement("insert into t(id, val) values(?, ?)")
        ins.setInt(1, 2)
        ins.setObject(2, baseDom, Types.JAVA_OBJECT)
        assertEquals(1, ins.executeUpdate())

        val stat2 = conn.createStatement()
        // val rs = s.executeQuery("select val from t")
        val rs = stat2.executeQuery("select val, id from t")

        assertTrue(rs.next())
        assertTrue(rs.getObject(1) is ValueDom)
        assertTrue((rs.getObject(1) as ValueDom).element.toString().contains("Hello World"))
    }

    @Ignore("Ignored temporary")
    @Test
    fun testNetworkSerialization2() {
        val conn = remoteDB.getConnection("testNetworkSerialization2")
        val stat = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)

        val expr = "sibling > 20 && char > 40 && char < 100 && width > 200"
        val sql = """SELECT
            DOM, DOM_FIRST_HREF(DOM), TOP, LEFT, WIDTH, HEIGHT, CHAR, IMG, A, SIBLING, DOM_TEXT(DOM)
            FROM LOAD_AND_GET_FEATURES('$productIndexUrl', '*:expr($expr)')
            ORDER BY SIBLING DESC, CHAR DESC LIMIT 500"""
        val rs = stat.executeQuery(sql)

        assertTrue(rs.next())
        assertTrue(rs.getObject(1) is ValueDom)
        val dom = rs.getObject(1) as ValueDom
        assertTrue { dom.element.uniqueName.contains("nfItem") }

        rs.beforeFirst()
        printlnPro(ResultSetFormatter(rs).toString())

        printlnPro(sql)
        printlnPro(SysProperties.serializeJavaObject)
        printlnPro(JdbcUtils.serializer.javaClass.name)

        printlnPro(dom.element.uniqueName)
    }

    @Ignore("Ignored temporary")
    @Test
    fun testNetworkSerialization4() {
        val sql = """SELECT DOM_DOC_TITLE(DOM) FROM DOM_SELECT(DOM_LOAD('$productIndexUrl'), '.welcome');"""

        IntRange(1, 50).toList().parallelStream().forEach {
            runNetworkSerialization(sql)
        }
    }

    private fun runNetworkSerialization(sql: String) {
        val conn = remoteDB.getRandomConnection()
        val stat = conn.createStatement()

        val rs = stat.executeQuery(sql)

        assertTrue(rs.next())
        val value = rs.getString(1)
        printlnPro(value)
        assertTrue(value.length > 1)
        assertTrue(SysProperties.serializeJavaObject)
        assertEquals("ai.platon.pulsar.ql.common.PulsarObjectSerializer", JdbcUtils.serializer.javaClass.name)
    }
}

