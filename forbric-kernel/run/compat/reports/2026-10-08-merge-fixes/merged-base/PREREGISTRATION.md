# Pre-committed reading — 2026-10-08-merge-fixes / merged-base (findings #1 and #4)

**Written before the run's result is read (run launched 2026-10-09 ~08:29 HKT; this file's criteria are fixed now,
and the run's numbers are filled in afterwards, in `README.md` §5, never here).**

## Lane

- Finding **#1 (audit C-registry.md A3)**: `ItemBlockRenderTypes.BLOCK_RENDER_TYPES` — Forge's
  `setRenderLayer(Block, net.minecraftforge.client.ChunkRenderTypeSet)` writes the map under a `Holder.Reference`
  key while `getRenderLayers(BlockState)` reads it by the raw `Block`. Repair: `ForbricMergedBaseCompatTransformer
  #rekeyTheForgeRenderLayerRegistration`.
- Finding **#4 (audit C-registry.md B1)**: `RegistrySynchronization.<clinit>` is MinecraftForge's
  (`DataPackRegistriesHooks.grabNetworkableRegistries`). Decision + bounded repair or a recorded loss: README §6.

## The single run

- Harness: W7Harness `sweep_client.py`, surface **client**, `--only modmenu --limit 1`, quick-play into `W7Client`,
  window-hiding agent **on**.
- Kernel: my worktree build, injected game side; frozen sha to be recorded in
  `evidence/run/frozen-kernel-sha256.txt`. Claimed sha before the run: `5944d167…`.
- Stage: `w7/.stage-scratch/stage` + `w7/.stage-scratch/mc` (class bytes of `ItemBlockRenderTypes` are byte-identical
  to the installed `/Applications/.minecraft/libraries/…/patched-mc-merged-1.21.1.jar`, checked: sha256 of the class
  = `3c1e0921…` in both).
- Reading is taken OFF the run's own frozen kernel and its rundir log only.

## Accepted / rejected at face value (falsifiers in brackets)

**(R1) The repair executes in the real client.** The class-load log must contain the transform's own line, once:

> `[Forbric/MergedBaseCompat] net.minecraft.client.renderer.ItemBlockRenderTypes's Forge setRenderLayer(Block,
> ChunkRenderTypeSet) keyed BLOCK_RENDER_TYPES by a registry delegate … it is now re-keyed to the raw Block and its
> ChunkRenderTypeSet converted to NeoForge's …`

[Falsified if the line is absent, or if a `ClassCastException`/`NoSuchMethodError`/`VerifyError`/`IncompatibleClassChangeError`
appears naming `ItemBlockRenderTypes`, `ChunkRenderTypeSet` or my injected `asList`/`of` calls.]

**(R2) No regression at boot.** `run == PASS`, `world == true`, `confirmed_required == 0`, log has
`ClientSmoke] joined world via quick-play`, `ClientSmoke] client-ready after`, `ClientSmoke] clean disconnect observed`,
and **zero** crash-reports in the rundir.

[Falsified by any `run != PASS`, a crash-report, a `confirmed_required > 0`, or a missing join/ready/disconnect line.]

**(R3) The other four Forge-delegate sites stay as measured.** Off-game probe already fixes this; the run is not asked
to prove it.

**(R4) Honest scope — what the run CANNOT show.** No jar in the corpus references
`net/minecraftforge/client/ChunkRenderTypeSet` (scanned all 228 corpus jars: 0 classes), so no corpus subject
*registers* through the Forge overload. The run therefore demonstrates the repair **applies and links** (R1/R2); it does
**not** demonstrate a Forge mod's render layer changing from opaque to cutout — that half is proven off-game, by the
byte probe (`evidence/forge-render-layer-key-probe-output.txt`) and the value-type analysis in README §3.3. This is
recorded as a limit, not papered over.

## Reading criteria for finding #4

None (no run); its "reading" is the byte evidence of §6 — the two hooks are not interchangeable and the kernel has no
call site that can synthesize Forge's caller-identity guard, so the honest outcome is a **recorded loss** unless §6's
investigation surfaces a bounded repair.
