# B — Reflective resolution & one-shot guards (census)

Slice B of the 2026-10-08 merge-convention audit. **Shape 2** (reflective method resolution
by NAME ONLY where overloads exist and `Class.getMethods()` order is unspecified) and
**Shape 3** (one-shot guard spent before the bus/entrypoint it needs exists). Read-only:
bytes and source only, no build, no client run, no windows.

## Method and tree

- Kernel source audited: `forbric-kernel/src/main/java/net/forbric/**`,
  `src/runtime/java/net/forbric/**`, and the kernel's `net/fabricmc/loader/**`
  (`Hooks`). Worktree `/tmp/rel038/wt/forbric-kernel` is **byte-identical** to the canonical
  tree for `src/main/java/net/forbric` (verified `diff -rq` clean); repo HEAD `61b93a0`,
  kernel `0.3.8-beta`.
- Overload existence was checked against the real jars, not asserted:
  `net.minecraftforge/eventbus/6.2.33`, `net/neoforged/bus/8.0.5`,
  `/Applications/.minecraft/.forbric-build/client-patched.jar` (`ResourceKey`),
  `.forbric-kernel/remap/fabric-registry-sync-v0-0.116.17-*.jar`,
  `fabric-rendering-v1-0.116.17-*.jar` — all with `javap`.
- Line numbers are relative to `forbric-kernel/`.
- "player-visible" = a normal play session (not a `-D…` diagnostic / smoke switch) can see
  the defect's effect.

Key discriminating rule used for shape 2: a **zero-arg** lookup (`getMethod("x")`,
`getDeclaredMethod("x")`) is unambiguous by construction — Java forbids two same-name
zero-arg methods — so name-only *zero-arg* picks are safe. The hazard is a pick that
matches by **name + a shape predicate** (arity, "first abstract", "is static") where two or
more overloads satisfy the predicate, or a name-only pick whose intended method is *not*
zero-arg. That is the class the brief's `EventBus.post` example belongs to.

---

## Table 1 — reflective picks that resolve by name/shape

