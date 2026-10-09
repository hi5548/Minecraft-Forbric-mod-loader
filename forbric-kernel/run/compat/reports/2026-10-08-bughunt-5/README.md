# Bug hunt #5 — player-visible defects outside the four 2026-10-08 fix lanes

**Verdict (EN).** Read-only hunt on `0.3.8-beta` (kernel `61b93a04` / version `2a9e6938`, boot jar
`97d894f5…`), in the four requested areas and against the **shipped** carrier/instance bytes, not a build.
Four things are worth a player's time; two of the four requested surfaces came back clean and I say so with
bytes. The strongest item is a **latent sibling of the just-fixed payload class**: the kernel fixed
NeoForge's payload *work-ordering* on the `Runnable` overload of `ClientPayloadContext.enqueueWork` and
**explicitly left the `Supplier` overload alone** — a clientbound-PLAY handler that defers through the
returning form can still run its work before the client level exists and kill the join. The strongest
*actually-observed-feature* loss is **MinecraftForge's damage events never firing on 1.21.1**, which the
0.3.8 instance log reports as `ERROR` and which I confirmed in the merged bytecode.

**答案（中文）。** 在 `0.3.8-beta`（内核 `61b93a04`／版本提交 `2a9e6938`，boot jar `97d894f5…`）上做**只读**
排查，四个请求区域都对着**已发布**载体的字节与本机安装实例的日志，而不是对着一次构建。四件事值得看；
其中两个请求面我用字节给出**阴性**结论。最强的一条是**刚修好的那类载荷缺陷的姊妹**：内核只修了
`ClientPayloadContext.enqueueWork` 的 `Runnable` 重载，**明确没碰 `Supplier` 重载**——用返回值形式延后工作的
客户端 PLAY 处理器仍可能在客户端关卡存在之前跑完，从而打断进服。**真正观测到的功能损失**最强的一条是
**1.21.1 上 MinecraftForge 的伤害事件从不触发**：0.3.8 安装实例的日志把它记成 `ERROR`，我在合并字节里确认了。

Method: read-only. Sources are the kernel tree, `javap -c -p` on the real merged carrier
(`/Applications/.minecraft/.forbric-build/out/patched-mc-merged-1.21.1.jar`, `neoforge-runtime.jar`) and on the
remapped fabric-api modules, plus the **shipped** instance log
(`/Applications/.minecraft/versions/1.21.1-forbric/logs/latest.log`, kernel `0.3.8-beta`). No build, no
client run, no game launch. Line numbers are relative to `forbric-kernel/`. Raw outputs in `evidence/`.

---

## Ranked findings

| # | Area | Defect | Player-visible symptom | Confidence |
|---|---|---|---|---|
| R1 | payload (work ordering, clientbound PLAY) | `ClientPayloadContext.enqueueWork(Supplier<T>)` keeps the inline same-thread shortcut; the kernel patches only the `Runnable` overload | a NeoForge/Fabric mod whose clientbound handler defers via the returning form during the login window runs work before the level exists → join dies `Network Protocol Error` | high (code-admitted + bytes) |
| R2 | registry-sync id remap, multiplayer | a Forbric client on a **pure Fabric** server gets **no** id remap for the 17 Forge-wrapped registries from either ecosystem | joining a Fabric server whose registry ids differ → wrong block/item/entity ids (ghost/desynced content) | high (documented limit + mechanism) |
| R3 | performance / startup | ≥5 independent full-jar decompress+scan passes run on `[main]` before the client window; no shared pass, no cache | visibly longer boot | medium (AbiAudit timed 204–745 ms; the other passes unmeasured) |
| R4 | transfer API | `fabric-transfer-api-v1`'s own storage accessors (`DoubleInventoryAccessor`, `ContainerComponentAccessor`, `BucketItemAccessor`, `BundleContentsComponentAccessor`) are reported un-bindable and kept, "the generated accessor will throw when called" | a Fabric transfer mod touching a double chest / container component / bucket / bundle may throw | low — MixinFit is a static verdict; needs a runtime call to confirm |
| A1 | *adjacent, outside the four buckets* | `ForgeDamageSeamsInjector` targets the **26.2** `actuallyHurt(ServerLevel,…)`/`hurtServer(...)` shapes and cannot place its seams on 1.21.1 | MinecraftForge's `LivingHurtEvent`/`LivingDamageEvent`/`onPlayerAttack` never fire — Forge combat/perk/immunity mods do nothing | high (log ERROR + merged bytes) |

