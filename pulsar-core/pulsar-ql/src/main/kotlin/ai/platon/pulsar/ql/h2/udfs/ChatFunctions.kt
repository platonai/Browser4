package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.context.SQLContexts
import kotlinx.coroutines.runBlocking

/**
 * Created by Vincent on 17-11-1.
 * Copyright @ 2013-2020 Platon AI. All rights reserved
 */
@Suppress("unused")
@ExperimentalApi("Might be slow")
@UDFGroup(namespace = "DOM")
object ChatFunctions {

    private val sqlContext get() = SQLContexts.create()
    private val configuration get() = sqlContext.configuration

    @UDFunction(description = "Chat with the AI model")
    @JvmStatic
    fun chat(userMessage: String, systemMessage: String): String {
        return runBlocking { sqlContext.chat(userMessage, systemMessage).content }
    }
}
