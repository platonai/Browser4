package ai.platon.pulsar.common

import com.google.common.base.Predicates
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class FlowState {
    CONTINUE, BREAK;

    val isContinue get() = this == CONTINUE
}

/**
 * Both 0 and 200 are good states
 * */
data class CheckState(
    val code: Int = 0,
    val message: String = "",
    val scope: String = ""
) {
    val isOK get() = code == 0 || code == 200
    val isNotOK get() = code != 0
}

/**
 * A result with a message
 * */
class DescriptiveResult<T>(
    val value: T?,
    val message: String = "",
) {
    constructor(message: String) : this(null, message)

    operator fun component1() = value
    operator fun component2() = message

    override fun toString(): String {
        return "{value: $value, message: $message}"
    }
}

/**
 * Smaller value indicates higher priority, keep consistent with PriorityQueue
 *
 * Notice: can not use Int.MIN_VALUE as the highest priority value nor Int.MAX_VALUE as the lowest, choose another one
 * */
enum class Priority5(val value: Int) {
    HIGHEST(Int.MIN_VALUE / 10),
    HIGHER(-1000),
    NORMAL(0),      // NORMAL is the default priority, make sure it is 0
    LOWER(1000),
    LOWEST(Int.MAX_VALUE / 10)
}

/**
 * Smaller value indicates higher priority, keep consistent with PriorityQueue
 *
 * Notice: can not use Int.MIN_VALUE as the highest priority value nor Int.MAX_VALUE as the lowest, choose another one
 * */
enum class Priority13(val value: Int) {
    HIGHEST(Int.MIN_VALUE / 10),
    HIGHER5(-5000),
    HIGHER4(-4000),
    HIGHER3(-3000),
    HIGHER2(-2000),
    HIGHER(-1000),
    NORMAL(0),      // NORMAL is the default priority, make sure it is 0
    LOWER(1000),
    LOWER2(2000),
    LOWER3(3000),
    LOWER4(4000),
    LOWER5(5000),
    LOWEST(Int.MAX_VALUE / 10);

    companion object {
        fun valueOfOrNull(name: String?): Priority13? {
            if (name == null) {
                return null
            }

            return entries.firstOrNull { it.name == name }
        }

        @Throws(IllegalArgumentException::class)
        fun valueOf(value: Int): Priority13 {
            return entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException(
                    "Illegal priority value $value, " +
                        "must be one of ${entries.map { it.value }}"
                )
        }

        fun valueOfOrNull(value: Int): Priority13? {
            return entries.firstOrNull { it.value == value }
        }

        fun lowerPriority(value: Int): Priority13 {
            return entries.sortedBy { it.value }.firstOrNull { it.value >= value } ?: LOWEST
        }

        fun upperPriority(value: Int): Priority13 {
            return entries.sortedBy { it.value }.lastOrNull { it.value <= value } ?: HIGHEST
        }
    }
}

/**
 * Smaller value indicates higher priority, keep consistent with PriorityQueue
 *
 * Notice: can not use Int.MIN_VALUE as the highest priority value nor Int.MAX_VALUE as the lowest, choose another one
 * */
enum class Priority21(val value: Int) {
    HIGHEST(Int.MIN_VALUE / 10),
    HIGHER9(-9000),
    HIGHER8(-8000),
    HIGHER7(-7000),
    HIGHER6(-6000),
    HIGHER5(-5000),
    HIGHER4(-4000),
    HIGHER3(-3000),
    HIGHER2(-2000),
    HIGHER(-1000),
    NORMAL(0),      // NORMAL is the default priority, make sure it is 0
    LOWER(1000),
    LOWER2(2000),
    LOWER3(3000),
    LOWER4(4000),
    LOWER5(5000),
    LOWER6(6000),
    LOWER7(7000),
    LOWER8(8000),
    LOWER9(9000),
    LOWEST(Int.MAX_VALUE / 10)
}

interface StartStopRunnable : AutoCloseable {
    val isRunning: Boolean
    fun start()
    fun stop()
    override fun close() = stop()

    fun restart() {
        stop()
        start()
    }

    @Throws(InterruptedException::class)
    fun await() {
    }
}

class StartStopRunner(val runnable: StartStopRunnable) : AutoCloseable {
    fun start() = runnable.start()
    fun stop() = runnable.stop()
    fun restart() = runnable.restart()
    override fun close() = stop()
}

/** Unsafe lazy, usually be used in single thread */
fun <T> usfLazy(initializer: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.NONE, initializer)

fun sleepSeconds(seconds: Long) {
    try {
        TimeUnit.SECONDS.sleep(seconds)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

fun sleepMillis(millis: Long) {
    try {
        TimeUnit.MILLISECONDS.sleep(millis)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

fun sleep(duration: Duration) {
    try {
        Thread.sleep(duration.toMillis())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

/** Always false and have no static check warning */
fun alwaysFalse(): Boolean {
    return Predicates.alwaysFalse<Boolean>().apply(false)
}

/** Always true and have no static check warning */
fun alwaysTrue(): Boolean {
    return Predicates.alwaysTrue<Boolean>().apply(true)
}

/**
 * This annotation marks the API that is considered experimental.
 * The behavior of such API may be changed or the API may be removed completely in any further release.
 *
 * > Beware using the annotated API especially if you're developing a library, since your library might become binary incompatible
 * with the future versions of the standard library.
 *
 * Any usage of a declaration annotated with `@ExperimentalStdlibApi` must be accepted either by
 * annotating that usage with the [OptIn] annotation, e.g. `@OptIn(ExperimentalStdlibApi::class)`,
 * or by using the compiler argument `-Xopt-in=kotlin.ExperimentalStdlibApi`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS
)
@MustBeDocumented
annotation class ExperimentalApi(val message: String = "")

class PrioriClosable(
    val priority: Int,
    val closeable: AutoCloseable,
) : Comparable<PrioriClosable>, AutoCloseable by closeable {
    override fun compareTo(other: PrioriClosable): Int {
        return priority.compareTo(other.priority)
    }

    override fun hashCode(): Int {
        return 31 * priority + closeable.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PrioriClosable

        if (priority != other.priority) return false
        if (closeable != other.closeable) return false

        return true
    }
}