Negatives (requested areas, checked, clean): **save/load integrity** and **fabric rendering data attachment**
— §“What came back clean”.

---

## R1 — `enqueueWork(Supplier)` is the un-fixed sibling of the just-fixed payload class

`PayloadWorkOrderingTransformer` removes the `isSameThread()` inline shortcut from NeoForge's clientbound
payload work queue, because NeoForge's data-map sync dereferences `Minecraft.level` with no null guard and the
inline branch can run it before `ClientPacketListener.handleLogin` created the level. Its own javadoc admits the
gap (`transform/PayloadWorkOrderingTransformer.java:63-65`):

> The `enqueueWork(Supplier)` overload carries the same shortcut and is deliberately NOT touched here: the failed
> join enqueued a `Runnable`, and widening the repair to the returning form is a separate change with its own arm.

The match is pinned to one descriptor (`:69-70`):

```java
private static final String METHOD = "enqueueWork";
private static final String DESCRIPTOR = "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;";
```

**Byte evidence** (`evidence/payload-enqueuework-javap.txt`). `javap -c -p` on
`neoforge-runtime.jar :: net.neoforged.neoforge.network.handling.ClientPayloadContext` shows **both**
overloads carry the shortcut at the same offset:

```
public java.util.concurrent.CompletableFuture<java.lang.Void> enqueueWork(java.lang.Runnable);
   4: getMainThreadEventLoop()  9: isSameThread()  12: ifeq 26   15: Runnable.run()  25: areturn   // inline path
public <T> java.util.concurrent.CompletableFuture<T> enqueueWork(java.util.function.Supplier<T>);
   4: getMainThreadEventLoop()  9: isSameThread()  12: ifeq 25   15: Supplier.get()  24: areturn    // inline path, NOT patched
```

The class declares both (`javap` lists `enqueueWork(Runnable)` and `enqueueWork(Supplier<T>)`), and the
transformer's loop (`:82-84`) skips every method whose descriptor is not the `Runnable` one.

Player-visible: the same failure the `Runnable` fix removes — payload work that must run *after* the client
level exists runs inline *before* it, and the join dies with `Network Protocol Error`. It is **latent**, not
guaranteed: NeoForge's own handlers use the `Runnable` form (`MainThreadPayloadHandler`, `ClientRegistryManager`,
`GenericPacketSplitter` all pass a lambda), so it needs a guest mod that defers through
`ctx.enqueueWork(() -> value)`; that is the form the `IPayloadContext` interface publishes for return values, so
mods do use it.

**Smallest honest fix.** Accept both descriptors in the same loop — the replacement (`POP; GOTO`) is identical
for both bodies, since both push a boolean and branch to a `submit(...)` tail:

```java
if (!METHOD.equals(method.name)) continue;
if (!DESCRIPTOR.equals(method.desc) && !SUPPLIER_DESCRIPTOR.equals(method.desc)) continue;
```

Keep the same `-Dforbric.payloadWorkOrdering=off` falsifier; add the returning overload to the shape test.

---

## R2 — registry-sync id remap against a pure Fabric server is dead for the 17 wrapped registries

`boot/KernelForgeWrapperSync.java:50-53` states the limit itself:

> Known limit, on purpose: fabric-api's `remap` is a mixin on `MappedRegistry`'s fields, so on a wrapped registry
> it is a silent no-op — a Forbric client against a PURE Fabric server gets no remap of these seventeen
> registries from either ecosystem.

