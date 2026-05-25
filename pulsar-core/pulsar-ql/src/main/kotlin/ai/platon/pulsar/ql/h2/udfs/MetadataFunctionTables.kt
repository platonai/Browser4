package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.ql.common.annotation.H2Context
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.h2.DomToH2Queries
import ai.platon.pulsar.ql.h2.H2SessionFactory
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration

@Suppress("unused")
@UDFGroup(namespace = "META")
object MetadataFunctionTables {
    private val volatileConfig = VolatileConfig()

    @UDFunction(description = "Load a page specified by url from the database, " +
            "return the fields of the page as key-value pairs")
    @JvmStatic
    fun load(@H2Context conn: Connection, configuredUrl: String): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        val page = runBlocking { session.load(configuredUrl) }
        return DomToH2Queries.toResultSet(page)
    }

    @UDFunction(description = "Load a page specified by url from the database, " +
            "fetch it from the internet if absent or expired" +
            "return the fields of the page as key-value pairs")
    @JvmStatic
    fun fetch(@H2Context conn: Connection, configuredUrl: String): ResultSet {
        val (url, args) = URLUtils.splitUrlArgs(configuredUrl)
        val loadOptions = LoadOptions.parse(args, volatileConfig)
        loadOptions.expires = Duration.ZERO

        val session = H2SessionFactory.getSession(conn)
        val page = runBlocking { session.load(url, loadOptions) }
        return DomToH2Queries.toResultSet(page)
    }
}
