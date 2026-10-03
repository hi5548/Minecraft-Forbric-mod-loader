# Read 1 — the entity-event family (`fabric-entity-events-v1` `LivingEntityMixin`, `fabric-data-attachment-api-v1` `EntityMixin`)

The cluster note said these "need the same two-sided read — injector selector from the remapped guest, then
`javap -c` the merged target method and look for the instruction, not just the member — and I would not fold them
into cluster 1 without that." Done here. The result: **these are not merge-deleted anchors.** Every missing
`@At(INVOKE)` target is present in the merged base (under the name the mod's own refmap gives it), two are lambda
renumbers, and two are refactors of the *host* method. A pin would delete a restorable feature, so nothing is
pinned for these two mixins.

Guest jars read (remapped, i.e. the runtime bytes):
`/tmp/w7-remap-cache-final/fabric-entity-events-v1-0.116.17-8edee89c2c7f05a3.jar`,
`/tmp/w7-remap-cache-final/fabric-data-attachment-api-v1-0.116.17-1ff255a265de44f7.jar`.

Merged base: `p0/mc-1.21.1/.forbric-build/out/patched-mc-merged-1.21.1.jar`.

## The reported losses (default arm, console, cristel-lib)

```
[Forbric/Mixin] fabric-data-attachment-api-v1 … EntityMixin applies only partially — 4/6 anchors resolve, missing:
  @At(INVOKE) net.minecraft.entity.Entity.readCustomDataFromNbt in Entity.load,
  @At(INVOKE) net.minecraft.entity.Entity.writeCustomDataToNbt in Entity.saveWithoutId
[Forbric/Mixin] fabric-entity-events-v1 … LivingEntityMixin applies only partially — 15/21 anchors resolve, missing:
  @At(INVOKE) net.minecraft.world.World.sendEntityStatus in LivingEntity.die,
  @At(INVOKE) net.minecraft.entity.LivingEntity.isSleeping in LivingEntity.hurt,
  @Inject target LivingEntity.lambda$checkBedExists$7(Lnet/minecraft/core/BlockPos;)Ljava/lang/Boolean;,
  @At(INVOKE) net.minecraft.world.level.block.BedBlock.getBedOrientation in LivingEntity.getBedOrientation,
  @At(INVOKE) net.minecraft.world.level.Level.setBlock in LivingEntity.startSleeping,
  @Inject target LivingEntity.lambda$stopSleeping$9(Lnet/minecraft/core/BlockPos;)V
```

The compatibility rows report these as CONFIRMED `mixin-injector` for `readEntityAttachments`,
`writeEntityAttachments`, `beforeDamage`, `notifyDeath`, `onIsSleepingInBed`, `setOccupiedState`,
`modifyWakeUpPosition` — i.e. Mixin did not attach them (`FinalMixinApplications` sees the merged handler with 0
callers).

## Where each anchor's instruction actually is

### `fabric-data-attachment-api-v1` `EntityMixin`

`javap -v` of the class:

```
readEntityAttachments:  @Inject(method="Lnet/minecraft/world/entity/Entity;load(Lnet/minecraft/nbt/CompoundTag;)V",
                                at=@At(value="INVOKE",
                                       target="Lnet/minecraft/entity/Entity;readCustomDataFromNbt(Lnet/minecraft/nbt/NbtCompound;)V"))
writeEntityAttachments: @Inject(method="Lnet/minecraft/world/entity/Entity;saveWithoutId(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
                                at=@At(value="INVOKE",
                                       target="Lnet/minecraft/entity/Entity;writeCustomDataToNbt(Lnet/minecraft/nbt/NbtCompound;)V"))
```

The refmap `fabric-data-attachment-api-v1-refmap.json` resolves both targets:

```
'net/minecraft/entity/Entity.readCustomDataFromNbt(Lnet/minecraft/nbt/NbtCompound;)V'
    -> 'Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V'
'net/minecraft/entity/Entity.writeCustomDataToNbt(Lnet/minecraft/nbt/NbtCompound;)V'
    -> 'Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V'
```

Merged `Entity`, `javap -c -p`:

```
saveWithoutId(CompoundTag)CompoundTag:
  496: invokevirtual  // Method addAdditionalSaveData:(Lnet/minecraft/nbt/CompoundTag;)V
load(CompoundTag)V:
  635: invokevirtual  // Method readAdditionalSaveData:(Lnet/minecraft/nbt/CompoundTag;)V
```

Both instructions are there. Verdict: **selector-translation gap**, not a merge deletion. The annotation's
`@At.target` is written `Lnet/minecraft/entity/Entity;readCustomDataFromNbt(…)` (descriptor form, Yarn owner)
while the refmap key is the dotted `net/minecraft/entity/Entity.readCustomDataFromNbt(…)` — an exact-string miss —
so Mixin receives an unresolvable target and skips the injection. The data-attachment feature (persisting and
syncing `AttachmentType` data on entities) is restorable by translating `@At.target` through the refmap the way
`method=` already is (see `MixinNames`). Not pinned.

### `fabric-entity-events-v1` `LivingEntityMixin` — three different causes

Guest annotations (`javap -v`), with the refmap-resolved form in brackets:

| handler | anchor as written | refmap-resolved | merged base |
|---|---|---|---|
| `notifyDeath` | `@At(INVOKE) Lnet/minecraft/world/World;sendEntityStatus(Lnet/minecraft/entity/Entity;B)V` | `Lnet/minecraft/world/level/Level;broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V` | **present** in `die` at `+178` |
| `beforeDamage` | `@At(INVOKE) Lnet/minecraft/entity/LivingEntity;isSleeping()Z` | `Lnet/minecraft/world/entity/LivingEntity;isSleeping()Z` | **present** in `hurt` at `+90` |
| `onIsSleepingInBed` | `@Inject target LivingEntity.lambda$checkBedExists$7(BlockPos)Boolean` (RETURN) | – | **renumbered**: class declares `lambda$checkBedExists$9` / `$10` |
| `setOccupiedState` | `@Redirect target LivingEntity.lambda$stopSleeping$9(BlockPos)V` on `startSleeping`/`lambda$stopSleeping$9`, `@At(INVOKE) Level.setBlock` | – | **renumbered** (`$11`/`$12`) **and** `startSleeping` no longer calls `setBlock` |
| `modifyWakeUpPosition` | `@Redirect target LivingEntity.lambda$stopSleeping$9(BlockPos)V`, `@At(INVOKE) BedBlock.findStandUpPosition` | – | **renumbered**; `findStandUpPosition` present inside `lambda$stopSleeping$11`/`$12` |
| `onGetSleepingDirection` | `@Redirect target LivingEntity.getBedOrientation()Direction`, `@At(INVOKE) BedBlock.getBedOrientation(BlockGetter,BlockPos)` | – | host refactored: `getBedOrientation` calls `BlockState.getBedDirection(LevelReader,BlockPos)` instead |

Merged evidence, `javap -c -p -cp "$MERGED" net.minecraft.world.entity.LivingEntity`:

```
hurt:
   90: invokevirtual  // Method isSleeping:()Z
die:
  178: invokevirtual  // Method net/minecraft/world/level/Level.broadcastEntityEvent:(Lnet/minecraft/world/entity/Entity;B)V
startSleeping(BlockPos)V:            # no Level.setBlock at all; instead:
   41: invokevirtual  // Method net/minecraft/world/level/block/state/BlockState.setBedOccupied:(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V
getBedOrientation()Direction:        # no BedBlock.getBedOrientation; instead:
   54: invokevirtual  // Method net/minecraft/world/level/block/state/BlockState.getBedDirection:(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;
member list (javap -p):
  lambda$stopSleeping$12(BlockPos)V, lambda$stopSleeping$11(BlockPos)V, lambda$checkBedExists$10(BlockPos)Boolean,
  lambda$checkBedExists$9(BlockPos)Boolean          # the mixin's $9/$7 are gone
lambda$stopSleeping$11:  58: invokestatic  // Method net/minecraft/world/level/block/BedBlock.findStandUpPosition:(…)Ljava/util/Optional;
```

Verdict per cause:

* `notifyDeath`, `beforeDamage` — **translation gap** (dotted-owner `@At.target` vs the descriptor form in the
  annotation). Restorable; do not pin.
* `onIsSleepingInBed`, `modifyWakeUpPosition`, `setOccupiedState` — **lambda renumbering** by the merge. This is
  `LambdaSelectorRetarget`'s territory, but note `lambda$checkBedExists` has two same-descriptor candidates
  (`$9`, `$10`) and `lambda$stopSleeping` two more (`$11`, `$12`), so a by-number retarget is not unique here —
  the same ambiguity class that made `$41` a refusal. Do not pin.
* `setOccupiedState`/`onGetSleepingDirection` also carry a **host refactor**: the vanilla call the redirect
  anchored on was rewritten to a `BlockState` method, so those anchors need a retarget to the new call, not a
  stand-down.

Mixin-instance cost of a pin, for completeness: it resolves 15/21 anchors and backs
`ServerLivingEntityEvents.ALLOW_DAMAGE/AFTER_DAMAGE/AFTER_DEATH` and several `EntitySleepEvents` — all live. A
whole-mixin pin would delete them.

## Bottom line

* `EntityMixin`: 2/2 losses are translation gaps; the merged instructions exist. Restore in `MixinNames`, not a pin.
* `LivingEntityMixin`: 2 translation gaps, 2 lambda renumbers, 2 refactors; 15/21 anchors live. Retarget/report.