Mechanism (all in-tree): the 17 `net.minecraftforge.registries.NamespacedWrapper` registries (`block`, `item`,
`entity_type`, …) override `containsKey`/lookup to their delegate but leave the inherited `MappedRegistry`
fields (`byId`/`toId`/`byKey`) empty for life. NeoForge's `RegistryManager.applySnapshot` remaps through those
fields, which is why `RegistrySyncParityInjector` had to route NeoForge's snapshot into Forge's own
`GameData.injectSnapshot` (staged by `KernelForgeWrapperSync`). fabric-api's remap is a mixin *on the same
fields*, so when the peer is a pure Fabric server — which drives the Fabric path, not NeoForge's snapshot — the
call is silently a no-op and the client keeps its own ids. Singleplayer and a Forbric server are unaffected
(singleplayer syncs nothing; a Forbric server's ids arrive through NeoForge's snapshot).

Player-visible: a client with a different mod set than the server keeps its local ids for those registries →
wrong blocks/items/entity types, ghost or missing content. Nothing logs it.

**Smallest honest fix.** Mirror `RegistrySyncParityInjector` for the *Fabric* entry point: add a `remap`
override to `NamespacedWrapper` (a `MappedRegistry` subclass, so it inherits the mixin-added
`RemappableRegistry.remap`) that stages the server's id map into the same per-registry map
`KernelForgeWrapperSync` already keeps and flushes through `GameData.injectSnapshot`, then fires
`announceRemapToFabric` as the NeoForge path already does. That reuses the existing staging/flush and the
existing Fabric remap-event notification; the alternative — making the wrapper keep real `byId`/`toId` maps —
duplicates the delegate's storage.

---

## R3 — startup does ≥5 independent full-jar scans, none cached or shared

`boot/KernelBoot.java:361-375` runs, on `[main]` before the client window, five passes that each open **every
mod jar** and read **every `.class` entry**:

```
361: PortingLayerAudit.report(shadowCandidates, runtimeJars);
364: FabricApiModuleLossAudit.scan(shadowCandidates);      // readAllBytes per class
367: FieldDriftAudit.scan(shadowCandidates);               // readAllBytes per class
369: MergedBaseUncalledMethods.scanGuests(shadowCandidates);
371: AbiLinkAudit.scan(shadowCandidates, abiUniverse);     // readAllBytes per class, twice over the universe
```

Each is a separate decompress+read of the same bytes for a different constant-pool needle set, and none is
keyed to a jar hash (the only content-addressed cache, `ForbricCache`, covers remapped jars, not these scans).
**Measured** (`evidence/shipped-log-defects.txt`): `AbiAudit` logs its own cost —
`scanned 68 jar(s) in 205 ms` on this instance, and **745 ms** on another recorded boot of the same set. It is
one of five.

**Smallest honest fix.** Fold the constant-pool needle scans (`FabricApiModuleLossAudit`, `FieldDriftAudit`,
`AbiLinkAudit` — all `ByteScan.containsAny` on the same `byte[]`) into one pass that hands each jar's class
bytes to all three `note()` methods, or cache per-jar needle hits under the jar's sha256 in `ForbricCache`.
Either removes 2 of the 3 redundant decompressions without changing verdicts. (Lower rank than R1/R2 because
the cost is bounded and unmeasured for the other four passes; `[INFERENCE]` where I say "visibly longer".)

---

## R4 — transfer API accessors reported un-bindable (candidate; needs a runtime call)

