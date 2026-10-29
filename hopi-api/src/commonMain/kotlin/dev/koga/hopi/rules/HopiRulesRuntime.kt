package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiInternalApi

/**
 * The rules runtime [HopiRules] delegates to once one is registered
 * ([dev.koga.hopi.api.HopiRuntimes.rules]): `hopi-core`'s, which its start sequence registers in a
 * debug build. Each add gets the builder after its block ran and reads it through the members
 * marked [HopiInternalApi]; the runtime validates it, and throws what [HopiRules] documents:
 * [IllegalArgumentException] for a validation error or a `from` naming no record,
 * [IllegalStateException] for a limit. `hopi-api` comes from the app, and the runtime needs it at
 * its own version. On Android the Gradle plugin adds core at the plugin's version and holds
 * `hopi-api` to it in debuggable builds. On iOS both come from the app's shared module
 * (`hopi-start` brings core) at the versions it names, so it names one version for both. Nothing
 * registers a runtime on the JVM before the runtime-split plan's stage 10.
 */
@HopiInternalApi
public interface HopiRulesRuntime {
    /** Adds the mock [rule] for [match]; returns its id. */
    public fun mock(match: String, rule: HopiMock): Long

    /** Adds the fail [rule] for [match]; returns its id. */
    public fun fail(match: String, failure: HopiFailure, rule: HopiRuleOptions): Long

    /** Adds the delay [rule] for [match]; returns its id. */
    public fun delay(match: String, rule: HopiDelay): Long

    /** Adds the edit [rule] for [match]; returns its id. */
    public fun edit(match: String, rule: HopiEdit): Long

    /** Every rule, in the order they were added, built with [HopiRuleInfoFactory]. */
    public fun list(): List<HopiRuleInfo>

    public fun setEnabled(id: Long, enabled: Boolean): Boolean

    public fun remove(id: Long): Boolean

    public fun clear()
}

/** Builds a [HopiRuleInfo] for a rules runtime: its constructor is internal to this module. */
@HopiInternalApi
public object HopiRuleInfoFactory {
    public fun create(
        id: Long,
        label: String?,
        enabled: Boolean,
        priority: Int,
        times: Int?,
        hits: Long,
        exhausted: Boolean,
        match: String,
        action: String,
        unsupported: List<String>,
    ): HopiRuleInfo = HopiRuleInfo(id, label, enabled, priority, times, hits, exhausted, match, action, unsupported)
}
