package ai.platon.pulsar.skeleton.context.support

import org.springframework.context.support.ClassPathXmlApplicationContext

open class ClassPathXmlPulsarContext(applicationContext: ClassPathXmlApplicationContext) :
    BasicPulsarContext(applicationContext) {

    constructor(configLocation: String) : this(ClassPathXmlApplicationContext(configLocation))
}
