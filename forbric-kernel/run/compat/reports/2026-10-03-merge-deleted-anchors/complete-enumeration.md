# Complete `mixin-injector` enumeration at the widest depth (2026-10-03)

Source: `reports/2026-10-03-cluster1-verdicts-2/per-mod/run/*/.forbric-kernel/compatibility-report.json`
(kernel `53e0e61e`, `compatibilityPolicy=continue`, default `mixinFit`, 10 subjects). Every distinct id below is
present in one report; the counts are the subjects carrying it. Classified by the standard four types, with the
merged-base read for each new cluster.

## Required + CONFIRMED — the 7 that block a STRICT launch

| id | type | evidence | landed |
|---|---|---|---|
| `entity-events LivingEntityMixin#setOccupiedState` | lambda renumber **+ host refactor** | `@Redirect` on `lambda$stopSleeping$9(BlockPos)V` (merged declares `$11`/`$12`; only `$12` is referenced from `stopSleeping`'s invokedynamic) and on `Level.setBlock` in `startSleeping` (merged calls `BlockState.setBedOccupied(Level,BlockPos,LivingEntity,Z)V`) | lambda half **landed (a48ae7fb)**; host half **landed (524454fc)** — `FabricEntityMixinAnchors.bedOccupation` now accepts the 1.21.1 selector spelling and bridges to `forbric$setBedOccupied`, firing `EntitySleepEvents.SetBedOccupationState` |
| `entity-events LivingEntityMixin#modifyWakeUpPosition` | lambda renumber | `@Redirect` on `lambda$stopSleeping$9(BlockPos)V` → `$12`; `BedBlock.findStandUpPosition` present inside `$12` | **landed (a48ae7fb)** |
| `entity-events LivingEntityMixin#onIsSleepingInBed` | lambda renumber | `@Inject` on `lambda$checkBedExists$7(BlockPos)Boolean` → `$10` (only `$10` referenced) | **landed (a48ae7fb)** |
| `content-registries AbstractFurnaceBlockEntityMixin#canUseAsFuelRedirect` | anchor gone at the call site | merged `isFuel` calls `ForgeHooks.getBurnTime`, not `getFuel`; handler returns Map vs the call's int → not retargetable | **per-injector stand-down landed (6d316525)** |
| `content-registries AbstractFurnaceBlockEntityMixin#getFuelTimeRedirect` | anchor gone at the call site | merged `getBurnDuration+19` calls `ForgeHooks.getBurnTime` | **per-injector stand-down landed (6d316525)** |
| `balm FabricCropBlockMixin#randomTickPreGrow` | apply-time failure (whole mixin aborts) | MixinExtras sugar `getGrowthSpeedCaptureLocals` throws `InvalidInjectionException`; the mixin never applies, so every handler is unattached | balm cluster (report) |
| `balm FabricCropBlockMixin#randomTickPostGrow` | apply-time failure (whole mixin aborts) | same | balm cluster (report) |

## Required + SUSPECTED — the 10 that will be required once the above clear

| id | type | evidence | action |
|---|---|---|---|
| `transfer-api ChiseledBookshelfBlockEntityMixin#setStackBypass` | **kernel false positive** | row `#[setItem(IIItemStack;)V] -> (IIItemStack;Z)V neo=stub`; the `(IIItemStack;Z)V` delegate does **not exist** on the 1.21.1 base, whose `setItem(int,ItemStack)` holds the body (`setNonNullList`/`setChanged`) | **FIXED (2a5dd7a7)** |
| `transfer-api LockableContainerBlockEntityMixin#fabric_redirectMarkDirty` | kernel false positive | same row | **FIXED (2a5dd7a7)** |
| `transfer-api AbstractFurnaceBlockEntityMixin#setStackSuppressUpdate` | kernel false positive | same row | **FIXED (2a5dd7a7)** |
| `transfer-api SimpleInventoryMixin#fabric_redirectMarkDirty` | kernel false positive | same row | **FIXED (2a5dd7a7)** |
| `item-api AnvilScreenHandlerMixin#callAllowEnchantingEvent` | anchor gone | `@Redirect` on `Enchantment.canEnchant(ItemStack)Z` in `AnvilMenu.createResult`; merged createResult+571 calls `ItemStack.supportsEnchantment(Holder)Z` (owner + parameter differ) | **per-injector stand-down landed (this commit)** |
| `recipe-api IngredientMixin#useCustomIngredientPacketCodec` | anchor gone | `@ModifyExpressionValue` on `StreamCodec.map(Function,Function)` in `Ingredient.<clinit>`; merged `<clinit>`+11 calls `Either.map(...)Object` (owner + return differ) | **per-injector stand-down landed (this commit)** |
| `entity-events LivingEntityMixin#onGetSleepingDirection` | host refactor | `@WrapOperation` on `BedBlock.getBedOrientation(BlockGetter,BlockPos)Direction`; merged `getBedOrientation` calls `BlockState.getBedDirection(LevelReader,BlockPos)` | **landed (524454fc)** — `FabricEntityMixinAnchors.sleepDirection` bridges to `forbric$modifySleepingDirection`, firing `EntitySleepEvents.ModifySleepingDirection` |
| `balm FabricCropBlockMixin#getGrowthSpeed` | dead-path overload | merged `CropBlock` declares both `getGrowthSpeed(BlockState,…)` and `getGrowthSpeed(Block,…)`; `randomTick+40` calls the `BlockState` overload, leaving the `Block` one (the mixin's target) uncalled | balm cluster (retarget candidate) |
| `balm FabricCropBlockMixin#getGrowthSpeedCaptureLocals` | apply-time failure | sugar wrapper throws | balm cluster |
| `balm PlayerMixin#getDestroySpeed` | not read this pass | balm-only; grouped with the balm cluster | open |

## CONFIRMED but not required — recorded losses already landed

| id | landed |
|---|---|
| `item-api BrewingStandBlockEntityMixin#hasStackRecipeRemainder`, `#createStackRecipeRemainder` | `ea68dfe4` (pruner; `captureItemStack` stays) |
| `lifecycle WorldChunkMixin#onRemoveBlockEntity` | `0ce5598b` (pruner; Load + two setRemoved handlers stay) |

## Not required, not CONFIRMED

| id | type | evidence |
|---|---|---|
| `block-api LivingEntityMixin#allowTaggedBlocksForTrapdoorClimbing` | dead path | `@Inject` on `LivingEntity.trapdoorUsableAsLadder`, which the merged base declares but nothing calls; vanilla/Forge call it from `onClimbable` |

## Totals

At this depth: 21 distinct `mixin-injector` ids (7 required-CONFIRMED, 10 required-SUSPECTED, 3 landed-recorded,
1 neither) and 26 distinct `mixin`-shape required ids (all SUSPECTED; 17 universe-wide, 9 subject singletons).
After `2a5dd7a7` (transfer ×4 false positive), `58ff96b6` (anvil+ingredient), `6d316525` (furnace fuel) and
`a48ae7fb` (caller-aware lambda disambiguation), the remaining required injectors are entity-events ×4 and balm ×5;
the remaining required block that is not an injector is the `mixin`-shape long tail below.

### The `mixin`-shape long tail is `required` hygiene, not the gate

W7Harness measured it: `req` fell 354 → 317 → 242 across three arms while `cr` stayed 69 → 51 → 51, so these
entries are invisible to the load criterion. 17 of the 26 appear in all 10 subjects (the fabric-api recurring
member-moves plus the one-off relocations shared by every fabric-api closure); 9 are singletons (balm, kiwi,
cobblemon) and cannot be group-fixed by construction. Each keeps working anchors, so none should be pinned whole;
the recurring moves a group fix could address are: `Enchantment.canEnchant` → `ItemStack.supportsEnchantment`,
`Item.hasCraftingRemainingItem` → `ItemStack.hasCraftingRemainingItem`, `RecordCodecBuilder.create` →
`RecordCodecBuilder.mapCodec`, `CustomPacketPayload.codec` (moved), and the dead paths
(`trapdoorUsableAsLadder`, `PiglinAi.isBarterCurrency`).

### Outstanding, separate defect classes (not this pass)

* **cobblemon Auto-Battle** — `IncompatibleClassChangeError`: its `PokemonEntity` overrides a merged-base
  `final LivingEntity.canBreatheUnderwater`. An override/finality conflict, not an anchor, access or translation
  issue; left to its own pass.
* **balm `FabricCropBlockMixin`** — the apply-time `InvalidInjectionException` → `VerifyError` cluster
  ([`read-balm-cropblock.md`](read-balm-cropblock.md)); an apply/recovery defect, its own owner.
* **`builders-enhancements` `mod=FAILED`** (new on the deeper boot, arm 7): `NoSuchMethodError:
  'BlockBehaviour$Properties FabricBlockSettings.method_9630(BlockBehaviour)'` at
  `com.zrollus.bd.block.ModBlocks.<clinit>`. `method_9630` is intermediary for
  `BlockBehaviour$Properties.copyOf(BlockBehaviour)` (`class_4970$class_2251`), and the mod calls it with the
  fabric-api subclass `FabricBlockSettings` as owner — a game member reached through a non-game owner the remap
  did not resolve. A remap gap in an ordinary mod class (the mixin path resolves inherited members; this one did
  not), not an anchor. Revealed, not caused, by the deeper boot.
* **`cobblemon_skills_api` `mod=FAILED`**: `ClassNotFoundException: com/cobblemon/mod/…` — cobblemon core is not in
  the subject's closure (`deps = [fabric-api]`), so its classes referencing cobblemon cannot load. A corpus/closure
  limit (same family as miguelfaction's missing `migueleconomy`), subject-side, not kernel.

### Remaining CONFIRMED per subject (the load gate)

The last fabric blocker was `entity-events LivingEntityMixin#setOccupiedState`, resolved in two halves: `a48ae7fb`
moved the renumbered lambda selector to the merged body, and `524454fc` made `FabricEntityMixinAnchors.bedOccupation`
accept the 1.21.1 selector spelling, so the adapter (which already existed, pinned to the 26.2 spelling) now bridges
`startSleeping`'s `BlockState.setBedOccupied` to `forbric$setBedOccupied` and fires
`EntitySleepEvents.SetBedOccupationState`; `sleepDirection` does the same for `onGetSleepingDirection` /
`ModifySleepingDirection`. Verified on the real 1.21.1 guest + merged bytes with a throwaway: `adapt` returns 2 and
both bridges pass `BasicVerifier`. So fabric `confirmedRequired` should reach 0 and STRICT should pass — the first
loading fabric run — leaving balm's `FabricCropBlockMixin` apply-time cluster, which is separate.

With `cr=0` reached, the first *revealed* blocker was `IllegalAccessError: Bootstrap tried to access private
BuiltInRegistries.createContents()`, and it was not the mixin: the kernel's fabric access-widener pass
(`ClassTweakerTransformer`) was **inert**, because fabric writes wideners in the intermediary namespace and the
runtime classes are Mojmap (verified: `class_7923 method_47487 ()V`, and after `6875f090`'s remap the tweaker's
targets become Mojmap and `createContents` becomes `public`). That fix needs a **fresh remap cache** (REMAP_VERSION
bumped to `1.21.1-6-accesswidener-namespace`).

The translation alone was not enough: `KernelFabricEcosystem.accessWidenerFiles()` read the widener out of
`container.getJar()` — the pre-remap original — while the class loader used the remapped copy, so the runtime still
merged an `intermediary` file. `44bad9da` closes the asymmetry: `jarToRead` resolves a container jar to the
remapped copy (registered by `KernelBoot` right after `remapAll`), `ClassTweakerTransformer`'s summary line now
names the declaring jars, and `KernelFabricEcosystemJarAlignmentTest` is the contract test — the reader and the
class loader must resolve the same jar.


