package ai.platon.pulsar.skeleton.context.support

import org.springframework.context.support.ClassPathXmlApplicationContext

open class ClassPathXmlPulsarContext(configLocation: String)
    : BasicPulsarContext(ClassPathXmlApplicationContext(configLocation)) {
}
