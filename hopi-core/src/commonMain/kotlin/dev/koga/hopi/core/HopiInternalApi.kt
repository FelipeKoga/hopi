package dev.koga.hopi.core

/**
 * Marks declarations that exist for Hopi's own modules. They carry no compatibility promise
 * for consumers, who use the API of `hopi-ktor` and `hopi-ui` instead.
 */
@RequiresOptIn(
    message = "Hopi internal API: used by Hopi's own modules, may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class HopiInternalApi