The shipped log lists fabric-transfer-api's own storage accessors under `[Forbric/Mixin] guest accessor mixin
… cannot bind — … (kept; the merge re-typed or removed the member, so the generated accessor will throw when
called)`, including `DoubleInventoryAccessor` (double chests), `ContainerComponentAccessor` (container
components), `BucketItemAccessor` and `BundleContentsComponentAccessor`. The remapped interfaces exist
(`fabric_getFirst/getSecond`, `fabric_getStacks`, `fabric_getFluid`) and the classes that **use** them are
fabric-transfer-api's own `ItemStorage` lookup and `ContainerComponentStorage`/`EmptyBucketStorage`/
`BundleContentsStorage`, so a Fabric transfer mod can reach them.

**Why only low confidence.** The verdict comes from `MixinFit` — a *static* analysis (`KernelGuestMixinAdapter`
`:198-205`) whose own docs (`MixinFitReport`) say its verdicts "can differ from the live ones". For
`DoubleInventoryAccessor` the reported descriptors are *identical* on both sides and the target fields do exist
in the merged base (`CompoundContainer.container1/container2`, `ItemContainerContents.items`,
`BucketItem.content` — all verified with `javap`), so this may be a false positive of the checker rather than a
runtime failure. It cannot be settled without a client that calls `ItemStorage.SIDED` on one of those
containers, which this hunt does not have. Recorded as a candidate, not a confirmed defect.

**Smallest honest fix (if confirmed):** teach `MixinFit` the same "matching name+descriptor IS a bind" rule
`MixinNames` already applies for re-typed members, so the accessor is not left with a throwing body — or, if
the member really is gone, fulfil it with a real accessor in the merged base as `WidenedFieldTwinInjector`
does for the re-typed fields.

---

## A1 — adjacent (outside the four buckets): Forge damage events never fire on 1.21.1

The 0.3.8 instance log carries this at `ERROR`
(`evidence/shipped-log-defects.txt`):

```
[Forbric/Anchor] forbric-forge-damage-seams was handed net.minecraft.world.entity.LivingEntity and made no edit —
  its anchor is gone. MinecraftForge's LivingHurtEvent and LivingDamageEvent never fire — damage perks, immunity
  and death-prevention in MinecraftForge mods do nothing
