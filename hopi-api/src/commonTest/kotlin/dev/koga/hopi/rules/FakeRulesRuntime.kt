package dev.koga.hopi.rules

/** A rules runtime that answers each add with a fixed id and records the builders and calls it got. */
internal class FakeRulesRuntime : HopiRulesRuntime {
    val calls = ArrayList<String>()
    val rules = ArrayList<HopiRuleOptions>()

    override fun mock(match: String, rule: HopiMock): Long = added("mock $match", rule, 1)

    override fun fail(match: String, failure: HopiFailure, rule: HopiRuleOptions): Long = added("fail $match $failure", rule, 2)

    override fun delay(match: String, rule: HopiDelay): Long = added("delay $match", rule, 3)

    override fun edit(match: String, rule: HopiEdit): Long = added("edit $match", rule, 4)

    override fun list(): List<HopiRuleInfo> = listOf(
        HopiRuleInfoFactory.create(7, "teapot", true, 1, 5, 3, false, "GET */a", "mock 418", emptyList()),
        HopiRuleInfoFactory.create(8, null, false, 0, null, 2, false, "* */b", "fail timeout", listOf("urlsession: no")),
    )

    override fun setEnabled(id: Long, enabled: Boolean): Boolean {
        calls += "setEnabled $id $enabled"
        return id == 7L
    }

    override fun remove(id: Long): Boolean {
        calls += "remove $id"
        return id == 7L
    }

    override fun clear() {
        calls += "clear"
    }

    private fun added(call: String, rule: HopiRuleOptions, id: Long): Long {
        calls += call
        rules += rule
        return id
    }
}