| # | site (file:line) | resolves | call sites | overload analysis | player-visible | conf | owner |
|---|---|---|---|---|---|---|---|
| R1 | `kernel/boot/KernelBusSupport.java:58-63` `singleArgMethod(owner,name)` | first method with `getName().equals(name) && parameterCount==1` over `owner.getMethods()` — `Class.getMethods()` order unspecified | **none** — grep finds only the definition; `util/Reflect.java:28` names it in a javadoc comment | this IS shape 2 in helper form. It is **dead code** left behind when `eventBusPost` was fixed (R2). High blast radius if ever reused. | no (dead) | high | boot/bus — **delete** |
| R2 | `kernel/boot/KernelForgeModContext.java:307-315` `eventBusPost` | `busClass.getMethod("post", eventType)` — **by parameter type** | `kernel/boot/KernelForgeBaseline.java:143` (the only caller, `fireNewRegistryEvent`) | **FIXED.** `javap` confirms `IEventBus` has `post(Event)` **and** `post(Event,IEventBusInvokeDispatcher)` (Forge 6.2.33); NeoForge bus 8 has `post(T)` and `post(EventPriority,T)`. The prior first-by-name pick produced `created 0 custom registr(ies)`. | no (was yes) | high | boot/forge-baseline |
| R3 | `kernel/fabric/KernelFabricLoader.java:821-833` and `kernel/fabric/KernelLanguageAdapters.java:175-184` | first **static declared** method named `member` on an entrypoint class, bound via `MethodHandleProxies` (`C::member`) | every Fabric entrypoint construct (`Entrypoint.construct`), and `provides()` at `KernelFabricLoader.java:712-713` (any method by name); reached from the loader's own adapter via `net/fabricmc/loader/impl/util/DefaultLanguageAdapter.java:38` → `LanguageAdapter.getDefault()` | `getDeclaredMethods()` order unspecified. A mod whose entrypoint class declares **two same-name static methods** (an overload) binds whichever comes first — possibly the wrong one. Whether upstream Fabric resolves the same way was not verified here (the vendored `DefaultLanguageAdapter` delegates back to `LanguageAdapter.getDefault()`), so treat this as a kernel-side ambiguity independent of parity. | **yes** | med | fabric-loader |
| R4 | `kernel/boot/KernelForgeWrapperSync.java:309-311` | first `onRemap` with `parameterCount==1` over `invoker.getClass().getMethods()` | `announceRemapToFabric` (registry-sync remap callback) | `javap` on `RegistryIdRemapCallback` shows a **single** `onRemap(RemapState)` — safe. Only residual risk is a synthetic bridge (`onRemap(Object)`, also arity 1) which still accepts the arg. | no | med-high | boot/registry-sync |
| R5 | `kernel/boot/KernelRegistryAliases.java:143-147` | first static 2-arg `create` on `key.getClass()` | `resolveKey` (alias target rebuild) | `javap` on `client-patched.jar`: `ResourceKey` has exactly **one public** static 2-arg `create(ResourceKey,ResourceLocation)`; the other 2-arg `create` is `private` and excluded by `getMethods()`. Safe **on 1.21.1**, brittle if a public overload is ever added. | no | high | boot/registry |
| R6 | `kernel/boot/KernelHudBridge.java:367-371` (`getRootOf`) and `:375-379` (`single`, first abstract) | first `getRoot` arity 1; first abstract method of a presumed-SAM interface | `resolve()` when fabric-rendering-v1 is present | On 1.21.1 the whole path is **unreachable**: `fabric-rendering-v1-0.116.17` ships no `HudElementRegistryImpl`/`HudElement`/`VanillaHudElements` (verified jar listing) → `resolve()` latches ABSENT. Forward-generation path only. `single()` assumes the interface is functional. | no on 1.21.1 | med | client/hud |
| R7 | `kernel/boot/KernelModLoader.java:1081-1108` `constructNeoMod` | **widest** public constructor, then fills params by type | every NeoForge `@Mod` construction | Not name-based but the same "pick by shape, order/arity dependent" family: if a mod's widest public ctor is unrelated (e.g. `(String,int)`), fill-by-type throws. Real NeoForge resolves the ctor by param types too, so parity is imperfect. | **yes** | med | boot/modloader |
| R8 | `kernel/interop/PayloadInterop.java:1471-1475` | `onMinecraftRegister` matched by name **+ arity + `isInstance` on both params** | payload registration fan-out | Checks parameter types — robust. | no | high | interop/payload |
| R9 | `kernel/boot/RegistrationEventSteps.java:293` | `getDeclaredMethod(step.name())` for a `()V` INVOKESTATIC | replay of NeoForge `RegistrationEvents.init` | Zero-arg for a `()V` descriptor — exact; overloads irrelevant. | no | high | boot/registration |
| R10 | `KernelForgeInternalSubscribers.java:181` (`register(Object)`), `KernelForgeModContext` `acceptEvent(baseEvent)`, `LateForgeRegistryDeclarations.java:34,38` (`post(EVENT)`,`addListener(EVENT_LISTENER)`), `GameEventMultiplexer` `getMethod(entry,Object.class)` | bus/hook methods | registration + dispatch | All specify parameter types. Safe. | no | high | various |
| R11 | Dynamic **zero-arg** picks: `KernelLifecycle.java:665` `getMethod("start")`, `:2508` `invokeGameDataOn` `getMethod(method)`, `:1171` `contentCall`, `:2431` `invokeNoArg`, `ClientShutdown.java:226` `findMethod` | no-arg static/instance hooks (`start`, `unfreezeData`, `modifyAttributes`, `fireSpawnPlacementEvent`, `earlyInit`, `init`, `stopFuture`/`stop`) | boot/lifecycle/shutdown | `javap`: `IEventBus.start()` is the **only** `start` on both Forge 6.2.33 and bus 8.0.5; the rest are found by distinct names or are genuine zero-arg hooks → unambiguous. | no | high | boot/lifecycle |
| R12 | `kernel/boot/KernelClientSmoke.java:1158` (name+params assignable), `:1963` (name+arity0) | smoke-test field accessors | smoke harness only | Gated by the smoke switch; not a normal-session path. | no (unless smoke) | med | boot/client-smoke |
| R13 | `util/IdentifierNames.java:31-34`, `KernelPackMetadata.java:136` (`name()`), `ForgeRuntimeInterop.java:76` (`get()`), `LateForgeRegistryDeclarations.java:19-20` (`backingList`/`monitorBackingList`/`children`) | gen-agnostic accessors | registry/pack interop | Zero-arg, single-method targets — safe. | no | high | util/interop |

