/**
 * Cross-ecosystem block-entity item/fluid/energy transfer, compiled against Fabric transfer 8.x (1.21.1), Forge 52's
 * {@code net.minecraftforge.items}/{@code fluids}/{@code energy} capabilities and NeoForge 21.1's
 * {@code net.neoforged.neoforge.items.IItemHandler}/{@code fluids.capability.IFluidHandler}/{@code energy.IEnergyStorage}
 * plus Team Reborn Energy 4.1. Every built game side carries this package: without the Fabric API or Reborn compile
 * input the build fails rather than shipping a runtime jar without it. It is never a Fabric mod, and neither Fabric
 * API nor Reborn is bundled.
 *
 * <p>PORT(1.21.1): NeoForge 26.2's {@code net.neoforged.neoforge.transfer.*} (ResourceHandler/Resource/ItemResource/
 * FluidResource, EnergyHandler, the Transaction/SnapshotJournal engine) does not exist in 21.1. This package keeps its
 * own minimum of that vocabulary in {@code TransferApi} (the file in this package), and uses
 * Fabric's {@code TransactionContext} as the only real transaction: a native 21.1 handler has only simulate/execute,
 * so exactly the audited standard classes (Forge's and NeoForge's {@code ItemStackHandler}/{@code FluidTank}/
 * {@code EnergyStorage}) are journaled and restored on rollback, while any other native handler is readable (and
 * writable outside a transaction) but refuses a write a transaction could not take back. Every such substitution is
 * marked {@code PORT(1.21.1):} in place.
 *
 * <p>Boot integration: install TransferTransactionHooks and TransferCapabilityFallback in the pre-Mixin chain
 * only when both selected APIs exist. After native capability registration, invoke BlockTransferBridge.install
 * through the GAME classloader. Installation checks every hook marker before exposing any fallback. The native
 * provider gets the first answer; a provider from another ecosystem is consulted only when that answer is absent,
 * the block entity's owner (the mod that registered its type) first. Fabric's generic fallbacks, which wrap any
 * Container, speak only for Fabric-owned and vanilla block entities (TransferPrecedence).
 * TransferIssues.setReporter can connect runtime findings to the kernel's attributed compatibility report.
 *
 * <p>PORT(1.21.1): NeoForge 21.1 exposes no null-result seam in {@code BlockCapability}, so the bridge's
 * {@code BlockTransferBridge.neoFallback} is not yet reachable from a NeoForge query; the boot transform must supply
 * the seam (a RegisterCapabilitiesEvent registration or a BlockCapability mixin). The Forge direction's hook
 * ({@code BlockEntity.forbric$forgeTransferFallback}) and the Fabric fallbacks are unchanged.
 *
 * <p>Fabric's transactional storage is the model: Fabric transactions are real and nested, a native write made
 * inside one is captured by a journal participant (PairedTransactions.Journal) and restored if that transaction
 * aborts, and a native-only entry point opens its own Fabric scope to simulate (abort) or execute (commit). A
 * callback throwing after commit is reported and the engines are released; external side effects are not magically
 * rolled back.
 *
 * <p>Fabric SlottedStorage is required when exposing a NeoForge/Forge item or fluid capability: an arbitrary Storage
 * cannot supply indexed insertion. Fabric has no exact resource-specific validity query; our advertised validity is
 * conservative and the real provider's insert remains authoritative. Fluid units convert exactly at 81 Fabric units
 * per millibucket, with sub-millibucket leftovers retained at their source. Failed quantization trials abort before
 * retry; amounts are never rounded after mutation. Registry identity and DataComponentPatch are preserved.
 *
 * <p>Only a loaded ServerLevel block entity on its server thread is eligible. Directions, including null, are
 * passed unchanged. Wrappers resolve providers afresh for each operation, hold world/entity references weakly, and
 * become empty after removal or unload. Capability invalidation during a transfer aborts its nested operation.
 * Recursive fallback lookup returns absent instead of wrapping itself. No level is force-loaded by this bridge.
 *
 * <p>ForgeSnapshotAdapters grants transactional writes only to exact audited {@code ItemStackHandler} and
 * {@code FluidTank} implementations (Forge's or NeoForge's), with the default or explicitly approved pure fluid
 * validator. All wrappers for one handler share one real journal. Subclasses and arbitrary proxies lose the write
 * facade but keep reads. ForgeLegacyFacades supports the opposite direction, for both declared-capability ecosystems,
 * using a real transaction per simulate/execute call; it never promises that separate old-style calls form an atomic
 * transfer. Non-empty Forge fluid tags require a per-fluid codec that round-trips exactly to the other APIs'
 * component patch. Unslotted Fabric storage and entity/item capabilities remain outside scope. Hoppers are the
 * exception: they reach unslotted and block-only Fabric storages through Fabric's own lookup on NeoForge's
 * found-nothing branches (KernelFabricHopperStorage), independently of the bridge switch.
 *
 * <p>Energy (placed block entities only; item energy is not bridged) uses the same seams, endpoints, precedence,
 * invalidation and recursion guard. 1 FE = 1 E; see EnergyUnits for the int/long rule. Fabric's side is Team Reborn
 * Energy's EnergyStorage.SIDED, through RebornEnergyAdapters and RebornEnergyBridge, the only two classes that name
 * a Reborn type and the only ones the boot seam loads when Reborn is installed; Reborn already speaks Fabric's
 * TransactionContext, so no extra pairing layer exists in 21.1. ForgeEnergyAdapters writes transactionally only to
 * the certified standard EnergyStorage (Forge's or NeoForge's, and subclasses declaring none of the IEnergyStorage
 * methods) through a per-thread journal of its energy field, and gives declared-capability consumers
 * simulate-by-abort / execute-by-commit views of the other two. Without Reborn, Forge and NeoForge energy still
 * bridge.
 *
 * <p>Register ForgeTransferCapabilityFallback immediately after the existing capability composition. Its root
 * BlockEntity fallback declines to answer from a super-call when a subclass overrides getCapability, preserving
 * that subclass's native authority. A Forge consumer can query Fabric/NeoForge block entities inheriting the
 * composed root; arbitrary override shapes are not rewritten. The one exception is BaseContainerBlockEntity's own
 * override, for a Fabric or NeoForge owner: its generic InvWrapper yields to the owner's item capability (never to
 * Fabric's generic Container view), and its super-call reaches the fallback for fluids. The existing provider and
 * invalidation remain live.
 *
 * <p>That InvWrapper is Forge's own generic view, not a handler to audit, and is never reported as refused. For a
 * Forge owner it is the owner's answer, "my whole Container": a NeoForge consumer then gets NeoForge's own
 * {@code net.neoforged.neoforge.items.wrapper.InvWrapper} of that Container (21.1's replacement for 26.2's
 * VanillaContainerWrapper), with no Forbric bridge. Only when no class below the vanilla base declares setItem or
 * onTransfer; a Container with writes of its own gets no NeoForge view at all.
 */
package net.forbric.kernel.runtime.transfer;
