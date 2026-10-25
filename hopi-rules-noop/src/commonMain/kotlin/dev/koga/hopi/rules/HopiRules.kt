package dev.koga.hopi.rules

/**
 * The noop twin of `hopi-rules`: the same API, and no rule exists, applies or is stored. Release
 * builds link this one. Each block runs on a builder that is then dropped, so the caller's code
 * behaves the same; every call returns `-1`, `false` or an empty list.
 */
public object HopiRules {
    /** Returns `-1`: no rule is added. */
    public fun mock(match: String, block: HopiMock.() -> Unit = {}): Long {
        HopiMock().apply(block)
        return -1
    }

    /** Returns `-1`: no rule is added. */
    public fun fail(match: String, failure: HopiFailure, block: HopiRuleOptions.() -> Unit = {}): Long {
        HopiRuleOptions().apply(block)
        return -1
    }

    /** Returns `-1`: no rule is added. */
    public fun delay(match: String, block: HopiDelay.() -> Unit): Long {
        HopiDelay().apply(block)
        return -1
    }

    /** Returns `-1`: no rule is added. */
    public fun edit(match: String, block: HopiEdit.() -> Unit): Long {
        HopiEdit().apply(block)
        return -1
    }

    /** Always empty. */
    public fun list(): List<HopiRuleInfo> = emptyList()

    /** Always false: there is no rule. */
    public fun setEnabled(id: Long, enabled: Boolean): Boolean = false

    /** Always false: there is no rule. */
    public fun remove(id: Long): Boolean = false

    /** Does nothing. */
    public fun clear() {}

    /** Returns `-1`: nothing is replayed. */
    public fun replay(transactionId: Long, block: HopiRequestEdit.() -> Unit = {}): Long {
        HopiRequestEdit().apply(block)
        return -1
    }
}
