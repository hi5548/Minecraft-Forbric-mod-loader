# Complete `mixin-injector` enumeration at the widest depth (2026-10-03)

Source: `reports/2026-10-03-cluster1-verdicts-2/per-mod/run/*/.forbric-kernel/compatibility-report.json`
(kernel `53e0e61e`, `compatibilityPolicy=continue`, default `mixinFit`, 10 subjects). Every distinct id below is
present in one report; the counts are the subjects carrying it. Classified by the standard four types, with the
merged-base read for each new cluster.

## Required + CONFIRMED — the 7 that block a STRICT launch

| id | type | evidence | landed |
|---|---|---|---|
| `entity-events LivingEntityMixin#setOccupiedState` | lambda renumber **+ host refactor** | `@Redirect` on `lambda$stopSleeping$9(BlockPos)V` (merged declares `$11`/`$12`, two same-descriptor candidates ⇒ `LambdaSelectorRetarget` declines) and on `Level.setBlock` in `startSleeping` (merged calls `BlockState.setBedOccupied(Level,BlockPos,LivingEntity,Z)V`) | declined (ABI + ambiguity) |
| `entity-events LivingEntityMixin#modifyWakeUpPosition` | lambda renumber | `@Redirect` on `lambda$stopSleeping$9(BlockPos)V`; `BedBlock.findStandUpPosition` is present inside `$11`/`$12` | declined (2 candidates) |
| `entity-events LivingEntityMixin#onIsSleepingInBed` | lambda renumber | `@Inject` on `lambda$checkBedExists$7(BlockPos)Boolean`; merged declares `$9`/`$10` | declined (2 candidates) |
| `content-registries AbstractFurnaceBlockEntityMixin#canUseAsFuelRedirect` | anchor gone at the call site | merged `isFuel` calls `ForgeHooks.getBurnTime`, not `getFuel` | per-injector (3/5 anchors survive) |
| `content-registries AbstractFurnaceBlockEntityMixin#getFuelTimeRedirect` | anchor gone at the call site | merged `getBurnDuration+19` calls `ForgeHooks.getBurnTime` | per-injector |
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
| `entity-events LivingEntityMixin#onGetSleepingDirection` | host refactor | `@WrapOperation` on `BedBlock.getBedOrientation(BlockGetter,BlockPos)Direction`; merged `getBedOrientation` calls `BlockState.getBedDirection(LevelReader,BlockPos)` | declined (ABI) |
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

At this depth: 21 distinct `mixin-injector` ids. Of them, 7 required-CONFIRMED, 10 required-SUSPECTED,
3 CONFIRMED-not-required (landed losses), 1 neither. After `2a5dd7a7` (transfer ×4 false positive) and this
commit's anvil+ingredient stand-downs, the required population is entity-events ×4, content-registries ×2, balm ×5
— i.e. the two mechanism clusters (lambda/ABI, apply-time) and the fuel call-site swap, all already reported.
