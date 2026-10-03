# balm `FabricCropBlockMixin` — a failing injector, and the half-applied member it left behind (2026-10-03)

Scope: the apply-time cluster queued by
[`reports/2026-10-03-merge-deleted-anchors/read-balm-cropblock.md`](../2026-10-03-merge-deleted-anchors/read-balm-cropblock.md):
`InvalidInjectionException` on balm's `FabricCropBlockMixin` becoming a `VerifyError` on
`net.minecraft.world.level.block.CropBlock`, which ended the dedicated-server launch of every subject carrying balm
(`/tmp/w7-ab-offcontinue/per-mod/run/001-balm__fabric/console.log:349-351`, `:430-483`).

Verdict of that read: **a kernel apply/recovery defect, not an anchor issue** — "a single failing injector in one
mixin must not turn the whole mixin (or the class) into a VerifyError". This entry lands the recovery. No game JVM,
no sweep; the real bytes are driven through Mixin directly.

## 1. The defect, reproduced on the real bytes

Guest `net/blay09/mods/balm/mixin/FabricCropBlockMixin` (remapped, as Mixin reads it) against the merged base
`CropBlock`, through Mixin's own applicator with a two-jar byte provider (see §"How to re-run"):

```
[Forbric/Mixin] ...FabricCropBlockMixin failed to apply to net.minecraft.world.level.block.CropBlock
    (InvalidInjectionException) ...
org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Implicit variable modifier
    injection failed in net/minecraft/world/level/block/CropBlock::getGrowthSpeedCaptureLocals
  [ -> Inject -> ...FabricCropBlockMixin->@SugarWrapper::getGrowthSpeedCaptureLocals(
        Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;]
Caused by: org.spongepowered.asm.mixin.injection.modify.InvalidImplicitDiscriminatorException:
    Found 0 candidate variables but exactly 1 is required.
  at ModifyVariableInjector.inject(ModifyVariableInjector.java:242)
  at mixinextras .../InjectorWrapperImpl.doGranularInject(InjectorWrapperImpl.java:109)
  at mixinextras .../SugarWrapperImpl.granularInject(SugarWrapperImpl.java:77)
```

Mixin still returns a class — 27039 bytes, 76 methods — and the failure is reported through
`KernelMixinErrorHandler`, exactly as the campaign log shows. What it returns is the half-applied state: two
members of the failed mixin, merged, one of them unverifiable.

```
  private static BlockState localvar$zza000$getGrowthSpeedCaptureLocals(BlockState);
    Code:
         0: aload_1
         1: aload_0
         2: invokeinterface LocalRef.set:(Ljava/lang/Object;)V
         7: aload_0
         8: areturn
  private static float localvar$zza000$getGrowthSpeed(float, Block, BlockGetter, BlockPos);
```

