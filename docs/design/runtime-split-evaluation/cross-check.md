# Cross-check of the evaluation

Stage 1 of `docs/design/api-runtime-split-plan.md`. After E1 to E8 were written, one reader
re-verified the claims that decisions or stage order depend on, against the code at `e1ef40a`,
read-only. Each check names its source in the E files, a verdict (confirmed, refuted, unclear) and
the evidence. "Unclear" means it needs a build or a device; the evidence says how to settle it.

Result: 40 confirmed, 5 unclear, 1 refuted, of 46.

## Stage-order problems

1. Section 6 has not been re-planned for section 10 (plan:813-832), and E1-E6 were written before it. D2' overtakes stage 3, the removal of hopi-ktor (plan:589-636): steps 2, 4, 5 and 7 become void and step 1 optional. D7' overtakes stage 8, the automatic launcher. Stage 7 step 3 (plan:690-692) still describes option D, while O1 is provisionally A (plan:826). Stage 1's own rule (plan:570-571) requires re-planning these stages in the plan before they run, and none of the eight answers does it.

2. Stage 3 as written breaks the build between its own steps (moot under D2', but the text is still in the plan). Step 4 deletes PlatformCapture and step 5 removes TransactionSource.KTOR, RuleSource.KTOR and HopiSource.Ktor, while :hopi-ktor still compiles against all of them until step 7 (hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Hopi.kt:4,7,11,88-89,103,107-108,133,143,148-149; Exchange.kt:3,106,108). Not in the E files: step 5 also comes before step 6, while sample/androidApp/src/main/kotlin/dev/koga/hopi/sample/MainActivity.kt:14,182 uses HopiSource.Ktor and sample/shared/src/commonMain/kotlin/dev/koga/hopi/sample/SampleHttp.kt:3-4,68 uses install(Hopi). Fix: do steps 4 to 7 in one commit, or steps 6 and 7 before 4 and 5.

3. Every stage that deletes a twin leaves check red unless it also edits the sample's hard-coded module sets, which the plan never names. These are sample/androidApp/build.gradle.kts:125 (release: exactly the five noops) and :228 (debug: requires :hopi-android, :hopi-core, :hopi-ktor, :hopi-ui, :hopi-rules and :hopi-perf), both run by check at :348 and :350. The same goes for the functional-test fixture pairs (HEAD lines): FT:157 (stand-ins), :227-228 (the shared fixture asks for hopi-ktor-noop and hopi-rules) and :245 (the real/noop loop). The plan mentions the functional tests only in stages 3.7 and 9.2. Stage 5 (the rules pair) and stage 6 (the perf pair: release no longer gets hopi-perf-noop) must update them too. So must stage 7 for the hopi-android stand-ins at FT:26-28, :86, :134, :142-145, :183, :285 and :424-481, which the plugin picks by project name (HopiPlugin.kt:124,134-135).

4. Stage 5 is the first stage that puts hopi-api on the release classpath, through sample/shared/build.gradle.kts:24,97, and it fails verifyHopiSelection unless the same commit allow-lists dev/koga/hopi/core/HopiInternalApi or moves the annotation to another package. The release build is not minified and must hold every class of the selected jars (sample/androidApp/build.gradle.kts:33-39,131-141), and dev/koga/hopi/core/ is forbidden across all dex type_ids (:143-144, :180; DexReader.kt:88). Keep the registry out of dev.koga.hopi.core. The iOS half is unverified: after stage 5, run ./gradlew :sample:shared:verifyIosReleaseExcludesCapture and xcrun nm -m sample/shared/build/bin/iosSimulatorArm64/releaseFramework/Shared.framework/Shared | grep 'dev.koga.hopi.core'. Any kfun line means the iOS check (:161) needs the same exemption.

5. Stage 6 as written does not compile (not in the E files; verified by reading). Moving HopiPerfConfig into hopi-api keeps its internal constructor (hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/HopiPerf.kt:50) out of reach of hopi-perf's PerfEntry.boot, which constructs it (hopi-perf/src/androidMain/kotlin/dev/koga/hopi/perf/internal/AndroidBackend.kt:268). PerfEntry has to survive until stage 7 replaces it with a tool. The provider loads it by name (hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/HopiInitProvider.kt:92; hopi-core/src/androidMain/kotlin/dev/koga/hopi/core/perf/PerfBootstrap.kt:18; keep rule hopi-perf/consumer-rules.pro:3), and verifyDebugCapture requires it (sample/androidApp/build.gradle.kts:250-251,273). Fix: give it a @HopiInternalApi constructor, or let PerfRuntime take PerfSettings directly. In the same stage, hopi-perf's own tests must stop bootstrapping with HopiPerf.install (HopiPerfTest.kt:38,96,116,129,168; IosHangTest.kt:30), and sample/shared/build.gradle.kts:86 and :99 must change as well as :28, since both name perfModule.

6. C4, stage 6 before 7: iOS loses its perf start until stage 7's entry names the perf tool. iOSApp.swift:22-24 has nothing left to call, and HopiPerf.start no longer installs the runtime itself (PerfRuntime.kt:304-305). Once export(perfModule) and api(perfModule) go (sample/shared/build.gradle.kts:86,99), nothing in the framework references hopi-perf's Kotlin code, and Kotlin/Native drops it, as E2.3 shows for unreferenced core code. -verifyPerf then finds no monitor, and the manual positive control (verifyIosReleaseExcludesCapture -Phopi.enabled=true) would fail its perf-Kotlin family (sample/shared/build.gradle.kts:163,175-176). This is reasoned, not run. check stays green. Fix: keep an interim exported perf start until stage 7, or build the start sequence before stage 6.

7. D2' has no stage text (plan:817). The thin hopi-ktor must move its recording code (hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/{Exchange,RequestCapture,BodyCapture,TeedBody,Rules}.kt) into a runtime and switch hopi-ktor/build.gradle.kts:38 from hopi-core to hopi-api. Only then can hopi-ktor-noop go (C1); before that, hopi-core reaches the release APK through sample/shared/build.gradle.kts:94 and fails sample/androidApp/build.gradle.kts:125 and :180. Two questions are open. Which module holds the Ktor recorder: does hopi-network get an edge to ktor-client-core, and a JVM target? D2' says the plugin 'holds no recording logic' but also that 'on the JVM it is the recorder'. And the release set becomes {:hopi-api, :hopi-ktor}, more than plan 3.6's 'hopi-api classes allowed'. The earliest stage for this is 7, since it needs the stage-4 registry and a registered Ktor recorder.

8. C3: each noop constant must change in the stage that deletes its project: HopiPlugin.kt:53 (RUNTIME_NOOP, :163) in stage 7 and :55 (PERF_NOOP, :165) in stage 6. runtime() falls back to dev.koga.hopi:<noop>:0.1.0 (:134-135). That fails to resolve in this build (settings.gradle.kts:19-30, no mavenLocal), but a consumer with mavenLocal would silently resolve it, because ~/.m2/repository/dev/koga/hopi holds every *-noop 0.1.0. TWINS (:166) must lose ktor (in C1's stage), ui (D7'), rules (5) and perf (6); its module substitution at :128 applies whether or not the projects exist.

9. Stage 7, which deletes hopi-android, hopi-urlsession and their noops, must in the same commit: (1) repoint gradle-plugin/hopi-gradle-plugin/build.gradle.kts:116-118, the functionalTest input ../../hopi-android/consumer-rules.pro, which FT:431 copies; a missing input fails check through :124 and root build.gradle.kts:143. (2) Rename the FT stand-ins along with RUNTIME (HopiPlugin.kt:162). (3) Keep the dev.koga.hopi.android.internal and .generated packages, or change hopi-instrument too (HookDescriptors.kt:17,21,24,27; HopiRewriter.kt:41,53; sample/androidApp/build.gradle.kts:183,239,271). (4) Replace the HopiAndroid reads in sample/androidApp/src/main/kotlin/dev/koga/hopi/sample/MainActivity.kt:12,100,152,169,260 (D8). (5) Make every type the generated config names visible to javac: the runtimes take hopi-core as implementation (hopi-android/build.gradle.kts:53) and javac compiles HopiGeneratedConfig inside the app (GenerateHopiConfig.kt:58). (6) Turn both verifyReleaseUsesNoop (:301) and verifyHopiSelection (:308, the one check runs) into the new check. (7) Move the test edges: hopi-android/build.gradle.kts:58-70 (including hopi-instrument at :63 and hopi-ktor at :67) and the OkHttp jar sets at :80-105, and hopi-urlsession/build.gradle.kts:34-44,54-57,61-89. Also decide where the provider lives: in hopi-network it contradicts network { enabled = false } with perf still on (E3 2c).

10. Stage 7 with E2.6's move: moving the URLSession library as it is breaks :hopi-core's iOS compile, because HitCounter.ios.kt:3-8,15,24-38 imports it; split a hit-counter library that stays in core first. The same commit must edit build.gradle.kts:259 (publishedCinterops gives hopi-core oslog and urlsession); otherwise checkPublication expects a hopi-core iOS -cinterop-urlsession.klib that no longer exists (expectedPublication at :277-289). checkUrlSessionMinIos (hopi-core/build.gradle.kts:98-113) moves with the shim tasks.

11. O1 and what check compiles: with option D (stage 7 step 3), check never compiles the Debug-only entry. sample/shared has no test source sets (sample/shared/src holds only androidMain, commonMain, iosCaptureProbe and iosMain), and nothing in check passes -Phopi.enabled; only Xcode Debug builds and the manual positive control do. A broken entry template therefore leaves check green. Option A (plan:826) puts the entry in a module that check compiles, but its name, targets and ABI dump are still undecided (E7 C5). Both options need hopi-api as api plus export in every configuration, because CaptureVerification.swift calls HopiRules unguarded. Both also need a Swift compilation condition tied to HOPI_ENABLED for the Debug A/B build (sample/iosApp/project.yml:69-79), or that build fails to compile.

12. E7's derived order removes the UI (D7') right after stage 2. That also removes the only writer of core's PerfLauncher (hopi-ui/src/androidMain/kotlin/dev/koga/hopi/ui/platform/Platform.android.kt:91-95, called from HopiLauncher.kt:104). hopi-perf can then no longer attach frames and hangs to the current activity (AndroidBackend.kt:188-197), and a frames start answers 'frames attach when an activity next starts' (AndroidBackend.kt:66-69). On the single-activity sample that regresses perfVerify and 'hopi perf start frames' on a device; it is not a check failure (verified by reading). The same stage must drop hopi-ui from publishedModules (build.gradle.kts:248) once hopi-ui/build.gradle.kts:12 stops applying the publishing script. It must also update or delete hopi-ui's per-target dumps (api/android, api/*.klib.api) if its Android and iOS targets go, since checkKotlinAbi runs in check.

13. Two stage gates cannot pass as written. Stage 3's 'no file names dev.koga.hopi.ktor, PlatformCapture, TransactionSource.KTOR, HopiSource.Ktor or X-Hopi-Pass-Through' (plan:634-635) is hit by the plan itself and by history passages in README.md, docs/ARCHITECTURE.md and docs/design/{android-capture,rules,urlsession-capture}.md (git grep). Stage 9's grep for noop, twin, Noop and parity (plan:734-737) hits 17 live benchmark scripts: benchmark/android-capture/{run.py,abtable.py,e2e/check.py,e2e/run.sh}, benchmark/device/scripts/{ios-ab.sh,ios-matrix.sh,ios.py,ios_hold.sh,matrix.sh,matrix2.sh,paired.py}, benchmark/perf/{bench,idle}.py and benchmark/urlsession/e2e/{ab.sh,abtable.py,check.py,run.sh}. It also hits 4 tracked .pyc files (run, e2e/check, idle and abtable; E1 says five). Both gates need explicit exclusions.

14. The base check command: one missing task path fails the whole invocation, so every stage that deletes or renames a task must edit README.md:560-564 and plan section 7 in the same commit. That means :hopi-ktor-noop:jvmTest (C1's stage), :hopi-perf-noop:jvmTest (stage 6), :hopi-urlsession:jvmTest, :hopi-urlsession-noop:jvmTest, :hopi-android:testAndroidHostTest and :sample:androidApp:verifyReleaseUsesNoop (stage 7), :hopi-ui-noop:jvmTest (D7') and :checkNoopParity (stage 9, if the task is deleted). The explicit :checkNoopParity, :checkPublication and functionalTest tasks already run inside check (build.gradle.kts:139,143,329).

15. Edge inventory, with the stage that cuts or moves each edge (from git grep "project(':hopi-" over *.kts plus plugin, file and reflective edges; verified). Main edges: hopi-rules→core (hopi-rules:38; stage 5, module deleted), hopi-urlsession→core (:48; stage 7), hopi-android→core (:53; stage 7, into hopi-network), hopi-ktor→core (:38; C1's stage, becomes hopi-api). These stay: hopi-perf→core (:66), hopi-ui→core (:55), hopi-connect→core as api (:23), hopi-cli→connect and core (:35,37), hopi-desktop→connect, ui and core (:24,26,28). In sample/shared: urlsession at :21/:83/:119 (stage 7), rules at :24/:84/:97 (stage 5), perf at :28/:86/:99 (stage 6), ktor at :94 (C1's stage), ui at :95 (D7'), core and the probe directory at :116/:120 (stage 7). Edges the plugin injects: the runtime at HopiPlugin.kt:53 (stage 7), perf at :55 (stage 6), and TWINS at :166 (pair by pair, then gone in stage 9). Test-only edges: hopi-android androidHostTest → hopi-ktor (:67), ktor-client-android and ktor-client-encoding (:68-69), dev.koga.hopi:hopi-instrument (:63, from the included build per settings.gradle.kts:32-33) and the OkHttp jar sets (:80-105), all moving to hopi-network in stage 7 (:67 stays under D2'). hopi-urlsession iosTest → hopi-ktor (:56), ktor-client-darwin (:55) and the fixtures cinterop (:34-44, :61-89), all in stage 7. hopi-cli test → hopi-ktor (:41) and ktor-client-mock (:44), optional under D2'. The parity classpaths go with their noops: hopi-ktor-noop→hopi-ktor (:58, configuration at :46), hopi-perf-noop→hopi-perf (:53, at :41), hopi-urlsession-noop→hopi-urlsession (:53, at :41); only ktor and perf have a DefaultsParityTest, and urlsession's equivalent is HopiUrlSessionNoopTest.kt:51-71. File edges: functionalTest → hopi-android/consumer-rules.pro (plugin build :116-118; stage 7). The benchmark/fixtures source directory (hopi-core:54, hopi-ktor:50, hopi-ui:68, hopi-cli:28) and hopi-cli's test resources from hopi-connect (:30) need no cut. Reflective edges: HopiInitProvider.kt:92 → PerfEntry (PerfBootstrap.kt:18; replaced by the stage-7 tool list) and :174 → HopiGeneratedConfig (stage 7). No other module imports the rules or perf consumer API; only the sample does.

## Checks

### 1. E2.3 bullet 1 (O1): confirmed

**Claim.** Kotlin code of a non-exported project dependency that nothing references is dropped even from the Debug framework. RuleEngine.resetForTests() and PlatformCapture.resetForTests() have 0 symbols, RuleEngine# has 12 and UrlSessionCapture#start has 1.

**Evidence.** (verified, artefact) xcrun nm -m on the Shared.framework.o member of sample/shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Shared (built 2026-10-26 03:12 with the flag): 'resetForTests' 0 and kfun:dev.koga.hopi.core.rules.RuleEngine# 12. PlatformCapture keeps 12 symbols (cover, covers, honorsPassThrough, markPassThrough, $instance) but not resetForTests, which is public and called only from tests (hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/capture/PlatformCapture.kt:66, .../rules/RuleEngine.kt:172). Provenance: PlatformCapture.kt has a single commit, a5d160b, authored 2026-10-25 19:40, before the build, and both functions exist at that commit (RuleEngine at :187). Small correction: UrlSessionCapture#start has 2 symbols (start and start$default), not 1.

### 2. E2.3 bullet 2; E5.5 (O1): confirmed

**Claim.** The native code of the cinterop static libraries enters a static framework as archive members, outside Kotlin's dead-code elimination (hopi_urlsession.o, hopi_perf.o). HopiPerfCadence is defined only in hopi_perf.o.

**Evidence.** (verified, artefact) ar -t on the Debug Shared lists Shared.framework.o, hopi_urlsession.o and hopi_perf.o. nm: all 24 HopiPerfCadence lines are in hopi_perf.o and 0 are in Shared.framework.o; hopi_urlsession.o has 139 _hopi_us_ symbols. Caveat for E2.7: the pattern '_hopi_perf_' also matches Kotlin cinterop stubs (_dev_koga_hopi_perf_internal_native_hopi_perf_*_wrapper, 102 in Shared.framework.o plus 45 native symbols, which makes the 147 baseline). That family therefore mixes Kotlin symbols, which DCE can remove, with native ones.

### 3. E2.3 bullet 2 (open item); E5 X1: unclear

**Claim.** An unreferenced runtime's native member still lands in the framework, so nm on the framework sees every runtime that was on the link classpath.

**Evidence.** (unverified: needs a link) In today's Debug framework both members are referenced. To settle it, use E2's probe: ./gradlew -I $P/init.gradle.kts :e2probe:linkDebugFrameworkIosSimulatorArm64 -Pe2.variant=unref, then run ar -t $F | grep '^hopi_' and nm -m $F | grep -c _hopi_perf_. If the member is listed, the archive is copied whole. If it is absent, Kotlin/Native filters libraries by use, and only the E2.8 classpath guard can catch a runtime on the classpath.

### 4. E2.3 bullet 3: confirmed

**Claim.** External (Maven) libraries enter a Debug framework whole, as compiler caches: 79 cache members, 20 of them Ktor, none of them Hopi. The Release framework has none.

**Evidence.** (verified, artefact) ar -t on the Debug Shared: 79 members named '*-cache.a.o', 20 of them io.ktor:*, none named hopi or koga. sample/shared/build/bin/iosSimulatorArm64/releaseFramework/Shared.framework/Shared (rebuilt 2026-10-28 08:21 from HEAD code, without the flag) has only Shared.framework.o. The consequence for a consumer that takes Hopi from Maven (every runtime linked whole into Debug) is still unverified: there is no such consumer build.

### 5. E2.3 bullet 4; plan 3.4: confirmed

**Claim.** No runtime code runs until something calls it: the .m files have no +load, +initialize or constructor attribute, and @EagerInitialization is unused. So the runtimes cannot register themselves, and the iOS entry must name the tools.

**Evidence.** (verified) git grep for '+ (void) load', '+ (void) initialize' and '__attribute__((constructor' over *.m and *.h finds nothing (hopi-core/src/nativeInterop/objc/hopi_urlsession.m, hopi-perf/src/nativeInterop/objc/hopi_perf.m, hopi-urlsession test fixtures, benchmark/urlsession/ab.m). EagerInitialization appears only in docs/design/api-runtime-split.md:171. That Kotlin/Native initialises globals lazily is a language rule, not re-verified here.

### 6. E2.4 bullet 1; E4.2.3 'Swift': confirmed

**Claim.** export() puts every public declaration of the exported module in the header, including the @HopiInternalApi ones: PerfSettings with 6 members, MonitorBackend with 13, PerfRuntimeController with 12.

**Evidence.** (verified, artefact) Debug Headers/Shared.h: SharedPerfSettings at :380-387 (an init and 5 properties), SharedMonitorBackend with 13 swift_name members, SharedPerfRuntimeController with 12. All six public common and iOS declarations of hopi-perf are in the header: HopiPerf, HopiMonitor and HopiPerfConfig (hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/HopiPerf.kt:19,47,50), and PerfSettings, MonitorBackend and PerfRuntimeController (.../internal/PerfRuntime.kt:30-31,50-51,102-103), the last three @HopiInternalApi. Exported at sample/shared/build.gradle.kts:86, api at :99.

### 7. E2.4 bullet 2; E2.5 bullet 2; E2 correction 3: confirmed

**Claim.** When an exported signature names a type from a non-exported dependency, that type is exported with all its members, with a module prefix, and the types those members name follow. The Debug header has 32 SharedHopi_core* declarations (24 classes, 8 protocols), and PerfData has 35 members. This refutes plan 3.4 D's 'may be exported without its members'.

**Evidence.** (verified, artefact) Debug Shared.h has 24 '@interface SharedHopi_core*' and 8 '@protocol SharedHopi_core*'. SharedHopi_corePerfData (:1048) has 35 member lines, 34 of them with a swift_name. It is reached from PerfRuntimeController.data (:346, :371) and MonitorBackend attachData:lease: (:290). hopi-core is only an implementation dependency: hopi-perf/build.gradle.kts:66 (whose comment says 'none of its types are in this module's API') and sample/shared/build.gradle.kts:120. The open detail in plan 3.4 (plan:211-214) is wrong as stated.

### 8. E2.5 bullet 2 ('expected identical'); O1: unclear

**Claim.** The same holds when the exported declaration belongs to the consumer module itself (option D's entry naming a core type).

**Evidence.** (unverified: the artefacts hold no such case) The sample's own public API names no type from a non-exported module: the only module prefix in the Debug Shared.h is Hopi_core, and every such type comes from hopi-perf. To settle it, run E2's entry probe: ./gradlew -I $P/init.gradle.kts :e2probe:linkDebugFrameworkIosSimulatorArm64 -Pe2.variant=entry, then grep the probe header for swift_name Hopi, HopiOwnConfig, Hopi_perfHopiPerfConfig and Hopi_coreHopiStore, and typecheck both Swift calls with xcrun swiftc -typecheck. The template decision does not depend on the answer: a configuration class of its own with primitive members only. Section 10's O1 = A (plan:826) makes the same choice ('its configuration a type of its own').

### 9. E2.4 bullet 3; E2.1 1.2: confirmed

**Claim.** hopi-urlsession and hopi-rules leak nothing into the header: all 32 prefixed types come from hopi-perf's internals.

**Evidence.** (verified, artefact) The 32 Hopi_core names are all perf model types (CpuSample, EventRing, Frame*, HangEvent, PerfData, PerfController, PerfStatus, SeriesRing, Sequenced and so on). urlsession and rules contribute only HopiUrlSession, HopiUrlSessionConfig and the rules builders. hopi-ktor and hopi-ui are implementation dependencies (sample/shared/build.gradle.kts:94-95) and do not appear in the header.

### 10. E2.5 bullet 1: confirmed

**Claim.** The sample already uses option D's mechanism: SampleCaptureProbe and SampleCaptureWatch, from the Debug-only src/iosCaptureProbe, are in the Debug header and in neither Release header.

**Evidence.** (verified, artefact) The Debug Shared.h has SampleCaptureProbe (3 members) and SampleCaptureWatch. The Release Shared.h (2026-10-28) has 0. nm finds 0 in Release and 13 in Debug. The source directory is gated at sample/shared/build.gradle.kts:116.

### 11. E2.1 1.4; E2 correction 4: confirmed

**Claim.** An exported hopi-api puts one Objective-C class per exported class into Release. Today's noop Release framework has 15 _OBJC_CLASS_$_SharedHopi* classes, so plan 3.6.1 item 2's 'no Objective-C class of its own' is wrong.

**Evidence.** (verified, artefact) nm of the Release Shared.framework.o (HEAD code) lists 15 defined classes: SharedHopiDelay, Edit, Failure, Mock, Monitor, Perf, PerfConfig, RequestEdit, ResponseEdit, RuleInfo, RuleOptions, Rules, Source, UrlSession and UrlSessionConfig. Plan:311-313 needs rewording: hopi-api brings no native Objective-C class, but the classes Kotlin/Native generates for exported types are there.

### 12. E2.1 1.4; E2 correction 6: confirmed

**Claim.** Swift's unguarded HopiRules calls need hopi-api as api(...) plus export(...) in every configuration. Plan 3.2's commonMain implementation(hopi-api) serves Kotlin callers only.

**Evidence.** (verified) sample/iosApp/iosApp/CaptureVerification.swift:172-257 calls HopiRules.shared with no #if. This compiles in Release today only because the noop is both api and exported (sample/shared/build.gradle.kts:84,96-97). KGP accepts export() only for api dependencies (a KGP rule, not re-run here). The snippet in question is plan:108-109.

### 13. E2.5 bullet 4; E2 correction 6: confirmed

**Claim.** The iOS entry needs hopi-core on its compile classpath, because the runtimes take core as an implementation dependency.

**Evidence.** (verified) hopi-urlsession/build.gradle.kts:48, hopi-perf/build.gradle.kts:66 and hopi-android/build.gradle.kts:53 all declare implementation(project(':hopi-core')). The sample adds core itself (sample/shared/build.gradle.kts:120) for its Debug-only probe.

### 14. E2.4 mitigation; E4.2.3 'Swift': confirmed

**Claim.** Nothing in the repository hides declarations from Objective-C (no HiddenFromObjC, HidesFromObjC, ObjCName or ShouldRefineInSwift), and Kotlin/Native 2.4.20's stdlib has HiddenFromObjC and HidesFromObjC.

**Evidence.** (verified) git grep finds none of them outside docs. The names are in ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20/klib/common/stdlib/default/linkdata/package_kotlin.native/00_native.knm, the common stdlib metadata (which also has EagerInitialization, RefinesInSwift and ObjCName). That suggests, but does not prove, that they compile from commonMain. Whether HidesFromObjC can meta-annotate HopiInternalApi (targets CLASS to TYPEALIAS, hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/HopiInternalApi.kt:12-18) is still a [run] item: E2's 'hidden' variant.

### 15. E2.8 bullet 1 and table: confirmed

**Claim.** A framework's Debug and Release binaries share one compilation and one dependency set, so whatever -Phopi.enabled adds reaches every Release link in that invocation (3.6.1 item 1). embedAndSignAppleFrameworkForXcode picks the build type from CONFIGURATION or KOTLIN_FRAMEWORK_BUILD_TYPE.

**Evidence.** (verified by reading) There is one binaries.framework per target (sample/shared/build.gradle.kts:75-88), and every Hopi dependency is chosen per source set from one property (:18-28, :94-95, :116-120). The positive control requires the families in the Release framework built with the flag (:174-179), and docs/ARCHITECTURE.md:313-314 records 99/36/185 there. The strings of XcodeEnvironment.class in the KGP 2.4.20 jar include CONFIGURATION, KOTLIN_FRAMEWORK_BUILD_TYPE, DEBUG, RELEASE, SDK_NAME, ARCHS, TARGET_BUILD_DIR and FRAMEWORKS_FOLDER_PATH. These are names only; how KGP uses them is E2.8 step 1, a [run] item.

### 16. E2 corrections 1-2; E7 4B: confirmed

**Claim.** Only iOSApp.swift and CaptureTests.swift call a start, although stage 7 step 3 lists four files. project.yml must change for the HOPI_ENABLED=false Debug A/B build.

**Evidence.** (verified) sample/iosApp/iosApp/iOSApp.swift:10-17 (HopiUrlSession.start) and :22-24 (HopiPerf.install); sample/iosApp/iosAppCaptureTests/CaptureTests.swift:15-16. ThirdPartySDK.swift:1 imports only Foundation, and HopiUrlSession appears there only in the comment at :5. sample/iosApp/project.yml:75-79 runs Gradle without the flag for Debug with HOPI_ENABLED=false, so a Hopi.shared.start inside #if DEBUG finds no symbol (the compile failure itself is unverified: E7 X5). This holds for section 10's option A as much as for D.

### 17. E2.6; E4.2.4: confirmed

**Claim.** Moving the URLSession library out of core needs a split first: core's iOS rule engine uses the library's hit counter.

**Evidence.** (verified) hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/rules/HitCounter.ios.kt:3-8 imports dev.koga.hopi.core.internal.urlsession.hopi_us_hits_* and HOPI_US_CLAIM_* (the package comes from hopi-core/src/nativeInterop/cinterop/urlsession.def:2); :15 makes newHitCounter() a NativeHitCounter, defined at :24-38. No other core file outside core/urlsession/ imports that package.

### 18. E1.6 bullet 1 (O3): confirmed

**Claim.** hello carries protocol 1 and hosts require exact equality. Dropping a source value therefore needs no bump, and a bump would stop old and new peers from connecting.

**Evidence.** (verified) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/bridge/BridgeSession.kt:789 writes field('protocol', PROTOCOL_VERSION), and :835 sets PROTOCOL_VERSION = 1. hopi-connect/src/main/kotlin/dev/koga/hopi/connect/BridgeClient.kt:63-64 throws when the protocol differs, and :159 sets PROTOCOL = 1. The CLI (hopi-cli/src/main/kotlin/dev/koga/hopi/cli/Main.kt:3,439) and the desktop viewer both use this client. Moot under D2' (plan:817, :827): 'ktor' stays.

### 19. E1.6 bullet 2: confirmed

**Claim.** Host decoders accept any source value: hopi-connect keeps it raw (BridgeDecoder.kt:54,86), the CLI prints it raw (Render.kt:46), the UI shows unknown values as sent (Text.kt:43-50), HAR writes _source raw (SessionExport.kt:162), summary lines cut it at 13 chars (TransactionJson.kt:18,228), and rule lines keep sources as strings (RuleClient.kt:175-195).

**Evidence.** (verified) hopi-connect/src/main/kotlin/dev/koga/hopi/connect/BridgeDecoder.kt:52 and :81 read source = json.str('source'). Line correction: :52 and :81, not :54 and :86. Also hopi-cli/src/main/kotlin/dev/koga/hopi/cli/Render.kt:46 (takeIf it != 'ktor'), hopi-ui/src/commonMain/kotlin/dev/koga/hopi/ui/format/Text.kt:43-49 (else -> source), the filter at hopi-ui/src/commonMain/kotlin/dev/koga/hopi/ui/list/TransactionRows.kt:111, hopi-desktop/src/main/kotlin/dev/koga/hopi/desktop/SessionExport.kt:162, hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/json/TransactionJson.kt:18,228, and hopi-connect/src/main/kotlin/dev/koga/hopi/connect/RuleClient.kt:188,193 (strings). No host maps a transaction source to an enum.

### 20. E1.6 bullet 3: confirmed

**Claim.** The places that reject unknown sources are the app's RuleJson (so an older host sending 'ktor' gets BAD_REQUEST), the CLI --source option and the desktop rule form (both through RuleSource.of). Their help and error texts list ktor.

**Evidence.** (verified) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/RuleJson.kt:48-50; RuleSource.of at .../rules/RuleModel.kt:19; hopi-cli/src/main/kotlin/dev/koga/hopi/cli/RuleArgs.kt:38; hopi-desktop/src/main/kotlin/dev/koga/hopi/desktop/RuleForm.kt:133; texts at hopi-cli/.../CliArgs.kt:225 and hopi-desktop/.../RulesPanel.kt:338. hopi-cli/src/test/kotlin/dev/koga/hopi/cli/RuleCliTest.kt:96-111 asserts that --source cronet throws a CliException (a usage error); it checks the message, not 'exit 1' as E1 says.

### 21. E1.6 bullet 4: confirmed

**Claim.** No recorded fixture carries source ktor. capture-cli.ndjson has 9 okhttp and 12 urlconnection, and the other NDJSON files carry no source.

**Evidence.** (verified) git grep for '"source":"ktor"' over *.ndjson and *.json finds nothing; the only mention is prose at docs/ARCHITECTURE.md:1181. Among the tracked NDJSON files, only benchmark/android-capture/2026-10-24/e2e/capture-cli.ndjson has a source field. Count correction: it holds 11 records, 5 okhttp and 6 urlconnection. E1's 9 and 12 count field occurrences, including each record's nested detail.

### 22. E1.1.6, Model/engine row: confirmed

**Claim.** Removing RuleSource.KTOR (ordinal 0) is harmless: bySource is rebuilt from RuleSource.entries, and no native code reads an ordinal.

**Evidence.** (verified) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/RuleModel.kt:12. RuleEngine.kt:24,31 index bySource by ordinal, :43 sizes EMPTY by entries, and :220-224 rebuild it on every publish. iOS reads RuleSource.URL_SESSION by name (hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/UrlSessionRules.kt:94). Hosts write wireName (RuleArgs.kt:173, RuleForm.kt:252). Moot under D2'.

### 23. E1.4.1; E1.4.3 'Cap' (O4); plan 3.8.1: confirmed

**Claim.** On Android, a request that names its own Accept-Encoding is recorded by OkHttp and HttpURLConnection capture as the server's gzip bytes, with Content-Encoding kept. OkHttp's own gzip is recorded decoded. The per-body cap counts the bytes handed over, which are the encoded ones.

**Evidence.** (verified) hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/HopiOkHttpInterceptor.kt:31-38 (the last application interceptor, which copies what the app reads) and :376-379 (TeeSource over original.source() with BoundedCapture(record.responseCaptureCap)). Tests in hopi-android/src/androidHostTest/kotlin/dev/koga/hopi/android/: AndroidRulesTest.kt:94-102 (1f 8b and Content-Encoding: gzip), :299-311 (OkHttp), :586-597 (HttpURLConnection, which 'never decodes a gzip the app asked for itself'); AndroidCaptureTest.kt:132-139 (OkHttp's own gzip recorded decoded) and :477-495 (today the plugin records the request decoded, with source ktor).

### 24. E1.4.1 caveat; BodyDecoding.kt KDoc: unclear

**Claim.** Android's own HttpURLConnection decodes gzip only when it asked for it itself. The host tests run the JDK's connection instead.

**Evidence.** (unverified: device behaviour) The host tests load sun.net.www.protocol.http.HttpURLConnection (AndroidCaptureTest.kt:446). The claim rests on the KDoc at hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/BodyDecoding.kt:11-13. To settle it, run E1's R2: add a sample scenario that fetches /gzip?kb=512 through the fake SDK's HttpURLConnection, once with its own Accept-Encoding and once without; then ./gradlew :sample:androidApp:installDebug, adb reverse tcp:8765 tcp:8765, and hopi get <id> --json for each. Base64 starting 'H4sI' with Content-Encoding for the first, and text without the header for the second, confirms it. A gzip body without the header would break heldCodings' invariant.

### 25. E1.4.2; plan 3.8.1 ('to verify, E1.4'): unclear

**Claim.** iOS is very likely unaffected: URLSession hands over decoded bodies even when the request names its own Accept-Encoding. No test covers that case.

**Evidence.** (unverified: no test) hopi-urlsession/src/iosTest/kotlin/dev/koga/hopi/urlsession/UrlSessionCaptureTest.kt:398-405 covers only URLSession's own Accept-Encoding (fixture at hopi-urlsession/src/nativeInterop/objc/test/hopi_fixtures.m:163-165). git grep -i accept-encoding in hopi-urlsession/src/iosTest and hopi-core/src/iosTest finds nothing. Supporting evidence: the IR strings table (default/ir/strings.knt) of ktor-client-darwin-iosArm64Main-3.5.2.klib contains dropCompressionHeaders and readHeaders (see .reference/ktor-3.5.2/ktor-client-core/commonMain/io/ktor/client/utils/HeadersUtils.kt:13-31), and the Darwin engine is covered with cover, not coverWithPassThrough (hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/UrlSessionCapture.kt:95,143). To settle it, run E1's R3: add a request whose Accept-Encoding the test sets and an HttpClient(Darwin) with ContentEncoding, then run ./gradlew :hopi-urlsession:iosSimulatorArm64Test --tests '*UrlSessionCaptureTest*'. 1f 8b in the record means iOS needs a decoder too.

### 26. E1.4.3 'What exists' and missing piece 1: confirmed

**Claim.** BodyDecoding offers DECODABLE_CODINGS (gzip, x-gzip, deflate), heldCodings (okhttp and urlconnection records plus their Content-Encoding header) and a bounded decodeBody (stops at maxBytes+1, one doubling array; JVM and Android identical, iOS returns null). All three are internal to core.rules, and only the mock copy uses them.

**Evidence.** (verified) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/BodyDecoding.kt:7, :17-24 (source check at :18) and :34 (expect). hopi-core/src/jvmMain/kotlin/dev/koga/hopi/core/rules/BodyDecoding.jvm.kt:10-55 is identical to androidMain's BodyDecoding.android.kt:10-55; iosMain's BodyDecoding.ios.kt:4 returns null. The only users are RuleJson.kt:117,121,143,146 and EncodedBodyMockTest.kt:111-120. The one public entry, RuleJson.encodedBodyRefusal (:115-118), only refuses. hopi-ui, hopi-cli and hopi-desktop cannot reach the internal functions.

### 27. E1.4.3 missing piece 2: confirmed

**Claim.** A held body cut at the cap does not decode with today's decodeBody (a truncated gzip stream throws an IOException, which becomes null), so decoding where the body is shown needs a prefix mode.

**Evidence.** (verified by reading) BodyDecoding.jvm.kt:10-19 catches IOException and returns null. readAtMost (:45-55) reads to the end of the stream, so the EOFException that GZIPInputStream throws on a cut stream aborts the decode. Not run.

### 28. E1.4.3 'Encoded flag' and 'Cap': confirmed

**Claim.** CapturedBody has no encoded flag and needs none. totalBytes, truncated and the store's byte cap describe the held bytes, which are the encoded ones. The per-body cap lives only in CaptureInstallation.Installed, and hello does not carry it.

**Evidence.** (verified) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/model/HttpTransaction.kt:20-31 (bytes, totalBytes, truncated, contentType, skippedReason, originalCapturedBytes) and :141-143 (capturedBytes); CaptureInstallation.kt:22,49 (min(maxBodyBytes, maxTotalBytes/2)); HopiStore.kt:57-69 (maxEntries and maxTotalBytes only); the hello fields at BridgeSession.kt:787-803 carry no cap. Using heldCodings as the flag relies on OkHttp and HttpURLConnection dropping Content-Encoding when they decode. The body half is tested (AndroidCaptureTest.kt:132-139); the header half is unverified.

### 29. E1.4.3 'Where it runs'; E1 correction 8: confirmed

**Claim.** Bodies are shown or served in five places: hopi-ui's DetailFormatter (off the main thread, cached), the CLI's human-readable get, the desktop's HAR export, the bridge get (held bytes as utf8 or base64) and cURL (which reproduces the request only and already adds --compressed).

**Evidence.** (verified) hopi-ui/src/commonMain/kotlin/dev/koga/hopi/ui/detail/DetailModel.kt:102 (DetailFormatter), :140 (bodyView(body, side)), :234 (formatBody), :254 (textOrNull), :170 (4 Mi-char budget). HopiScreen.kt:114 (Dispatchers.Default) and :299 (withContext(formatDispatcher)). The desktop panes use the same formatter on Dispatchers.Default (hopi-ui/src/jvmMain/kotlin/dev/koga/hopi/ui/desktop/HopiPanes.kt:135,143,147). hopi-cli/src/main/kotlin/dev/koga/hopi/cli/Render.kt:175-202 prints 'binary … --json has it as base64'. hopi-desktop/src/main/kotlin/dev/koga/hopi/desktop/SessionExport.kt:131-149,176-187 writes content.size = reportedBytes and the held bytes as text. hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/json/TransactionJson.kt:259-289, and BridgeSession.kt:809-810 ('encoded only when written'). hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/curl/Curl.kt:32,44,51 (--compressed). Under D7', the desktop panes are the only display left in a UI; O4 itself is settled by D2' (plan:827).

### 30. E1.4, E1.5; plan 3.8.1: confirmed

**Claim.** Today the Ktor plugin records exactly the requests that both go through a covered engine and name their own Accept-Encoding. It records them decoded and bounded, and marks them so the platform recorder lets them pass.

**Evidence.** (verified) hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Hopi.kt:88-89 (covered with no Accept-Encoding returns at once), :103-109 (source KTOR and PASS_THROUGH_HEADER), :202 (isEncoded selects the decoded plan), :264 (BoundedBuffer(maxBodyBytes)). hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/AndroidCapture.kt:129-133 (coverWithPassThrough); HopiOkHttpInterceptor.kt:46-47; UrlConnectionRecorder.kt:229-234; and the two callers E1 added, HopiHttpURLConnection.kt:177,182 and HopiHttpsURLConnection.kt:179,184.

### 31. E7.3 table; E7 correction 3: confirmed

**Claim.** Today ABI validation runs on the 12 twins and on hopi-connect, which is not published. hopi-core is published and has never had it, so O7's 'every published module (as today)' does not describe today.

**Evidence.** (verified) git grep abiValidation finds the 12 twin build files and hopi-connect/build.gradle.kts:14. hopi-core/build.gradle.kts:12-56 has none, there is no hopi-core/api, and git log -- hopi-core/api is empty; hopi-core is published (build.gradle.kts:244). The dump line counts match E7: ktor 33/51/33, ui 8/10/8, urlsession 28/45/28, android 6, rules 117/153/117, perf 48/63/43, connect 521. Section 10's O7 ('every published module', plan:829) would newly cover hopi-core, against E7's recommendation to exempt it. That is the owner's decision; check passes either way.

### 32. E7.3 (its X1, marked unverified there): confirmed

**Claim.** KGP 2.4.20 wires each module's checkKotlinAbi into that module's check, so deleting the parity tasks drops no ABI check.

**Evidence.** (verified from bytecode, not run) javap of org/jetbrains/kotlin/gradle/plugin/abi/internal/AbiValidationActionKt$AbiValidationSetupAction$1 in ~/.gradle/caches/.../kotlin-gradle-plugin-2.4.20-gradle96.jar: when isActivated, it calls TaskContainer.named('check', …$1$1), and $1$1 calls getCheckTaskProvider and Task.dependsOn. KotlinProjectExtension.abiValidation() and abiValidation(Action) both call AbiValidationExtensionImpl.activate. X1 can become an optional confirmation: ./gradlew :hopi-connect:check --dry-run | grep checkKotlinAbi.

### 33. E7.3 'hopi-core': confirmed

**Claim.** hopi-core's public surface is about 830 public declarations (790 in commonMain across 49 files), with 148 const val and 6 inline fun; 42 commonMain files use @HopiInternalApi.

**Evidence.** (verified, line counts) 864 lines start with 'public' in hopi-core/src/*Main, 811 of them in commonMain, spread over 43 of its 49 files. There are 148 const val (92 public) and 6 inline fun (none public), and 42 commonMain files contain @HopiInternalApi. If O7 covers hopi-core, its dump has about 850 entries, and the 92 public const vals are its only inlined bodies.

### 34. E4.3 'The ABI dump': confirmed

**Claim.** KGP 2.4.20's filter API has annotatedWith, so hopi-api can keep its @HopiInternalApi members out of its ABI dump.

**Evidence.** (verified) javap of org/jetbrains/kotlin/gradle/dsl/abi/AbiFilterSetSpec in kotlin-gradle-plugin-api-2.4.20-gradle96.jar shows getByNames() and getAnnotatedWith(). Whether the exclusion works on members is still [run] (E4 X1: ./gradlew :hopi-api:updateKotlinAbi :hopi-api:checkKotlinAbi, then grep the three dumps for the getter names). Today's modules exclude by name instead (hopi-android/build.gradle.kts:21-28, hopi-perf/build.gradle.kts:21-27).

### 35. E7.1 'inline/const rule'; E6.5 'ABI dump': confirmed

**Claim.** No dump declares an inline fun or a const val today (the parity check's ban never fires), and hopi-ui's dumps hold only HopiLauncher and HopiScreen.

**Evidence.** (verified) git grep -E '(inline|const) (fun|val)' over */api/* finds nothing; the rule is at build.gradle.kts:53-58 with its regex at :105. hopi-ui/api/jvm/hopi-ui.api:1-7 and hopi-ui/api/hopi-ui.klib.api:9-10 hold only HopiLauncher and HopiScreen, and the desktop package is excluded at hopi-ui/build.gradle.kts:25-27. Under D7' these dumps become empty.

### 36. E7.1 'Edits that must land in the same stage': confirmed

**Claim.** The root build lists must change in the same stage that deletes a module. A parity pair that names a missing project or api/ directory, or a publishedModules key without publish tasks, fails check while Gradle builds the task graph.

**Evidence.** (verified) build.gradle.kts:37-43 (the dumps are @InputDirectory), :109-116 (6 pairs), :125-128 (their directories, and dependsOn :$real:checkKotlinAbi and :$noop:checkKotlinAbi), :139. publishedModules (:243-258) feeds the publish tasks at :300, which check reaches through :329. The include lines are settings.gradle.kts:36-47. The parity machinery spans :24-136 plus :139, not the plan's 'lines 120-139' (E7 correction 1).

### 37. E7 corrections 4 and 6; E8 F3; E4 stage-5 knock-ons; E5.3.5: confirmed

**Claim.** The sample's release and debug checks run as part of check and hard-code module sets that the plan never names. Release must hold exactly {:hopi-ktor-noop, :hopi-ui-noop, :hopi-rules-noop, :hopi-android-noop, :hopi-perf-noop}. Debug must contain {:hopi-android, :hopi-core, :hopi-ktor, :hopi-ui, :hopi-rules, :hopi-perf} and no -noop module.

**Evidence.** (verified) sample/androidApp/build.gradle.kts:125-128 (a setOf compared with !=) and :228-229 (containsAll, and no project ending in -noop). check depends on them at :347-351 (verifyHopiSelection :348, verifyDebugCapture :350); verifyReleaseUsesNoop (:301) is run only by the README command. grep of the plan for verifyHopiSelection or verifyDebugCapture finds 0 hits. Stages 3.7, 5.4, 6.3, 7.5 and 8.4 each leave check red unless they change :125 and :228 in the same commit.

### 38. E7 C2 and correction 5; E4.3; E3.6 new-list item 3: confirmed

**Claim.** C2: keeping HopiInternalApi as dev.koga.hopi.core.HopiInternalApi inside hopi-api fails the Android release check from the first stage that puts hopi-api on the release classpath (stage 5).

**Evidence.** (verified by reading, not run) hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/HopiInternalApi.kt:1,11,19 (package dev.koga.hopi.core, BINARY retention). The release build is not minified: sample/androidApp/build.gradle.kts:33-39 configures only debug, and the report of 2026-10-27 21:03 (sample/androidApp/build/reports/hopi/release-selection.txt) lists all 31 noop classes. The check requires every class of the selected jars in the APK (:131-141). It forbids dev/koga/hopi/core/ (:180) across referencedTypes (:143-144), which are all dex type_ids, defined classes included (gradle-plugin/hopi-instrument/src/main/kotlin/dev/koga/hopi/instrument/DexReader.kt:24,86-91). Stage 5 makes rulesModule hopi-api, as an api dependency in commonMain (sample/shared/build.gradle.kts:24,97). The iOS half (the kfun:dev.koga.hopi.core count at sample/shared/build.gradle.kts:161) is unverified (E7 X4).

### 39. E7 C1 and correction 8: confirmed

**Claim.** C1 (under D2'): hopi-ktor-noop can be deleted only after hopi-ktor depends on hopi-api instead of hopi-core; otherwise hopi-core reaches the release APK.

**Evidence.** (verified) hopi-ktor/build.gradle.kts:37-38 (api on ktor-client-core, implementation on hopi-core); hopi-ktor-noop/build.gradle.kts:33 (ktor-client-core only); sample/shared/build.gradle.kts:94 picks one of the two; the release check forbids dev/koga/hopi/core/ (sample/androidApp/build.gradle.kts:180). The change needs the stage-4 registry and a Ktor recorder that registers in stage 7.

### 40. E7 C3: confirmed

**Claim.** C3: deleting a noop project while the plugin's constant still names it makes the release variant request coordinates that are not published.

**Evidence.** (verified) HopiPlugin.kt:53 (RUNTIME_NOOP, :163) and :55 (PERF_NOOP, :165); runtime() falls back to dev.koga.hopi:<name>:HOPI_VERSION (:134-135). This build resolves only from google (filtered) and mavenCentral (settings.gradle.kts:19-30), so resolution fails. A consumer that adds mavenLocal would instead silently resolve the stale noops in ~/.m2/repository/dev/koga/hopi/*-noop/0.1.0, where all six are present.

### 41. E5.3.4; E7 C4: confirmed

**Claim.** C4: stage 6 removes HopiPerf.install, but iOS gets a start path only in stage 7, so the simulator's -verifyPerf fails in between while check stays green.

**Evidence.** (verified by reading) sample/iosApp/iosApp/iOSApp.swift:22-24 calls install. Today HopiPerf.start installs the runtime itself (hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/internal/PerfRuntime.kt:287-305, then controller() at :275-283), which a start in hopi-api cannot do. No Hopi module links hopi-perf on iOS (hopi-urlsession/build.gradle.kts:48,56); the only project(':hopi-perf') edges are sample/shared/build.gradle.kts:28 and hopi-perf-noop/build.gradle.kts:53.

### 42. E7 correction 9: confirmed

**Claim.** The plan's own step order in stage 3 (steps 4 and 5 before step 7) breaks the compile of :hopi-ktor.

**Evidence.** (verified) hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Hopi.kt:4,7,11 import PlatformCapture, TransactionSource and RuleSource, used at :88-89, :103, :107-108, :133, :143 and :148-149; Exchange.kt:3,106,108 uses PlatformCapture.covers and store.withdraw. Not in E7: step 5 also comes before step 6 and breaks the sample, since sample/androidApp/src/main/kotlin/dev/koga/hopi/sample/MainActivity.kt:14,182 uses HopiSource.Ktor. Moot under D2'.

### 43. E7 correction 7: confirmed

**Claim.** Deleting hopi-android also breaks functionalTest: the plugin build declares hopi-android/consumer-rules.pro as an input, and the fixture copies it.

**Evidence.** (verified) gradle-plugin/hopi-gradle-plugin/build.gradle.kts:115-118 (inputs.file of ../../hopi-android/consumer-rules.pro) and :124 (check depends on functionalTest); gradle-plugin/hopi-gradle-plugin/src/functionalTest/kotlin/dev/koga/hopi/gradle/HopiPluginFunctionalTest.kt:431 copies the file. The root check runs it through build.gradle.kts:143.

### 44. E1.1.2; E3.5; E4 'Stage 5 knock-ons'; E5.3.5: refuted

**Claim.** The functional-test citations FT:135, 147, 217-218, 235 and 245 still hold.

**Evidence.** (verified) They were correct at e8583b8. HEAD (e1ef40a) changed the test's teardown (git diff --stat e8583b8 HEAD: 22 lines in HopiPluginFunctionalTest.kt), which moved every line after about :36 by +10: the published stand-ins :147 → :157, the shared fixture :217-218 → :227-228, the pair loop :235 → :245. E7 already uses HEAD's numbers. For the same reason, the task's premise 'code identical to 1c81938' does not hold for this test file.

### 45. E3 correction 1; E8 F6: confirmed

**Claim.** The latest release report shows 31 Hopi classes in the release APK, not the 13 that plan 3.6.1 item 2, README:493 and ARCHITECTURE:302 give.

**Evidence.** (verified, artefact) sample/androidApp/build/reports/hopi/release-selection.txt (2026-10-27 21:03, from the stage-0 run at e8583b8) says 'hopi classes in the apk: 31': android 1, ktor 6, perf 6, rules 12, ui 6. It also reports 0 core or capture types and 0 hook callers.

### 46. E7.2e 'Derived stage order': unclear

**Claim.** E7's derived order (stage 2, then the D7' UI removal, then 4, 5, 6, 7 including the thin hopi-ktor, then 9) keeps check green as long as each stage carries the edits E7.2d lists.

**Evidence.** (verified by reading, not run) Every graph and list edit E7.2d names exists at the cited lines, but the list misses three things. In stage 6, HopiPerfConfig's internal constructor (hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/HopiPerf.kt:50) is called from PerfEntry.boot (hopi-perf/src/androidMain/kotlin/dev/koga/hopi/perf/internal/AndroidBackend.kt:268), and verifyDebugCapture still requires PerfEntry (sample/androidApp/build.gradle.kts:250-251,273). In stage 7, publishedCinterops (build.gradle.kts:259) must change if the URLSession library moves. And removing the UI first also removes the only writer of core's PerfLauncher (hopi-ui/src/androidMain/kotlin/dev/koga/hopi/ui/platform/Platform.android.kt:91-95), a perf regression visible only on a device. Details are in stageOrderProblems.
