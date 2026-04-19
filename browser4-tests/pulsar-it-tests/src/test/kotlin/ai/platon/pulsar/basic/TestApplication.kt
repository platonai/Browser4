package ai.platon.pulsar.basic

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource

@SpringBootApplication
@ComponentScan("ai.platon.pulsar.boot.autoconfigure")
@ImportResource("classpath:test-beans/app-context.xml")
class TestApplication