Both are `@MixinMerged(mixin=net.blay09.mods.balm.mixin.FabricCropBlockMixin)` and both are `private static`. The
declared descriptor has ONE parameter; the body still reads slot 1 — the `LocalRef` parameter MixinExtras stripped
from the descriptor but never got to strip from the body, because that rewrite
(`InjectorWrapperImpl.transformHandlerCalls`) runs only after the injection that threw. Writing with
`COMPUTE_FRAMES` (Mixin's `TreeTransformer.writeClass`) computes the frame the DESCRIPTOR implies, so the file is
internally consistent and the mismatch only surfaces when the JVM verifies it:

```
$ java ... repro.Repro3 <merged>:CropBlock woven.class repaired.class <forge-runtime> <neoforge-runtime> <libraries>
=== woven: java.lang.VerifyError: Bad local variable type
  Location: CropBlock.localvar$zza000$getGrowthSpeedCaptureLocals(BlockState)BlockState @0: aload_1
  Reason:   Type top (current frame, locals[1]) is not assignable to reference type
```

That is the campaign error's own shape, raised against the exact bytes Mixin handed over for the exact reason the
campaign hit it. **Where it originates:** not in the mixin, not in the anchor, and not in the merge — in the fact
that Mixin merges a mixin's methods in an earlier pass than the one that injects, reports the failing injector, and
does not undo the merge. The class is defined (the JVM does not verify at `defineClass`); it dies later, at link
time, from `Blocks.<clinit>` — which is why the launch log shows the mixin's failure, dozens of unrelated boot
lines, and only then the end of the process.

## 2. The seam, and why it is this one

Three candidates were on the table; each was rejected for a reason the evidence names:

* **A `GuestInjectorPruner` table entry** (drop balm's two sugar handlers before Mixin reads the class). Rejected
  by the assignment and by the mechanism: this is decided at APPLY time (`Found 0 candidate variables`), not by the
  selector, and a table cannot know which injector a half-rewrite will abandon. The pruner is pre-Mixin and
  hand-listed; the defect is neither.
* **A selector retarget** (the merge-authoried shape change the same read found: the merged `randomTick` calls the
  `(BlockState, …)` overload, leaving balm's `(Block,…)` selector on a body nothing calls). That is a real, separate
  defect and it stays open — see §5.
* **Pinning the whole mixin.** Rejected by the campaign already (`-Dforbric.mixinFit=strict`, cr 11→31 on balm at
  equal depth): it deletes every anchor the mixin has to hide one that does not fit.

What is left is the layer the class actually passes through: the bytes Mixin returned, before the class is defined.
`HalfAppliedMixins.repair` runs there.

## 3. The change

`KernelMixinErrorHandler.onApplyError` already knows the one thing nobody else does — **which class the mixin
failed on** — so it hands that pair to `HalfAppliedMixins`. The pipeline
(`KernelMixinBootstrap.init`) then runs the repair on Mixin's own output:

```
Mixin (via MixinWeaverSlot) → HalfAppliedMixins.repair → NativeCoremodParity → PostMixinFixups
                           → InterfaceDefaultConflictRepair → ForgeTransferShapeAudit.certify
```

`HalfAppliedMixins.repair(className, bytes)`:

1. is a no-op unless Mixin reported a failure for THIS class (one thread-local map lookup; the class is not parsed);
2. takes the members carrying `@MixinMerged(mixin=M)` for a mixin M that failed;
3. replaces the BODY of each such member the verifier rejects with a minimal type-correct throw
   (`new UnsupportedOperationException("forbric: a mixin that failed to apply left this member half-applied")`);
4. leaves name, descriptor, access and `@MixinMerged` intact, so the post-definition audit
   (`FinalMixinApplications`) still finds the mixin's injector and records it as a per-injector loss;
5. logs one WARN naming the class, the mixin and the member(s).

**Exact behaviour change.** Before: the class was handed to the loader with a member no verifier accepts, and the
whole class — and therefore everything loaded after `Blocks.<clinit>` reached it — died with
`VerifyError: Bad local variable type`. After: the class links; the injector that threw is a recorded per-injector
loss; every member the failed mixin merged that the verifier accepts is untouched, so every attachment the mixin
did complete (and every other mixin's) is exactly as Mixin left it. With
`-Dforbric.halfAppliedMixins=off` the old bytes are returned unchanged.

## 4. Evidence

Real bytes, the fix on:

```
=== woven 27039 bytes
[Forbric/Mixin] CropBlock: FabricCropBlockMixin failed to apply and left 2 member(s) the verifier rejects; they
   are neutralised so the class loads — [localvar$zza000$getGrowthSpeedCaptureLocals(...)BlockState, ...]
=== repaired 26430 bytes (changed=true)
    BAD woven    localvar$zza000$getGrowthSpeedCaptureLocals(...)  : Expected an object reference, but found .
    BAD woven    localvar$zza000$getGrowthSpeed(F...)F             : Expected an object reference, but found .
=== woven: 76 methods, 2 unverifiable
=== repaired: 76 methods, 0 unverifiable
=== repair is idempotent on the repaired bytes: true
=== woven:    java.lang.VerifyError: Bad local variable type
=== repaired: java.lang.ExceptionInInitializerError <- java.lang.IllegalArgumentException: Not bootstrapped
              (called from registry ResourceKey[minecraft:root / minecraft:game_event])
```

The repaired class **links and initializes**: the only error left is the game's own "not bootstrapped", i.e. it
RAN its `<clinit>` outside a booted game. Method count is unchanged (76) because nothing was removed.

In-tree, `HalfAppliedMixinsTest` (7 tests, all green in `./gradlew --offline test --tests
'net.forbric.kernel.mixin.HalfAppliedMixinsTest'`):

* builds the half-applied shape with ASM exactly as Mixin writes it (`private static`, one-parameter descriptor,
  `aload_1` body) and asserts the **JVM** rejects the unrepaired class with `VerifyError: Bad local variable type`,
  then links the repaired one — the acceptance's "instead of a VerifyError", decided by HotSpot, not by the test;
* asserts `use()` still calls the handler of the injector that DID attach, and that a second member the verifier
  accepts is untouched (fingerprint-identical) — the recovery is per injector, not per mixin;
* asserts `@MixinMerged` survives and then drives the real post-definition audit
  (`FinalMixinApplications.observe`) over the repaired bytes: the injector is reported **CONFIRMED required loss**
  at `mixin-injector:…@example.Target`. That is the "recorded per-injector loss", end to end inside the kernel;
* asserts a member merged by a mixin that did NOT fail is untouched; a class no mixin failed on comes back
  identity-identical; `-Dforbric.halfAppliedMixins=off` restores the poison;
* asserts `KernelMixinBootstrap` calls `repair` on the result of `transformClassBytes` (bytecode-level, so the
  wiring cannot be dropped silently).

Mutation check: with `repair` short-circuited to `return bytes`, the two behavioural tests fail
(`2 failed`), so the tests measure the repair and not the fixture.

## 5. Cost, and what this does NOT fix

**Cost.** One thread-local map lookup per class the loader defines (the map is empty on every class no mixin failed
on). Only a class Mixin reported a failure for is parsed, and only if a member of the failed mixin actually fails
the verifier is the class rewritten — in the measured case 27039 → 26430 bytes, one method body replaced.
`-Dforbric.halfAppliedMixins=off` restores the previous behaviour exactly.

**Not fixed — the anchor (defect B in the read).** `randomTickPreGrow`, `randomTickPostGrow`, `getGrowthSpeed` and
`getGrowthSpeedCaptureLocals` all stay recorded losses: Mixin aborts the rest of a mixin's injectors at the first
one that throws, so at the moment the class is repaired there is nothing attached to keep. "Keeps its other
anchors" holds only in the sense that the recovery is per injector and the mixin is never stood down: the attached
set is preserved exactly (proved above with a fixture where one injector did attach), and once the
`getGrowthSpeed(Block,…) → getGrowthSpeed(BlockState,…)` selector is retargeted both sugar injectors bind and the
apply failure stops happening. That retarget is a separate, still-open kernel translation gap.

**Not fixed — poison outside the measured shape.** A leftover whose types are all well-formed but whose class
RELATIONSHIPS are wrong (an invoke against a hierarchy the merged base no longer has) needs a hierarchy-aware
verifier, which is not reachable mid-transform; `BasicVerifier` needs no class loading and catches the measured
body/descriptor inconsistency (and any other slot/kind error) but not that. Nothing observed has that shape yet;
when one appears, this is the class to extend.

## 6. How to re-run

Inputs (the ones the read named):

```
MERGED=/Users/charlescai/Desktop/dsh/实验/forbric/p0/mc-1.21.1/.forbric-build/out/patched-mc-merged-1.21.1.jar
GUEST=/tmp/w7-remap-cache-final/balm-fabric-1.21.1-21.0.66-d9ed9b8a0c312770.jar
```

The scratch harness (`/tmp/balmrepro`, not part of the tree) is a minimal `IMixinService` over those two jars:
`repro.Repro` applies Mixin and dumps the result, `repro.Repro2` adds `HalfAppliedMixins.repair` and re-verifies
with ASM, `repro.Repro3` links both classes in a `URLClassLoader` over the merged base, `forge-runtime.jar`,
`neoforge-runtime.jar` and `p0/mc-1.21.1/libraries/**` so the JVM's own verifier answers. Nothing else is needed;
the kernel jar is not on that classpath except for the class under test.

The in-tree test needs no fixtures: `cd forbric-kernel && ./gradlew --offline test --tests
'net.forbric.kernel.mixin.HalfAppliedMixinsTest' --console=plain`.
