package ai.platon.pulsar.basic

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.persist.gora.FileBackendPageStore
import ai.platon.pulsar.skeleton.crawl.TaskLoops
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
class TestBase {
    val logger get() = getLogger(this)

    @Autowired
    lateinit var conf: ImmutableConfig

    @Autowired
    lateinit var session: PulsarSession

    @Autowired
    lateinit var taskLoops: TaskLoops

    @Autowired
    lateinit var webDB: WebDb

    val context get() = session.context

    val globalCache get() = session.globalCache

    val isFileBackendPageStore get() = webDB.dataStorageFactory.getOrCreatePageStore() is FileBackendPageStore
}
