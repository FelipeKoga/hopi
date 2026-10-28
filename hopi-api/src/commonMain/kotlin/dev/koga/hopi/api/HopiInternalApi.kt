package dev.koga.hopi.api

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HidesFromObjC

/**
 * Marks declarations that exist for Hopi's own modules. They carry no compatibility promise
 * for consumers, who use the API of `hopi-api`, the Ktor plugin `hopi-ktor` and the Gradle
 * plugin's DSL instead.
 *
 * It also hides what it marks from the Objective-C header of every framework that exports a Hopi
 * module (`HidesFromObjC`; Kotlin/Native only, the JVM and Android ignore it), so Swift sees only
 * the consumer API, and no core or runtime type that a marked declaration names follows it into
 * the header. `HidesFromObjC` accepts only these three targets.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HidesFromObjC
@RequiresOptIn(
    message = "Hopi internal API: used by Hopi's own modules, may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class HopiInternalApi
