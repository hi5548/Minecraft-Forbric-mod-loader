# Guards — the one-way latches, the reflective member bind, and the dead helper

Lane `FixGuards` of the 2026-10-08 merge-convention cleanup. Audit source of record:
`forbric-kernel/run/compat/reports/2026-10-08-merge-convention-audit/B-reflect.md`.

Assignment: fix finding **#2** (`KernelFabricConditions.evaluatorResolved`, audit **G7**) and finding **#6**
(the wrong-overload static bind, audit **R3**; the dead helper, audit **R1**; and the low-cost one-way guard
spends, audit **G8**, **G12**, **G13**, **G15**). No behaviour change is intended beyond each cited defect.

All edits are in the canonical tree. Commits are one per decision; each stages only this lane's files (a sibling
lane is mid-flight in `PassiveSeeder.java`).

---

## 1. G7 / finding #2 — `KernelFabricConditions.evaluatorResolved` (runtime/conditions)

`src/runtime/java/net/forbric/kernel/runtime/KernelFabricConditions.java` (`evaluatorHandle()`).

**Was:** the latch `evaluatorResolved = true` was set *after* the `try`, unconditionally — including in the
`catch (Throwable absent)` path that leaves `evaluator == null`. The field's own javadoc says the first decode can
happen before fabric-api's classes are reachable, so one such decode permanently disabled condition judging: every
later `fabric:load_conditions` file loaded **unjudged**, i.e. a Fabric mod's config toggle over its own data files
did nothing, silently, for the whole session. Player-visible.

**Now:** the latch is spent only once the `findStatic` succeeded and `evaluator` is non-null. On any failure the
latch stays open, so the next decode asks again. The one-shot `reportedAbsent` warning already bounds log noise;
there is no repeated logging.

---

## 2. R3 / finding #6 — bind the entrypoint static member by DESCRIPTOR, not by name

`src/main/java/net/forbric/kernel/fabric/KernelLanguageAdapters.java` (new resolver),
`src/main/java/net/forbric/kernel/fabric/KernelFabricLoader.java` (`Entrypoint.construct`, `Entrypoint.provides`).

**Was:** three copies of "first `getDeclaredMethods()` entry whose name equals the member and is static".
`getDeclaredMethods()` order is unspecified, so a class declaring two same-name static methods (an overload)
bound whichever the JVM listed first — possibly one the requested functional interface cannot be implemented by,
which then threw inside `MethodHandleProxies`. KernelFabricLoader's `provides()` was looser still: any method of
that name (static or not) with an interface target returned `true`.

**Now:** one shared resolver, `KernelLanguageAdapters.staticMember(owner, member, type)` (fields first, unchanged),
selecting the static method whose descriptor can implement `type`'s single abstract method. Compatibility is the
JDK's own adaptation rule (`MethodHandle.asType`, which is what `MethodHandleProxies` applies), and ties are broken
by descriptor so the choice never depends on JVM reflection order. `provides()` asks the same resolver through
`hasStaticMember(...)`, so the two answers cannot drift.

Evidence (scratch worktree at repo HEAD, `guards-wt`, JDK 25):
- `./gradlew compileJava compileTestJava` — BUILD SUCCESSFUL.
- `KernelLanguageAdaptersMemberTest` (new): 4 tests, 0 failed.
  - `anOverloadIsBoundByDescriptorNotDeclarationOrder` — a `()V` interface binds the `()V` member even when an
    arity-mismatched `(String)V` overload of the same name is declared first.
  - `aStaticMethodOfTheWrongArityIsNotBound` — a lone `(String)V` member is not bound to `()V` (throws).
  - `aStaticFieldIsStillTakenByValue` — the static-field branch is unchanged.
  - `theProvidesCheckFollowsTheSameRule` — the predicate follows the descriptor rule (this one fails against the
    pre-fix name-only selection).
- Related suites green: `KernelBusSupportTest`, `FabricEntrypointStorageTest`, `FabricLoadOrderTest`,
  `ExitHookInjectorTest`, `EntrypointResolveFailureTest` — 32 tests, 4 skipped, 0 failed.

Note on the regression value: the two **binding** assertions are order-sensitive in the sense that, on this
HotSpot, pre-fix also happened to enumerate the `()V` member first, so they pass pre-fix here — they are behaviour
pins, not a deterministic pre-fix failure. The **predicate** assertion does fail pre-fix (demonstrated by
temporarily reverting the selection to first-by-name: 1 of 4 failed at
`KernelLanguageAdaptersMemberTest.java:78`). The descriptor rule is the fix; determinism is not observable as a
pre/post difference when reflection happens to return the right one first.

