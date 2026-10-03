# Merge-deleted anchors — Cluster 1 stand-downs and the equal-depth injector verdicts (2026-10-03)

Scope: the CONFIRMED `mixin-injector` losses at equal depth (default `mixinFit`, `compatibilityPolicy=continue`)
on the fabric bucket, read two-sided — the guest's actual (remapped) annotation selector, then `javap` of the
merged base for the instruction it names. One mixin is a whole-mixin stand-down (its only anchor's type no longer
exists); the rest are per-injector losses with a retarget or a kernel-side translation gap, and are reported
rather than pinned.

This pass is read-only except for the one pin it lands (`MergedBaseMixinCompat.SUPPRESSED_MIXINS`) and its unit
test. No game JVM, no sweep.

## Inputs and commands

```
MERGED="/Users/charlescai/Desktop/dsh/实验/forbric/p0/mc-1.21.1/.forbric-build/out/patched-mc-merged-1.21.1.jar"
#                       ^ the frozen merged base the W7 rows ran on (byte-identical to /tmp p0 copies)
CACHE=/tmp/w7-remap-cache-final              # remapped guest module jars, i.e. what Mixin actually reads
ROWS=/tmp/w7-ab-offcontinue/per-mod/run/*/.forbric-kernel/compatibility-report.json   # POLICY=continue, 3 subjects
```

* Enumerate the population: `python3` over the rows, keeping `id.startswith("mixin-injector:")` and
  `confidence=="CONFIRMED"` (below).
* Guest selector: `unzip -o "$CACHE/<module>.jar" <Class>.class -d <scratch>` then `javap -v -p <Class>.class`.
* Merged instruction: `javap -c -p -cp "$MERGED" <target>` (and `javap -p` for the member list).
* Class existence: `unzip -l "$MERGED" | grep -i <Name>`.
* One run: `cd forbric-kernel && ./gradlew test --tests 'net.forbric.kernel.mixin.ForbricMixinServiceTest' --console=plain --offline`

## Cluster 1 — the two named `MainMixin`s

The cluster's claim was "anchors into the `Main.main` that the merge rewrote; the instruction is gone, and the
merge is why." Reading both named mixins changes that from two stand-downs to **one**, because one of the two
anchors is not gone.

### 1. `fabric-data-generation-api-v1.mixins.json:server.MainMixin` — ANCHOR GONE, stood down

Guest (remapped) annotation, `javap -v` of `net/fabricmc/fabric/mixin/datagen/server/MainMixin.class` from
`fabric-data-generation-api-v1-0.116.17-b60dd4e1dfedf96d.jar`:

```
@Inject(
  method = ["Lnet/minecraft/server/Main;main([Ljava/lang/String;)V"]
  at = [@At(value="NEW", target="Lnet/minecraft/server/dedicated/ServerPropertiesLoader;")]
  cancellable = true)
```

Merged base:

```
$ javap -c -p -cp "$MERGED" net.minecraft.server.Main | grep -nE 'ServerModLoader|ServerPropertiesLoader|DedicatedServerSettings'
    453: invokestatic  // Method net/neoforged/neoforge/server/loading/ServerModLoader.load:()V
    456: new           // class net/minecraft/server/dedicated/DedicatedServerSettings
    462: invokespecial // Method net/minecraft/server/dedicated/DedicatedServerSettings."<init>":(Ljava/nio/file/Path;)V
$ unzip -l "$MERGED" | grep -i ServerProperties
  net/minecraft/server/dedicated/DedicatedServerProperties.class          # the properties holder
  net/minecraft/server/dedicated/DedicatedServerProperties$WorldDimensionData.class
  (no ServerPropertiesLoader.class)
```

`ServerPropertiesLoader` is not in the merged jar at all — the merge replaced the constructor call with
`DedicatedServerSettings` — so the `NEW` anchor can never bind and the mixin's only handler
(`FabricDataGenHelper.run()` then `CallbackInfo.cancel()`) is unreachable from the server entry point.

