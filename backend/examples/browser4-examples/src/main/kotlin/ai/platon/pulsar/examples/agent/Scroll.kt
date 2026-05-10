package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.ActionOptions

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val action = "打开 https://www.amazon.com/b?node=172282，向下滚动10次，每次滚动网页1/10高度，每次滚动报告当前滚动位置和当前的屏幕截图中的内容"
    val actionOptions = ActionOptions(action, multiAct = true)
    var result = agent.act(actionOptions)

    var maxSteps = 100
    while (!result.isComplete && maxSteps-- > 1) {
        result = agent.act(actionOptions)
    }
}
