# 生成 aria snapshot 相关测试

根据 page-aria-snapshot-ai.spec.ts 生成 aria snapshot 相关测试，忽略无关测试，如果测试失败，需要修改实现。

测试目标是 AriaSnapshotRenderer 组件，保证 AriaSnapshotRenderer 实现 page-aria-snapshot-ai.spec.ts 要求一致，忽略无关测试。

参考 SnapshotServiceE2ETest 模式，编写 AriaSnapshotRendererE2ETest 测试类，启动真实网页服务器，在真实网页上测试。

测试资源源代码目录：
browser4-tests/browser4-tests-common/src/main/resources/static/assets/frames

## Reference

[page-aria-snapshot-ai.spec.ts](../../../logs/page-aria-snapshot-ai.spec.ts)
