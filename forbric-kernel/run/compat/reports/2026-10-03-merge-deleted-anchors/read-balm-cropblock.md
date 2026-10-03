# Read 2 — balm `FabricCropBlockMixin` (the `InvalidInjectionException` on `CropBlock`)

This is the "different signal again — an apply-time exception rather than an unattached handler" case, and the
read finds two independent defects that are easy to conflate because they surface on the same handler names.

Guest: `/tmp/w7-remap-cache-final/balm-fabric-1.21.1-21.0.66-d9ed9b8a0c312770.jar`
→ `net/blay09/mods/balm/mixin/FabricCropBlockMixin.class`.
Merged: `p0/mc-1.21.1/.forbric-build/out/patched-mc-merged-1.21.1.jar`.

## A. The fatal one: an apply-time `InvalidInjectionException` becomes a `VerifyError`

Console (`/tmp/w7-ab-offcontinue/per-mod/run/001-balm__fabric/console.log`), in order:

```
[Forbric/Mixin] balm (balm.fabric.mixins.json):net.blay09.mods.balm.mixin.FabricCropBlockMixin failed to apply to
  net.minecraft.world.level.block.CropBlock (InvalidInjectionException) — Mixin's own report follows; the owning mod balm is marked
[Mixin/mixin] Mixin apply for mod balm failed … InvalidInjectionException Implicit variable modifier injection failed
  in net/minecraft/world/level/block/CropBlock::getGrowthSpeedCaptureLocals
  [ -> Inject -> balm.fabric.mixins.json:FabricCropBlockMixin … ->@SugarWrapper::getGrowthSpeedCaptureLocals(
        Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;]
  at org.spongepowered.asm.mixin.injection.modify.ModifyVariableInjector.inject(ModifyVariableInjector.java:242)
  at com.llamalad7.mixinextras.sugar.impl.SugarWrapperImpl.granularInject(SugarWrapperImpl.java:77)
…
[Forbric/Boot] the game stopped during startup on java.lang.VerifyError: Bad local variable type
  Location: net/minecraft/world/level/block/CropBlock.localvar$zzd000$balm$getGrowthSpeedCaptureLocals(
              Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;
  @0: aload_1
  Reason: Type top (current frame, locals[1]) is not assignable to reference type
  Bytecode: 2b2a b902 9002 002a b0
  at net.minecraft.world.level.block.Blocks.<clinit>(Blocks.java:1423)
```

The annotation is MixinExtras sugar: `@ModifyVariable(method="getGrowthSpeed(Block,BlockGetter,BlockPos)F",
at=@At(value="INVOKE", target="Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"),
@Share("farmBlock") …)`. The sugar injector throws at application; MixinExtras/the kernel leave the generated
`localvar$zzd000$balm$getGrowthSpeedCaptureLocals` helper behind, its stack map frame is wrong, and loading
`CropBlock` (triggered from `Blocks.<clinit>`) dies with `VerifyError`. So the kernel's "mark the owning mod and
carry on" recovery did **not** remove the poisoned class: this is a fatal boot failure, not a report.

Verdict: **its own cluster — a kernel-side apply/recovery defect** (or a `GuestInjectorPruner` job to drop the two
sugar handlers before Mixin runs), not a merge-deleted anchor. A whole-mixin pin would unblock the boot but costs
balm's crop-growth events; it is a fallback, not the fix.

## B. The quieter one: `getGrowthSpeed`'s called overload changed

While the whole mixin fails to apply, *every* handler is unattached, which is why the compatibility rows list
`randomTickPreGrow`, `randomTickPostGrow`, `getGrowthSpeed` and `getGrowthSpeedCaptureLocals` as CONFIRMED. One of
them would also fail on its own:

```
[Forbric/Mixin] guest mixin balm … FabricCropBlockMixin applies only partially on the merged base — 6/8 anchors
resolve, missing: @Inject target CropBlock.getGrowthSpeed never runs: nothing in the merged game calls it;
vanilla and MinecraftForge call it from CropBlock.randomTick, PitcherCropBlock.randomTick, StemBlock.randomTick
```

Merged `CropBlock` (`javap -p` and `javap -c -p`):

```
protected static float getGrowthSpeed(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos);
protected static float getGrowthSpeed(net.minecraft.world.level.block.Block,      net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos);
randomTick(...):
   40: invokestatic  // Method getGrowthSpeed:(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F
```

The merge kept **two** overloads and moved `randomTick`'s call to the `(BlockState, …)` one, leaving vanilla's
`(Block, …)` overload — the one balm's `@ModifyVariable(method="getGrowthSpeed(Block,…)")` targets — with no
caller. So the handler anchors on a method that still exists but never runs. Verdict: **merge-authored shape
change**, retarget the selector to the `(BlockState, …)` overload; not a stand-down.

Note the two defects are independent: A is an application failure on the sugar handlers; B is a dead path on the
plain `@ModifyVariable`. Fixing B does not fix A, and a pin hides both.
