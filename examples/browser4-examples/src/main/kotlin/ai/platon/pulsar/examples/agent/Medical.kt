package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
1. 访问内部测试站点 http://192.168.0.240/#/zhyq/buildingmanage/building/buildingIndex，这是一个信息管理系统的测试环境。
2. 如需登录，等待用户登录并跳转到管理系统后台。
3. 找到楼栋管理模块，cargo run/browser4-cli 录入一条新的信息记录，确保录入过程顺利且数据正确保存。测试过程中，你可以编造数据来进行录入，但请确保数据的合理性。
4. 完成信息录入后，检查系统是否正确显示新录入的信息记录，并验证数据的准确性。
5. 在完成上述步骤后，把你的操作步骤记录下来，形成可重复使用的模板，以便未来再次填写这个表单时可以复用这个模板进行操作。
6. 如果30分钟内不能完全完成任务，则停止测试，并记录下未完成的部分和遇到的困难，以便未来改进测试流程或工具。
7. 最后，编写一个markdown文件，详细记录你在测试过程中发现的所有问题，包括但不限于功能性问题、性能问题、用户体验问题等。每个问题都应该有清晰的描述、重现步骤（如果适用）、预期结果和实际结果。
        """.trimIndent()

    val history = agent.run(task)
    println(history.finalResult)
}