---

## 3. R1 / finding #6 — delete the dead `KernelBusSupport.singleArgMethod`

`src/main/java/net/forbric/kernel/boot/KernelBusSupport.java`, `src/main/java/net/forbric/kernel/util/Reflect.java`,
`src/test/java/net/forbric/kernel/boot/KernelBusSupportTest.java`.

`singleArgMethod(owner,name)` (first same-name `getMethods()` entry with arity 1 — shape-2 in helper form) has no
production callers; it was left behind when `eventBusPost` was fixed to resolve by parameter type. Deleted, along
with its three unit tests and fixture in `KernelBusSupportTest`, and the `Reflect` javadoc reference to it. The
`makeModBus` tests in that class are untouched.

---

## 4. G8 — `KernelPacketContext.resolved` (runtime/net)

`src/runtime/java/net/forbric/kernel/runtime/KernelPacketContext.java` (`resolve`).

**Was:** `resolved = true` was set *before* the `try`, so any failure latched "off" permanently and later packets
never got the Fabric packet context. **Now:** `resolved` is set only once both `scopedValue` and `encoderContext`
are actually in hand; on failure the resolve retries (the absence debug line is now logged once, not per packet).
Absent Fabric networking therefore no longer permanently disables the capture, but also no longer spams the log.

## 5. G12 — `BlockTransferBridge.INSTALLED` (runtime/transfer)

**Was:** `INSTALLED.compareAndSet(false, true)` was spent at the top of `install()`, before the four
`registerFallback`/`ahead`/seam calls; a throw mid-install left it spent and the Fabric fallback dormant for the
session with no retry. **Now:** spent at the END of the body (`INSTALLED.set(true)` next to `enabled = true`), so a
throw leaves the one-shot open. `install()` is a single boot call; the top guard is a `get()`.

## 6. G13 — `RebornEnergyBridge.INSTALLED` (runtime/transfer)

**Was:** the CAS was spent before `requireApi()`, which throws on API drift — spent with no retry. **Now:** spent
after the whole body (after `requireApi()` and both registrations).

## 7. G15 — `ClientShutdown.ran` (interop/shutdown)

`src/main/java/net/forbric/kernel/interop/ClientShutdown.java`, comment touch-up in
`src/main/java/net/forbric/kernel/boot/KernelBoot.java`.

**Was:** a session-wide `ran` one-shot set before the sweep. On a client where `Minecraft.close()` (and its exit
guard, which returns as soon as no leaked non-daemon thread is visible) runs before the integrated server creates
its watchers, the later end-of-life call — the kernel launcher's `finally` after `main` returns — was suppressed,
so those watchers were never swept. That is the exact JVM-exit hang this class exists to prevent.

**Now:** the guard is re-armed rather than spent once. `GUARD_RUNNING` is a boolean CAS meaning "an exit guard is
currently sweeping"; the guard thread clears it in a `finally` when it ends. Each end-of-life call therefore gets
its own sweep when no guard is active, and only reports while one is. A failed sweep releases the latch (spend
conditional on success). The `KernelBoot` comment about the boot-time measurement no longer calls it a one-shot.

### Runtime compile check

The pinned Team Reborn Energy 4.1.0 jar (`energy-4.1.0-named.jar`, sha `cec89d1c…`) is **absent from this machine**,
so `compileRuntimeJava` cannot run as configured. Proof used instead, against the real staged game jars, the
extracted Fabric transfer modules, the staged MC libraries, and the existing `build/classes/java/runtime`:
`javac --release 21` of the four changed runtime files — `KernelFabricConditions`, `KernelPacketContext`,
`BlockTransferBridge`, `RebornEnergyBridge` — **exit 0**. `RebornEnergyBridge` was typed against a minimal
`team.reborn.energy.api.EnergyStorage` stub (its Reborn API usage is byte-for-byte unchanged from the committed
version; only the two `INSTALLED` lines moved), because the real jar is missing. The other three used the real
jars only.

---

## Residual / environment notes

- The regression assertions that pin behaviour (#2, G8, G12, G13, G15) are not unit-testable off-game: the runtime
  source set is not on the unit-test classpath (tests load game-side classes through a `URLClassLoader`), and the
  pinned Reborn jar is absent. They are covered by compilation + inspection here; the main agent's client run is
  the reading.
- `PassiveSeeder.java` was broken mid-flight by a sibling lane during this work, so the canonical `compileJava`
  could not be used for proof; all compilation evidence above is from a scratch worktree at repo HEAD carrying only
  this lane's diff.
