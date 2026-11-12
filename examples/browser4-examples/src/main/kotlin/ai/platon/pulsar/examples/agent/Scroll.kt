package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val action = "打开 https://www.amazon.com/b?node=172282，向下滚动10次，每次滚动网页1/10高度，每次滚动报告当前滚动位置和当前的屏幕截图中的内容"

    // agent.run drives the full CLI tool loop until the task completes or the
    // run's step/retry budget is exhausted — no manual act loop is needed.
    val history = agent.run(action)
    println(history.finalResult)
}