Verdict: **whole-mixin stand-down** (it declares exactly this one handler, so the pin costs nothing the dead
anchor had not already cost). Cost: fabric-data-generation-api-v1's server-side datagen hook
(`-Dfabric-api.datagen` via `Main.main`); the module's other entry points are separate mixins and stay.
Landed in `MergedBaseMixinCompat.SUPPRESSED_MIXINS` as
`"fabric-data-generation-api-v1.mixins.json:server.MainMixin"` (see the javadoc/comment there for the same
evidence and cost).

### 2. `fabric-registry-sync-v0.mixins.json:MainMixin` — ANCHOR PRESENT, no new stand-down

Guest (remapped) annotation, `javap -v` of `net/fabricmc/fabric/mixin/registry/sync/MainMixin.class` from
`fabric-registry-sync-v0-0.116.17-c49cd030270bba6e.jar`:

```
@Inject(method=["Lnet/minecraft/server/Main;main([Ljava/lang/String;)V"],
        at=[@At(value="INVOKE", target="Lnet/minecraft/Util;startTimerHackThread()V")])
```

Merged base:

```
$ javap -c -p -cp "$MERGED" net.minecraft.server.Main | grep startTimerHack
  429: invokestatic  // Method net/minecraft/Util.startTimerHackThread:()V
```

The instruction the injector names **is present** at `Main.main+429`, so this injector attaches. The entry
already in `SUPPRESSED_MIXINS` is not about a dead anchor at all — it is the freeze-timing pin (the mixin re-runs
`BuiltInRegistries.bootStrap()` after mod init), and `ForbricMixinService.suppressedMixinsFor` even lifts it by
default while `FabricRegistryInitializationMixinAdapter` supplies the freeze.

Note for the record: the cluster message said this mixin anchors "`Util.startTimerHack` — that method name does
not appear in the merged body at all". The selector is `startTimerHackThread` (present); the claim came from
reading the refmap key `net/minecraft/entity/LivingEntity.isSleeping`-style dotted names as-is. No pin change.

### Answer: does the existing pin cover the injector-level report? Yes — it was the wrong `MainMixin`.

`fabric-registry-sync-v0.mixins.json:MainMixin` is already in `SUPPRESSED_MIXINS`. Across every report in
`w7/reports/2026-10-03-*` and the `/tmp/w7-*` arms:

```
$ python3 … # every finding whose id contains `fabric-registry-sync-v0.mixins.json` + `MainMixin`
SUSPECTED 87   → all shape `mixin:` (whole-mixin suspicion), none `mixin-injector:`, none CONFIRMED
mixin-injector: fabric-registry-sync-v0.mixins.json:…MainMixin   → 0 rows
mixin-injector: fabric-data-generation-api-v1.mixins.json:…datagen.server.MainMixin → present on every subject
```

