package ai.platon.pulsar

import ai.platon.pulsar.boot.autoconfigure.PulsarAutoConfiguration
import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.util.server.EnableMockServerApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(classes = [EnableMockServerApplication::class])
@Import(PulsarAutoConfiguration::class)
class TestBase {

    @Autowired
    lateinit var conf: ImmutableConfig

    @Autowired
    lateinit var session: PulsarSession

    val context get() = session.context

    val taskLoops get() = context.taskLoops

    val webDB get() = context.getBean(WebDb::class)

    val globalCache get() = session.globalCache
}
