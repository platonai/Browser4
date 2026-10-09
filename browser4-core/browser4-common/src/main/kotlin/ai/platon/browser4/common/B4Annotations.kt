package ai.platon.browser4.common

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is experimental and may change in the future."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR
)
annotation class Beta
