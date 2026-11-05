# Evaluation of the runtime-split plan

Stage 1 of [`../api-runtime-split-plan.md`](../api-runtime-split-plan.md). Each file answers one item
of the plan's section 4, read-only: nothing was built or run, and every item that needs a build, a
simulator or a device is written as a **[run]** experiment with its commands, to be run at the start
of the stage that needs it (plan section 6).

| File | Item | Read at |
| --- | --- | --- |
| [E1.md](E1.md) | Ktor plugin and the `ktor` source | `e8583b8` |
| [E2.md](E2.md) | iOS capture and the iOS entry | `e8583b8` |
| [E3.md](E3.md) | Android capture and the Gradle plugin | `e8583b8` |
| [E4.md](E4.md) | Rules | `e8583b8` |
| [E5.md](E5.md) | Perf | `e8583b8` |
| [E6.md](E6.md) | UI and the desktop viewer | `e8583b8` |
| [E7.md](E7.md) | Build infrastructure | `e1ef40a` |
| [E8.md](E8.md) | Documentation | `e8583b8` |
| [cross-check.md](cross-check.md) | Re-verification of the claims that decisions and stage order depend on | `e1ef40a` |

`e8583b8` holds the same code as `1c81938`. `e1ef40a` changed only the plugin's functional test
(`HopiPluginFunctionalTest.kt`, its lines after about :36 moved by +10) and added the plan's
section 10; line numbers in a file refer to the commit it was read at. E1 to E6 were written before
the owner decisions D2', D7' and D10 (plan section 10), so their passages on deleting the Ktor plugin
and on the in-app launcher are superseded where those decisions differ. `.reference/` (cited by E1
and E2) is a local, git-ignored checkout of the Ktor 3.5.2 sources.
