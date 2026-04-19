# Fix NanoDOMTreeNode

在计算 ariaSnapshot 时，丢失了关键信息。

如：在 ai.platon.browser4.driver.chrome.dom.SnapshotServiceE2ETest.writeDOMState 中，

同时打印了 domState.ariaSnapshot 和 domState.serializableTree.toNanoTree().ariaSnapshot。

domState.serializableTree.toNanoTree().ariaSnapshot 很多节点丢失了节点信息。


domState.ariaSnapshot 输出：
20:53:33.321 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Aria snapshot written (unfiltered) | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/aria-snapshot-6f6a3e70d2580e0358b9cc3ed8b513c1.yml

domState.serializableTree.toNanoTree().ariaSnapshot 输出：
20:53:33.329 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Aria snapshot written (nano) | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/aria-snapshot-nano-6f6a3e70d2580e0358b9cc3ed8b513c1.yml

信息丢失：

(未丢失)
```
  - button "Load Users (2s delay)" [ref=e36]
  - button "Load Products (3s delay)" [ref=e37]
  - button "Trigger Loading Error" [ref=e38]
  - button "Clear Content" [ref=e39]
```

（已丢失）
```
  - generic "Load Users (2s delay)" [ref=e36] [cursor=pointer]
  - generic "Load Products (3s delay)" [ref=e37] [cursor=pointer]
  - generic "Trigger Loading Error" [ref=e38] [cursor=pointer]
  - generic "Clear Content" [ref=e39] [cursor=pointer]
```

解释原因并修复。