[Forbric/Anchor] forbric-forge-damage-seams was handed net.minecraft.world.entity.player.Player and made no edit — …
```

Cause, confirmed in the merged carrier (`evidence/damage-seams-javap.txt`): `ForgeDamageSeamsInjector` looks up
`actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)V`
(`:55`) and `Player.hurtServer(ServerLevel,DamageSource,F)Z` (`:56`) — the **26.2** signatures. On 1.21.1 the
merged `LivingEntity.actuallyHurt` is `(DamageSource,float)` and `Player` has `hurt(DamageSource,float)` and no
`hurtServer`; so the seams never place. `ForgeHooks.onLivingHurt`/`onLivingDamage`/`onPlayerAttack` are reachable
*only* through `KernelLivingDamage` (`runtime/…/KernelLivingDamage.java:54,74,91`), which only those seams call —
so on 1.21.1 they never fire. Player-visible: Forge-family combat/perk/immunity mods are inert.

**Smallest honest fix.** Add the 1.21.1 shape to the injector: target `actuallyHurt(DamageSource,F)V` and
`Player.hurt(DamageSource,F)Z`, and relax the entry proof (it currently requires `aload0,aload1,aload2,
isInvulnerableTo`; the 1.21.1 body, verified, is `aload0,aload1,isInvulnerableTo` with the same single
`CommonHooks.onLivingDamagePre` + `getNewDamage(); fstore_3`). Everything downstream (`KernelLivingDamage`,
`notePlayerSeam`) is signature-independent.

---

## What came back clean (with bytes, so the negative is checkable)

- **save/load integrity — no defect found.** Two anchor-ledger rows that *sound* like save/load losses are
  false positives on 1.21.1 (`evidence/anchor-ledger-false-positives.txt`):
  - `keepTheSaveOffTheTeardownsFailurePath` wants 26.2's `IntegratedServer.stopServer() =
    teardownPublishedState(); MinecraftServer.stopServer()`. On 1.21.1 `stopServer()` is
    `invokespecial MinecraftServer.stopServer:()V` **first**, then the LAN-pinger teardown — the save is not
    behind a throwing teardown, so there is nothing to repair.
  - `furnace.handlers stays null` / `bookshelf.itemHandler stays null`: the 1.21.1 merged constructors **already
    assign those fields** (`putfield handlers` in `AbstractFurnaceBlockEntity.<init>`; `putfield itemHandler` in
    `ChiseledBookShelfBlockEntity.<init>`), so no `ITEM_HANDLER` ask NPEs.
  This matters beyond the two rows: the anchor ledger reports **27 misses** on every 0.3.8 boot (present in the
  0.3.7-era logs too), with 26.2 cost text, and at least these are not real on this carrier — so the ledger is
  a lead generator, not a bug list. The one I chased that *is* real is A1.
- **fabric rendering data attachment — no defect found.** Its three mixins are pure interface injections
  (`BlockEntityMixin @Mixin(BlockEntity) implements RenderAttachmentBlockEntity, RenderDataBlockEntity`;
  `WorldViewMixin`/`ChunkRendererRegionMixin` implement `RenderAttachedBlockView`); there is no `@Inject` to
  mismatch, and the shipped log has no failure line for the module.
- **the payload Miss on `ServerConfigurationNetworkAddon` is benign.** `FABRIC_ADDONS`
  (`transform/CommonNetworkInteropInjector.java:91-94`) lists that class, but on 1.21.1
  `net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon` does **not** declare
  `handle(CustomPacketPayload)` (only `ClientConfigurationNetworkAddon` and the base do), so the anchor is
  satisfied by the base-class injection the injector already places — the `ERROR`-level Miss is another
  26.2-shaped false positive, not a dropped config-phase payload.
- **the other payload directions/phases are covered.** Clientbound PLAY goes through the **merged
  `ClientCommonPacketListenerImpl.handleCustomPayload(ClientboundCustomPayloadPacket)`** (NeoForge's body, which
  `ClientPacketListener` does **not** override, so the injected Forge prologue and the Fabric HEAD mixin both
  run); serverbound/default configuration reaches the same method through the config listener's `invokespecial`
  super call (`ClientConfigurationPacketListenerImpl.handleCustomPayload` → `ClientCommonPacketListenerImpl.…`
  at offset 108; `ServerConfigurationPacketListenerImpl.…` → `ServerCommonPacketListenerImpl.…` at offset 33).
  No missing direction beyond R1.

---

## Limits of this hunt (stated, not implied)

- **No client run, no build, no subagent parallelism** (the delegation backend returned `401 INVALID_API_KEY`
  for every scout; results were produced directly). So no finding here is a *runtime* observation except where
  the shipped log line is quoted; R1 and R4 are therefore *verified in bytes but not fired in a session*.
- R4 is explicitly a candidate; I could not settle `MixinFit` false-positive-vs-real without a transfer-API call
  on a double chest / container component.
- R3's magnitude is measured for `AbiAudit` only (204–745 ms); the other four passes are asserted to read every
  class from their code, not timed.
- A1 is out of the four requested buckets; it is included because it is the one observed-`ERROR`, byte-confirmed
  feature loss I found outside the lanes, and dropping it would be dishonest about what the log shows.

## Evidence (`evidence/`)

| file | content |
|---|---|
| `payload-enqueuework-javap.txt` | `javap -c -p` of both `ClientPayloadContext.enqueueWork` overloads + the transformer's single descriptor |
| `registrysync-purefabric-doc.txt` | `KernelForgeWrapperSync.java:50-54` (the stated limit) |
| `damage-seams-javap.txt` | merged `LivingEntity.actuallyHurt` / `Player.hurt` hooks + the injector's 26.2 descriptors |
| `shipped-log-defects.txt` | 0.3.8 instance log: damage-seam `ERROR`, `AbiAudit` timings |
| `anchor-ledger-false-positives.txt` | `IntegratedServer.stopServer`, furnace `handlers`, bookshelf `itemHandler` bytecode |
| `startup-audit-passes.txt` | the five full-jar audit calls at `KernelBoot.java:361-375` |