**Net of shape 2:** the one *live* name/shape-ambiguous pick that can select the wrong member
on a normal session is **R3** (Fabric entrypoint static-member binding, overloaded member
only). R1 is the exact defect in helper form and is now **dead** — it should be removed so it
cannot be re-adopted. R2 (the brief's example) is fixed and verified against real overloads.
R4/R5/R9–R13 were checked against the jars and are safe on this base; R5/R6 are brittle to
future overloads/versions but not defects today.

---

## Table 2 — one-shot guards and whether their spend can precede the prerequisite

"Prerequisite" = the bus / entrypoint / classloader state the guarded work needs.

| # | guard (declare / spend) | gates | prerequisite | call sites (spend) | spend-before-prerequisite risk | player-visible | conf | owner |
|---|---|---|---|---|---|---|---|---|
| G1 | `KernelLifecycle.java:1431` / `:1362` `DATAPACK_REGISTRIES_DECLARED` | posting `DataPackRegistryEvent.NewRegistry` to baseline + each mod bus | a **NeoForge bus that exists** (baseline constructed) | `registerDataPackRegistries` `:1355`; called `:261` (deferred from `driveNativeRegistration`), `:2807` (`onClientEntrypoints`), `:2864` (`onNeoClientSetup`) | **FIXED.** `if (baselineBus == null) … return;` at `:1356` runs **before** the CAS at `:1362`, so a client whose Fabric mains run ahead of the mod-loading window no longer spends it. This is the brief's shape-3 example, closed. | was yes | high | boot/lifecycle |
| G2 | `KernelLifecycle.java:1766` / `:1739` `REGISTRATION_EVENTS_FIRED` | NeoForge `RegistrationEvents.init` (capabilities + data maps) **and** `KernelTransferInterop.install` | mods published (`publishedNeoMods`) + game bus started (`startGameBuses`) | `fireRegistrationEvents` `:1738`; called `:1708` (`fireModSetupLifecycle`, server only — `if (side.isClient()) return;` at `:1696`) and `:1906` (`fireClientSetupLifecycle`) | LOW. Both callers are post-construction, after `startGameBuses`. Note the CAS precedes the `try`, so a throw inside `RegistrationEventSteps.fire` still spends it (no retry) — intended "once", but there is no retry if the *first* call is mistimed. | yes | high | boot/lifecycle |
| G3 | `KernelLifecycle.java:1990` / `:1887` `CLIENT_SETUP_FIRED` | client FML setup phases (common/client setup, IMC, load-complete, foreign shim, client network) | `Minecraft.options` exists **and** mods published | `fireClientSetupLifecycle` `:1886`, sole caller `:2865`; reached only via `onNeoClientSetup` (`:2855`), called `:322` and injected from `Minecraft.<init>` (`NeoClientSetupHookInjector`) | LOW–MED. Single consumer and the measured window is post-construction; but as with G2 the CAS precedes the work and there is no prerequisite *check*, only ordering. | yes | med-high | boot/lifecycle |
| G4 | `KernelFabricEcosystem.java:81` / `:469` `MAINS_RAN` | Fabric `main` (+`server`) entrypoints | Fabric mods discovered + registries unfrozen **and** (for the datapack step that rides it) the NeoForge baseline bus | `runMainEntrypoints` `:467`; called `:855` (pre-`Minecraft` window, when not client-in-constructor) and `:2791` (`onClientEntrypoints` via `Hooks.startClient`) | **MED.** Guarded only by `loader == null` (`:468`) — **not** by "baseline bus exists". This is exactly why G1's deferral + `baselineBus` check exist (see `:248-262`): the entrypoint hook can run before the baseline is constructed. The guarded work itself (entrypoint init) is safe; the one action that needed a bus was the datapack declaration, now checked. | yes | med | fabric-loader |
| G5 | `KernelFabricEcosystem.java:82` / `:624` `CLIENTS_RAN` | Fabric `client` entrypoints | same as G4 | `runClientEntrypoints` `:622`; called `:2792` | **MED.** Same shape as G4, same mitigation. | yes | med | fabric-loader |
| G6 | `net/fabricmc/loader/impl/game/minecraft/Hooks.java:35` `SERVER_REACHED`, `:36` `CLIENT_STARTED` | the client-entrypoint window trigger / the "startServer too early" report | the kernel's window ordering | `startServer` `:64`, `startClient` `:97` | MED. `CLIENT_STARTED` CAS spends on the first call then drives the whole window; `startServer` *does* check `mainsAlreadyRan` (`:75`) and error-reports, but `startClient` has no such guard — its safety rests on where the injector emits it. | yes | med | fabric-loader |
| G7 | `src/runtime/.../KernelFabricConditions.java:97` / `:191` (also `:200`) `evaluatorResolved` | resolving fabric-api's `applyResourceConditions` evaluator | fabric-api classes **reachable** | `ask()` from `alsoAskFabric.decode` — first datapack decode | **MED — one-way latch, no reset.** On *any* `Throwable` the catch sets `evaluator = null` **and still sets `evaluatorResolved = true`**, so a first decode that happens before fabric-api is reachable leaves every `fabric:load_conditions` file kept unjudged for the whole session (a mod's config toggle over its own data files does nothing). The field javadoc itself says the first decode can precede reachability. | **yes** | med | runtime/conditions |
| G8 | `src/runtime/.../KernelPacketContext.java:69` / `:161` `resolved` | binding Fabric's packet context around NeoForge's splitter | fabric-networking internals + `java.lang.ScopedValue` | `contextOf` from `encodeInFabricContext` — first packet encode | **MED.** `resolved = true` is set *before* the `try`, so any failure latches "off" permanently; later packets never get the context. | yes | med | runtime/net |
| G9 | `src/runtime/.../transfer/KernelFabricHopperStorage.java:61` `resolved` | hopper ↔ Fabric item-storage bridge | fabric-transfer-api shapes present | `insert`/`extract` — first hopper op (world tick) | LOW–MED. Documented "false (for good)"; first op is late (after mods), and a miss just restores pre-bridge behaviour. Same one-way shape as G7/G8. | yes | low-med | runtime/transfer |
| G10 | `kernel/boot/LootTableEventDispatch.java:84` `state`/`handles` | loot-table bridge handles | fabric-loot-api + a bound guest loader | `afterLoad`/`allLoaded` via `resolve()` | LOW. A missing loader latches ABSENT, **but `bind()` (:119) resets state**, and `KernelBoot.java:1052` binds at boot. Recoverable. | yes | low | boot/loot |
| G11 | `kernel/boot/KernelHudBridge.java:157` `state` | HUD bridge classes/ctor | guest loader bound + fabric-rendering-v1 | `wrap()` via `resolve()` | LOW. Missing loader latches ABSENT, **but `bind()` (:178) resets state** (`KernelBoot.java:1051`). On 1.21.1 it correctly stays ABSENT. | no on 1.21.1 | low | client/hud |
| G12 | `src/runtime/.../transfer/BlockTransferBridge.java:82` / `:125` `INSTALLED` | installing Fabric fallback providers | Fabric transfer + NeoForge transfer APIs | `install()` — from `KernelTransferInterop.install` (inside G2) | LOW–MED. `INSTALLED` is **spent at `:125`, before the work**; `enabled = true` only at the end. A throw mid-install leaves it spent and the bridge dormant with no retry. Prerequisite (Fabric API) is guaranteed by `KernelTransferInterop.configure` gating, so the practical window is small. | yes | low-med | runtime/transfer |
| G13 | `src/runtime/.../transfer/RebornEnergyBridge.java:34` / `:45` `INSTALLED` | Reborn energy half | `BlockTransferBridge.installed()` + Reborn API unchanged | `install()` — from `KernelTransferInterop.install` | LOW–MED. CAS spends **before** `requireApi()` (`:46`), which throws on API drift → spent with no retry (recorded as a finding, not silent). | yes | low-med | runtime/transfer |
| G14 | `kernel/boot/KernelTransferInterop.java:33` / `:84` `installed` | `install()` body | `active` + CAPABILITIES registered | `install` from G2 | LOW. Set **after** success (`:97`) → retry-safe within a run; only invoked once (inside G2). | yes | low | boot/transfer |
| G15 | `kernel/interop/ClientShutdown.java:75` `ran` | exit sweep + exit guard | the watchers/workers actually exist | `stopLeakedBackgroundExecutors` — called at each side's end of life | MED. `ran = true` **before** the sweep; on a client where `Minecraft.close()` runs before the integrated server creates its watchers/workers, the later server exit sweep is skipped → possible JVM-exit hang (the exact class this guard exists to prevent). | yes | low-med | interop/shutdown |
| G16 | `kernel/mixin/KernelMixinBootstrap.java:46` / `:141` `initialized` | mixin bootstrap | — | `init` (throws if already initialised) | NONE — set at the *end*; a second call is an explicit error, not a silent skip. | no | high | mixin |
| G17 | `kernel/boot/KernelLoadReport.java:57` `reported`, `:58` `hooked` | end-of-loading line / shutdown hook | — | `writeTo(...,loadingFinished)`; `setRunDir` | NONE. `reported` is spent only when `loadingFinished && clean` (`:133`); `writeEvidence` deliberately never spends it. `hooked` installs a shutdown hook once. | no | high | boot/report |
| G18 | log/announce-once guards: `KernelPackRepair.java:54` `NULL_PACK_REPORTED`, `KernelRegistryAliases.java:86` `REPORTED`, `EventChainAudit.java:102` `HOOKED`, `KernelChunkExecutorGuard.java:74` `SAID`, `KernelFabricConditions.java:92/93`, `KernelServerTicks.java:74/76`, `KernelRegistryRevert.java:54`, `TransformChain.java:196`, `EnvironmentStripTransformer.java:158`, `ForbricClassLoader.java:126` `chainInstalled`, `KernelFabricLoader.java:657` `unresolvableReported` | a single log line / hook install | — | various | NONE with a bus/entrypoint prerequisite; `chainInstalled` is set with the chain; `EventChainAudit.HOOKED` CAS runs before `&& enabled()` but `enabled()` reads a constant property. | no | high | various |

