package dev.koga.hopi.rules

import dev.koga.hopi.api.HopiRuntimes

/**
 * Rules for this process's HTTP traffic (docs/design/rules.md): a rule matches a request and
 * mocks, fails, delays or edits it, on every source Hopi records, each as far as it can without
 * weakening the app's security. For tests and debug menus; the CLI and the macOS viewer reach the
 * same rules over the bridge. Rules live in memory for this process only, are never persisted, and
 * apply from the moment a call returns. `match` is the shorthand `[METHODS ]URL-PATTERN`
 * (docs/design/rules.md, "Shorthand"): methods such as `GET` or `GET|POST`, then
 * `[scheme://]host[:port]path[?query]`, where `*` in a host matches any run of characters, `*` in a
 * path one segment and `**` any number of them.
 *
 * Validation errors throw [IllegalArgumentException] with the field named; reaching a limit (64
 * rules, 1 MiB per body, 8 MiB of bodies) throws [IllegalStateException]. Thread-safe. Tests call
 * [clear] in their teardown: the rules are process-wide, like the store.
 *
 * A call does nothing while no Hopi runtime is registered, as in a release build: the block still
 * runs, and every call returns `-1`, `false` or an empty list. A debug build's start registers the
 * runtime before anything else (docs/ARCHITECTURE.md, "Start sequence"), so a rule added before
 * it is dropped too, and the start writes one notice line with the number dropped.
 */
public object HopiRules {
    /** A rule that answers matching requests itself, without the network; returns its id. */
    public fun mock(match: String, block: HopiMock.() -> Unit = {}): Long {
        val rule = HopiMock().apply(block)
        val runtime = HopiRuntimes.rules ?: return dropped()
        return runtime.mock(match, rule)
    }

    /** A rule that fails matching requests the way their stack fails them; returns its id. */
    public fun fail(match: String, failure: HopiFailure, block: HopiRuleOptions.() -> Unit = {}): Long {
        val rule = HopiRuleOptions().apply(block)
        val runtime = HopiRuntimes.rules ?: return dropped()
        return runtime.fail(match, failure, rule)
    }

    /** A rule that only holds matching requests back; needs a non-zero delay. Returns its id. */
    public fun delay(match: String, block: HopiDelay.() -> Unit): Long {
        val rule = HopiDelay().apply(block)
        val runtime = HopiRuntimes.rules ?: return dropped()
        return runtime.delay(match, rule)
    }

    /** A rule that rewrites matching requests and/or their responses; returns its id. */
    public fun edit(match: String, block: HopiEdit.() -> Unit): Long {
        val rule = HopiEdit().apply(block)
        val runtime = HopiRuntimes.rules ?: return dropped()
        return runtime.edit(match, rule)
    }

    /** Every rule, in the order they were added. */
    public fun list(): List<HopiRuleInfo> = HopiRuntimes.rules?.list() ?: emptyList()

    /** Enables or disables rule [id]; false when there is none. */
    public fun setEnabled(id: Long, enabled: Boolean): Boolean = HopiRuntimes.rules?.setEnabled(id, enabled) ?: false

    /** Removes rule [id]; false when there is none. */
    public fun remove(id: Long): Boolean = HopiRuntimes.rules?.remove(id) ?: false

    /** Removes every rule. */
    public fun clear() {
        HopiRuntimes.rules?.clear()
    }

    /** An add while no runtime is registered: counted for the start's notice, then `-1`. */
    private fun dropped(): Long {
        HopiRuntimes.countDroppedRule()
        ruleDroppedWithoutRuntime()
        return -1
    }
}

/**
 * Called by each add that found no rules runtime registered, once it is counted. Nothing on Android
 * and the JVM: there a missing runtime is a release build, a test that registers none, an Android
 * app's code that ran before its provider, or a JVM process before or without `Hopi.start`; the
 * start's dropped-rules notice counts those adds. On iOS the start is a call the app makes
 * (`Hopi.shared.start`), so a Debug binary's first such add writes one line to the system log, in
 * case that call is missing.
 */
internal expect fun ruleDroppedWithoutRuntime()
