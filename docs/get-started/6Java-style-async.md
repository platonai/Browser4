Java-style Asynchronous Programming
=

[Prev](5URL.md) [Home](1home.md) [Next](7Kotlin-style-async.md)

Browser4's rich API allows us to solve the "load-parse-extract" process with just one line of code in most of our programming scenarios. This article introduces how to use Java-style asynchronous programming to solve the problem of batch web page collection.

```kotlin
object JvmAsync {
    val session = createSession()

    fun loadAll() {
        fromResource("seeds10.txt").parallelStream()
            .map(session::open).map(session::parse).map(FeaturedDocument::guessTitle)
            .forEach { println(it) }
    }

    fun loadAllAsync2() {
        val futures = fromResource("seeds10.txt")
            .asSequence()
            .map { "$it -i 1d" }
            .map { session.loadAsync(it) }
            .map { it.thenApply { session.parse(it) } }
            .map { it.thenApply { it.guessTitle() } }
            .map { it.thenAccept { println(it) } }
            .toList()
            .toTypedArray()
        CompletableFuture.allOf(*futures).join()
    }

    fun loadAllAsync3() {
        val futures = session.loadAllAsync(fromResource("seeds10.txt"))
            .map { it.thenApply { session.parse(it) } }
            .map { it.thenApply { it.guessTitle() } }
            .map { it.thenAccept { println(it) } }
            .toTypedArray()
        CompletableFuture.allOf(*futures).join()
    }

    fun loadAllAsync4() {
        val futures = session.loadAllAsync(fromResource("seeds10.txt"))
            .map { it.thenApply { session.parse(it) }.thenApply { it.guessTitle() }.thenAccept { println(it) } }
            .toTypedArray()
        CompletableFuture.allOf(*futures).join()
    }
}
```

Online code: [kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_3_JvmAsync.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_3_JvmAsync.kt).

------

[Prev](5URL.md) [Home](1home.md) [Next](7Kotlin-style-async.md)