---

## Owner lanes / recommended next actions

1. **boot/bus — delete R1.** `KernelBusSupport.singleArgMethod` (`:58-63`) is the shape-2 defect
   in helper form and is now unused; keep it from being re-adopted. (No behaviour change.)
2. **runtime/conditions — G7 is the strongest live shape-3 match.** Make `evaluatorResolved`
   latch only on *evidence of absence* (e.g. `ClassNotFoundException`), or add a reset/retry so a
   first decode before fabric-api is reachable does not permanently disable condition judging.
   Player-visible: condition-gated data files load unjudged.
3. **runtime/net, runtime/transfer — G8, G12, G13.** Same one-way-latch shape (`resolved = true`
   / `INSTALLED` spent before the work). Cheap hardening: set the "resolved" flag only after a
   successful resolve, or spend `INSTALLED` after the install body.
4. **interop/shutdown — G15.** Set `ran` after the sweep (or key it per side) so a client sweep
   that precedes the integrated server's watchers does not suppress the server's exit sweep.
5. **fabric-loader — R3, G4–G6.** The entrypoint static-member bind (R3) and the mains/client
   guards (G4–G6) share one root: the entrypoint window can run before the NeoForge baseline bus.
   G1 fixed the one *action* that needed the bus; the guards themselves still have no
   prerequisite check beyond `loader == null`.
6. **boot/modloader — R7.** Widest-public-constructor selection can pick an unfillable ctor;
   consider matching by parameter type first (as NeoForge does) before falling back to widest.

## Closed / verified-safe (so the census is complete, not only positive)

- R2 (`EventBus.post`) — fixed to resolve by parameter type; both `IEventBus` overloads confirmed
  by `javap`. G1 (`DATAPACK_REGISTRIES_DECLARED`) — fixed with a `baselineBus == null` early
  return before the CAS. These are the brief's two named examples.
- R4, R5, R9–R13 — checked against the actual jars; no overload ambiguity on this base.
- G10, G11 — one-way latches but with a `bind()` reset, so recoverable.
- G16–G18 — no bus/entrypoint prerequisite; safe by construction.
