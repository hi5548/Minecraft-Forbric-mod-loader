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
| 1 | `fabric-data-generation-api-v1` : `server.MainMixin` | `main` | `@At(NEW) ServerPropertiesLoader`; class absent, `new DedicatedServerSettings`@456 | **ANCHOR GONE → stood down (landed)** |
| 2 | `fabric-registry-sync-v0` : `MainMixin` | – | `Util.startTimerHackThread`@429 present | **SURVIVES** (pin is freeze-timing) |
| 3 | `fabric-data-attachment-api-v1` : `EntityMixin` | `readEntityAttachments`, `writeEntityAttachments` | `load`→`readAdditionalSaveData`@635; `saveWithoutId`→`addAdditionalSaveData`@496 | **TRANSLATION GAP** (instruction present under the refmap's name) |
| 4 | `fabric-entity-events-v1` : `LivingEntityMixin` | 6 (see read 1) | mix: 2 translation gaps, 2 merge refactors, 2 lambda renumbers | **MIXED** (retarget) |
| 5 | `fabric-content-registries-v0` : `AbstractFurnaceBlockEntityMixin` | `canUseAsFuelRedirect`, `getFuelTimeRedirect` | `isFuel`/`getBurnDuration` call `ForgeHooks.getBurnTime`, not `getFuel` | **ANCHOR GONE at the call site**; 3/5 anchors survive → per-injector loss |
| 6 | `fabric-item-api-v1` : `BrewingStandBlockEntityMixin` | `hasStackRecipeRemainder`, `createStackRecipeRemainder` | `doBrew` calls `ItemStack.hasCraftingRemainingItem()`; `@At(NEW)` target is the raw intermediary descriptor `(Lclass_1935;)Lclass_1799;` | **MIXED** (owner moved Item→ItemStack; + translation gap) |
| 7 | `fabric-lifecycle-events-v1` : `WorldChunkMixin` | `onRemoveBlockEntity` | slice `from=createBlockEntity … to=Map.remove` is empty: `Map.remove`@30/47 precede `createBlockEntity`@92 | **ORDER/SHAPE CHANGE** (retarget the slice) |

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

## What was landed

* `forbric-kernel/src/main/java/net/forbric/kernel/mixin/MergedBaseMixinCompat.java` — the entry above with the
  instruction evidence and cost in the comment.
* `forbric-kernel/src/test/java/net/forbric/kernel/mixin/ForbricMixinServiceTest.java` —
  `theDataGenerationMainMixinStandDownReachesItsOwnConfigAndIsLiftable`: the shipped entry reaches its own
  config's suppression set and `-Dforbric.keepMixins` still lifts it. Fails before the entry exists (the
  `suppressedMixinsFor` assertion), passes after.
* Verified: `cd forbric-kernel && ./gradlew test --tests 'net.forbric.kernel.mixin.ForbricMixinServiceTest'
  --tests 'net.forbric.kernel.mixin.MergedBaseMixinCompatPinnedContractsTest' --console=plain --offline`
  → `15 tests, 2 skipped, 0 failed`. (The 2 skipped are the fabric-api-fixture tests; the 26.2 fixture is not
  present in this checkout, so `TestFixtures.require` skips them.)
