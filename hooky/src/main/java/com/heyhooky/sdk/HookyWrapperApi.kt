package com.heyhooky.sdk

/**
 * Reserved for Hooky's own wrappers of this SDK (the Flutter plugin): what
 * they need and the public API doesn't show. Not for apps; it may change
 * without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Reserved for Hooky's own wrappers of the SDK (the Flutter plugin).",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
public annotation class HookyWrapperApi
