# Review ariaSnapshot calculation

Run test ai.platon.browser4.driver.chrome.dom.SnapshotServiceE2ETest.testGetDomAxAndSnapshot, here is the output:

```
{type=TargetTrees, devicePixelRatio=1.5, timingsMs={ax_tree=39, dom_tree=14, dpr=4, snapshot=26, total=85}, options=SnapshotOptions(maxDepth=1000, includeAX=true, includeSnapshot=true, includeStyles=true, includePaintOrder=true, includeDOMRects=true, includeScrollAnalysis=true, includeVisibility=true, includeInteractivity=true), axTree.size=241, snapshotByBackendId.size=190, domByBackendId.size=223, domTree.stats=depth=12, nodes=223, leaves=90, domTree.boundsStats=zero=0, positive=0, missing=223, domTree.bounds.zeroNonZero={zero=0, nonZero=0}, domTree.midBoundsNode1=null, domTree.midBoundsNode2=null, bounds.samples.gt200.count=0, bounds.coords.gt50.sorted=[]}
```
00:43:17.174 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Metrics written | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/snapshot-metrics-6f6a3e70d2580e0358b9cc3ed8b513c1.json
00:43:18.617 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Dom tree node written | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/snapshot-6f6a3e70d2580e0358b9cc3ed8b513c1.yml
00:43:18.831 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Micro tree written | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/dom-state-micro-6f6a3e70d2580e0358b9cc3ed8b513c1.yml
00:43:18.836 [outine#147] INFO  a.p.b.d.c.dom.SnapshotServiceE2ETest - Aria snapshot written | file:///D:/workspace/Browser4/Browser4-4.6/browser4-tests/pulsar-it-tests/./logs/tests/dom-state-aria-snapshot-6f6a3e70d2580e0358b9cc3ed8b513c1.yml

You should check whether the aria snapshot is correct.
