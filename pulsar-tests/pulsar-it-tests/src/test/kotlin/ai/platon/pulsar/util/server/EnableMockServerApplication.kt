package ai.platon.pulsar.util.server

import ai.platon.pulsar.test.server.MockSiteApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(MockSiteApplication::class)
class EnableMockServerApplication