So the pin *does* cover its own mixin's injector report — there is none. The `mixin-injector` `MainMixin` finding
that was matched to it is `fabric-data-generation-api-v1`'s `server.MainMixin`: a **different class in a
different config that shares the simple name**. Suppression is keyed `config:entry`, so the two are independent
(and by my read #1 above, registry-sync's is not even dead). Conclusion: the pin is not failing; the identity
match was. The data-generation mixin needed its own entry, and now has one.

## The equal-depth `mixin-injector` population and verdicts

Union of CONFIRMED `mixin-injector` ids over the equal-depth arms (`/tmp/w7-ab-offcontinue`, `w7-policy-check`;
default `mixinFit`, policy `continue`, retarget on = the "default" arm; 3 subjects: cristellib, balm, chipped).
Seven mixins / fourteen injectors.

Excluded as stale, not as "absent": the `w7/reports/2026-10-03-*` rows are all `policy=STRICT` and name
intermediary classes in their ids (`fabric-message-api-v1 …MinecraftServerMixin#init(…Lnet/minecraft/class_32$class_5143;…)`,
`fabric-resource-loader-v0 …MinecraftServerMixin#onCheckDisabled(…Lnet/minecraft/class_3283;)`, `fabric-screen-handler-api-v1
…ServerPlayerEntityMixin`). They predate the remap fix (8f9d3f76) and were measured against un-remapped guests, so they
are re-derived here only if they reappear on the current kernel; the current arms do not carry them.

| # | config : mixin | CONFIRMED injector(s) | merged-base read | verdict |
|---|---|---|---|---|
| 1 | `fabric-data-generation-api-v1` : `server.MainMixin` | `main` | `@At(NEW) ServerPropertiesLoader`; class absent, `new DedicatedServerSettings`@456 | **ANCHOR GONE → stood down (landed, 4b9eca4e)** |
| 2 | `fabric-registry-sync-v0` : `MainMixin` | – | `Util.startTimerHackThread`@429 present | **SURVIVES** (pin is freeze-timing) |
| 3 | `fabric-data-attachment-api-v1` : `EntityMixin` | `readEntityAttachments`, `writeEntityAttachments` | `load`→`readAdditionalSaveData`@635; `saveWithoutId`→`addAdditionalSaveData`@496 | **TRANSLATION GAP → retargeted (landed, kernel fix)** |
| 4 | `fabric-entity-events-v1` : `LivingEntityMixin` | 6 (see read 1) | 2 translation gaps (`isSleeping`@hurt+90, `broadcastEntityEvent`@die+178) **retargeted (landed)**; 2 lambda renumbers **declined (ambiguous, below)**; 2 host refactors **declined (handler ABI changed)** | **MIXED** |
| 5 | `fabric-content-registries-v0` : `AbstractFurnaceBlockEntityMixin` | `canUseAsFuelRedirect`, `getFuelTimeRedirect` | `isFuel`/`getBurnDuration` call `ForgeHooks.getBurnTime`, not `getFuel` | **per-injector loss** (call site replaced by ForgeHooks); 3/5 anchors survive → pruner's job, not pinned |
| 6 | `fabric-item-api-v1` : `BrewingStandBlockEntityMixin` | `hasStackRecipeRemainder`, `createStackRecipeRemainder` | `@At(NEW)` target is the raw intermediary descriptor `(Lclass_1935;)Lclass_1799;` → **retargeted (landed)**; `Item.hasCraftingRemainingItem` moved to `ItemStack.hasCraftingRemainingItem` → **declined** | **MIXED** |
| 7 | `fabric-lifecycle-events-v1` : `WorldChunkMixin` | `onRemoveBlockEntity` | member+host survive; the slice `from=createBlockEntity` is empty because `Map.remove`@30/47 precede `createBlockEntity`@92; the eviction the handler watches is the `blockEntities.remove`@30 | **RETARGETABLE-SLICE, no mechanism → per-injector stand-down LANDED (GuestInjectorPruner)** |

### Retargets landed this pass (kernel fix, one commit)

The translation gaps are one defect with one cause, not six per-mixin patches: `MixinNames` translated an
`@At(target=…)` only through an **exact** refmap-key match, and Fabric's refmap keys spell the same member
differently from the annotation. Two sub-cases, both fixed in `MixinNames`:

* **Dotted-owner refmap key.** `memberName` returns null for any string containing `/`, so for a key like
  `net/minecraft/entity/LivingEntity.isSleeping()Z` the name table (`byName`) was never populated and
  `selector`'s name fallback could never fire — while the annotation spells the member
  `Lnet/minecraft/entity/LivingEntity;isSleeping()Z`. `memberName` now strips a dotted owner (everything through
  the last `.` before the descriptor) as well as a `Lowner;` owner. This resolves #3 (both injectors) and #4's
  `isSleeping` / `broadcastEntityEvent` (the merged `hurt`/`die` carry those exact calls).
* **Bare constructor descriptor.** An `@At(value="NEW", target="(L…;)L…;")` has no member name, so every member
  path returned it untouched and the `class_*` names inside stayed intermediary. `translateSelector` now maps a
  leading-`(` value through `mapDescriptor`, class by class. This resolves #6's `createStackRecipeRemainder`
  (`new ItemStack` is in the merged `doBrew`).

### Declined, with the reason this pass did not touch it

* **Lambda renumbers (#4, `lambda$stopSleeping$9`, `lambda$checkBedExists$7`).** `LambdaSelectorRetarget` already
  covers these selectors (they are `method=` annotation strings it walks) — but its rule rewrites only when
  enclosing-name + descriptor picks out **exactly one** member, and the merged base declares **two**
  same-descriptor candidates per lambda (`lambda$stopSleeping$11`/`$12`, `lambda$checkBedExists$9`/`$10`). It
  declines them, by design, rather than guess — the `$41` trap. Not a gap in that transformer; a real ambiguity.
* **Host refactors (#4, `startSleeping`/`getBedOrientation`).** The behaviour moved to `BlockState`, but the
  handler ABI did not: `setOccupiedState` matches `Level.setBlock(BlockPos,BlockState,I)Z` and
  `onGetSleepingDirection` matches `BedBlock.getBedOrientation(BlockGetter,BlockPos)Direction`, whereas the new
  call sites are `BlockState.setBedOccupied(Level,BlockPos,LivingEntity,Z)V` and
  `BlockState.getBedDirection(LevelReader,BlockPos)Direction`. An annotation-only retarget cannot re-shape the
  handler, so these are genuine per-injector losses until a bespoke adapter exists.
* **Owner move (#6, `Item.hasCraftingRemainingItem` → `ItemStack.hasCraftingRemainingItem`)** and **slice order
  (#7)**: same class of change — the member and its owner/position moved, so there is no string to rewrite;
  reported.

### Row 7 — why it is a per-injector stand-down and not a pin or a retarget

The handler is `@Redirect` on the `Map.remove` in `LevelChunk.getBlockEntity`, firing
`ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD` when a block entity is removed from a map. The member and the host
both survive; only the ordering changed: the merged method runs **two** `Map.remove`s (`blockEntities`@+30,
`pendingBlockEntities`@+47) **before** `createBlockEntity`@+92, so the `@Slice(from=createBlockEntity)` is empty.
The handler's `checkcast BlockEntity` also means only the +30 site is type-correct (the +47 value is a
`CompoundTag`), so the intent survives at +30 and the anchor is **retargetable in principle** —
`@At(INVOKE, target="Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", ordinal=1)`, or a slice
anchored on `BlockEntity.isRemoved`. But no current transformer rewrites a `@Slice`/`ordinal` (they rewrite
member-name selectors), and the campaign's own measured caution applies: a local selector rewrite changes the boot
depth and therefore the whole report shape, so it has to be judged at equal depth before it ships — too much for
one anchor inside a classification pass.

So it is stood down **per-injector**, via the existing mechanism for exactly this (`GuestInjectorPruner.TABLE`,
which removes the one injector method and keeps the mixin's other three applying), and the loss is **recorded** —
not hidden — as a `CONFIRMED`, `required=false` finding naming it: `BLOCK_ENTITY_UNLOAD` no longer fires for the
eviction at `getBlockEntity+30`; the Load handler and the two `setRemoved`-based unload handlers still fire. A
whole-mixin pin would delete those three; leaving it unpruned keeps a required CONFIRMED loss that stops a STRICT
launch for a feature that cannot work. Also recorded while measuring: the static preflight does **not** model the
empty slice (it logged no `applies only partially` for this mixin), so `GuestInjectorPruner` on a static fit and
the post-application audit disagree here — the audit is right.

### Why only #1 was pinned

The pin list removes a **whole mixin**. #1 declares exactly the one dead handler, so the pin is a pure stand-down.
#5, #6, #7 keep working anchors (e.g. #5 resolves 3/5), so pinning them would delete live features to hide a
per-injector loss — the exact trade the campaign already rejected (`-Dforbric.mixinFit=strict`, cr 11→31 on balm
at equal depth, is that trade measured). Per-injector misses are `GuestInjectorPruner`'s job; these rows are
reported, not suppressed. #3 and #4 must NOT be pinned for a second reason: their instructions are *present* in
the merged base, so a pin would delete a restorable feature.

## The two reads

* [`read-entity-events.md`](read-entity-events.md) — `fabric-entity-events-v1` `LivingEntityMixin` +
  `fabric-data-attachment-api-v1` `EntityMixin`.
* [`read-balm-cropblock.md`](read-balm-cropblock.md) — balm `FabricCropBlockMixin` /
  `InvalidInjectionException` → `VerifyError`.

## What was landed (three commits)

Commit `4b9eca4e` — the Cluster-1 stand-down:

* `forbric-kernel/src/main/java/net/forbric/kernel/mixin/MergedBaseMixinCompat.java` — the `server.MainMixin`
  entry with the instruction evidence and cost in the comment.
* `forbric-kernel/src/test/java/net/forbric/kernel/mixin/ForbricMixinServiceTest.java` —
  `theDataGenerationMainMixinStandDownReachesItsOwnConfigAndIsLiftable`: the shipped entry reaches its own
  config's suppression set and `-Dforbric.keepMixins` still lifts it. Fails before the entry exists.

Commit `28a94dfc` — the translation retargets:

* `forbric-kernel/src/main/java/net/forbric/kernel/mapping/MixinNames.java` — `memberName` reads a dotted-owner
  refmap key; `translateSelector` maps a bare constructor descriptor. Both with the measured shapes in the
  comments.
* `forbric-kernel/src/test/java/net/forbric/kernel/mapping/MixinNamesTest.java` — three tests over the REAL 1.21.1
  mappings (`MappingFixtures`): the dotted no-arg key, the dotted descriptor key, and the `NEW` constructor
  descriptor. All three **fail before the fix** (`3 tests, 0 skipped, 3 failed` on the pre-fix tree) and pass
  after.

Commit `HEAD` — the row-7 per-injector stand-down:

* `forbric-kernel/src/main/java/net/forbric/kernel/transform/GuestInjectorPruner.java` — the `WorldChunkMixin`
  entry (TABLE + the CONFIGS/ACTIVE/COSTS/REASONS/DRIFT/LOSSES rows) and the class-javadoc paragraph.
* `forbric-kernel/src/test/java/net/forbric/kernel/transform/GuestInjectorPrunerTest.java` — two synthetic-byte
  tests (no fixture): the standalone redirect is removed while the same-named `@Inject` handler and the Load
  handler stay, and the loss is recorded as `CONFIRMED`, `required=false`, naming
  `ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD`; and a moved selector stands the edit down.

Verified:

```
cd forbric-kernel && ./gradlew cleanTest test \
  --tests net.forbric.kernel.mapping.MixinNamesTest \
  -Pforbric.mcLibraries=<p0/mc-1.21.1>/libraries --console=plain --offline
  → test: 3 tests, 0 skipped, 0 failed        # with the mapping fixtures reachable
cd forbric-kernel && ./gradlew cleanTest test \
  --tests net.forbric.kernel.transform.GuestInjectorPrunerTest --console=plain --offline
  → test: 15 tests, 13 skipped, 0 failed      # the 2 new synthetic tests run; the 13 need the staged fixtures
cd forbric-kernel && ./gradlew test --tests 'net.forbric.kernel.mixin.*' \
  -Pforbric.mcLibraries=<p0/mc-1.21.1>/libraries --console=plain --offline
  → test: 500 tests, 191 skipped, 1 failed    # LootSupersessionProofTest, missing fixture, pre-existing
                                              # (proven by stash + rerun on the base tree)
```

Regression scope checked: the `mapping.*` + `transform.*` packages carry 23 failures **both before and after**
the `MixinNames` change (all staged-artifact/fixture dependent — `ForbricCacheTest`, `LambdaSelectorRetargetTest`,
`LootTableEventBridgeInjectorTest`, `ModsButtonRedirectorTest`, `TransferTransactionHooksTest`,
`MergedBaseParticleProvidersTest`); the only delta is the three new `MixinNamesTest` cases flipping from fail to
pass.
