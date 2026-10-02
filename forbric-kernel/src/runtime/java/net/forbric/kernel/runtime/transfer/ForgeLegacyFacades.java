package net.forbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;

/**
 * Declared-capability facades over a bridge pivot: an old-style {@code IItemHandler}/{@code IFluidHandler}/
 * {@code IEnergyStorage} caller can use a transactional Fabric or native provider. Every simulate call performs a
 * real nested operation then aborts; execute commits that operation. If a native outer scope already exists, it
 * remains the owner of the ultimate commit/rollback. There is no delayed execution based on an earlier simulation
 * and no promise that two distinct old-style calls form one atomic operation.
 *
 * <p>PORT(1.21.1): 26.2's pivot <em>was</em> NeoForge's {@code ResourceHandler}/{@code EnergyHandler}, so
 * {@code NeoForge consumers} needed no facade. In 21.1 the pivot is the bridge's own shape and NeoForge's
 * capabilities are {@code IItemHandler}/{@code IFluidHandler}/{@code IEnergyStorage}, so this file now supplies the
 * NeoForge facades ({@code neoItems}/{@code neoFluids}/{@code neoEnergy}) beside the Forge ones. 26.2's
 * {@code PairedTransactions.fabric/neo} scope pairing is gone; every scope is a Fabric transaction
 * ({@link PairedTransactions#scope()}).
 */
public final class ForgeLegacyFacades {
	private ForgeLegacyFacades() { }
	public static IItemHandler items(ResourceHandler<ItemResource> handler) { return new Items(java.util.Objects.requireNonNull(handler)); }
	public static net.minecraftforge.fluids.capability.IFluidHandler fluids(ResourceHandler<FluidResource> handler) { return new Fluids(java.util.Objects.requireNonNull(handler)); }
	public static net.neoforged.neoforge.items.IItemHandler neoItems(ResourceHandler<ItemResource> handler) { return new NeoItems(java.util.Objects.requireNonNull(handler)); }
	public static net.neoforged.neoforge.fluids.capability.IFluidHandler neoFluids(ResourceHandler<FluidResource> handler) { return new NeoFluids(java.util.Objects.requireNonNull(handler)); }
	public static net.minecraftforge.energy.IEnergyStorage energy(EnergyHandler handler) { return new Energy(java.util.Objects.requireNonNull(handler)); }
	public static net.neoforged.neoforge.energy.IEnergyStorage neoEnergy(EnergyHandler handler) { return new NeoEnergy(java.util.Objects.requireNonNull(handler)); }
	static ResourceHandler<ItemResource> unwrapItems(IItemHandler handler) { return handler instanceof Items own ? own.handler : null; }
	static ResourceHandler<FluidResource> unwrapFluids(net.minecraftforge.fluids.capability.IFluidHandler handler) { return handler instanceof Fluids own ? own.handler : null; }
	/** A scope under the current Fabric transaction, or a fresh outer one: the operation can be committed or aborted. */
	static Transaction scope() { return PairedTransactions.scope(); }
	/**
	 * The endpoint went away during the operation (its block replaced, its capabilities invalidated). The scope was
	 * closed uncommitted, so the provider's own engine rolled it back; an old-style caller, which has no transaction
	 * to abort, is told nothing moved instead of an exception thrown into its tick.
	 */
	private static void invalidated(LiveTransferEndpoints.Unavailable failure, Object handler) {
		NativeTransferAdapters.requireSuccessfulRollback(failure, handler);
		TransferIssues.report("ENDPOINT_INVALIDATED", handler, failure.getMessage() + "; the operation was rolled back");
	}
	private static int amount(long amount) {
		if (amount < 0) throw new IllegalStateException("Provider advertised a negative amount");
		return (int) Math.min(Integer.MAX_VALUE, amount);
	}

	private static ItemStack insert(ResourceHandler<ItemResource> handler, int slot, ItemStack input, boolean simulate) {
		if (input.isEmpty()) return ItemStack.EMPTY;
		int maximum = input.getCount();
		try (var transaction = scope()) {
			int moved = handler.insert(slot, ItemResource.of(input), maximum, transaction);
			ForgeSnapshotAdapters.checkAmount(moved, maximum);
			if (!simulate) transaction.commit();
			return input.copyWithCount(maximum - moved);
		} catch (LiveTransferEndpoints.Unavailable gone) {
			invalidated(gone, handler);
			return input.copy();
		}
	}
	private static ItemStack extract(ResourceHandler<ItemResource> handler, int slot, int maximum, boolean simulate) {
		if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
		if (maximum == 0) return ItemStack.EMPTY;
		ItemResource resource = handler.getResource(slot);
		if (resource.isEmpty()) return ItemStack.EMPTY;
		// IItemHandler's contract, as ItemStackHandler and the ecosystem's own adapter implement it: the result is at
		// most ONE stack, even when the store holds more and the caller asks for more. getStackInSlot may still
		// report the whole amount. An oversized ItemStack is not even encodable by the item codec.
		int limit = Math.min(maximum, resource.getMaxStackSize());
		try (var transaction = scope()) {
			int moved = handler.extract(slot, resource, limit, transaction);
			ForgeSnapshotAdapters.checkAmount(moved, limit);
			if (!simulate) transaction.commit();
			return resource.toStack(moved);
		} catch (LiveTransferEndpoints.Unavailable gone) {
			invalidated(gone, handler);
			return ItemStack.EMPTY;
		}
	}
	private static int fill(ResourceHandler<FluidResource> handler, FluidResource resource, int amount, boolean simulate) {
		try (var transaction = scope()) {
			int moved = handler.insert(resource, amount, transaction);
			ForgeSnapshotAdapters.checkAmount(moved, amount);
			if (!simulate) transaction.commit();
			return moved;
		} catch (LiveTransferEndpoints.Unavailable gone) {
			invalidated(gone, handler);
			return 0;
		}
	}
	/** The first tank whose resource is representable and non-empty, extracted for {@code maximum}; 0 if none. */
	private static int drain(ResourceHandler<FluidResource> handler, FluidResource resource, int maximum, boolean simulate) {
		if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
		if (maximum == 0) return 0;
		try (var transaction = scope()) {
			int moved = handler.extract(resource, maximum, transaction);
			ForgeSnapshotAdapters.checkAmount(moved, maximum);
			if (!simulate) transaction.commit();
			return moved;
		} catch (LiveTransferEndpoints.Unavailable gone) {
			invalidated(gone, handler);
			return 0;
		}
	}
	private static int move(EnergyHandler handler, int maximum, boolean simulate, boolean insert) {
		if (maximum <= 0) return 0;
		try (var transaction = scope()) {
			int moved = (int) EnergyUnits.moved(insert ? handler.insert(maximum, transaction) : handler.extract(maximum, transaction), maximum);
			if (!simulate) transaction.commit();
			return moved;
		} catch (LiveTransferEndpoints.Unavailable invalidated) {
			invalidated(invalidated, handler);
			return 0;
		}
	}

	private record Items(ResourceHandler<ItemResource> handler) implements IItemHandler {
		public int getSlots() { return handler.size(); }
		public ItemStack getStackInSlot(int slot) { return handler.getResource(slot).toStack(amount(handler.getAmountAsLong(slot))); }
		public int getSlotLimit(int slot) { return amount(handler.getCapacityAsLong(slot, handler.getResource(slot))); }
		public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && handler.isValid(slot, ItemResource.of(stack)); }
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) { return insert(handler, slot, input, simulate); }
		public ItemStack extractItem(int slot, int maximum, boolean simulate) { return extract(handler, slot, maximum, simulate); }
	}
	private record NeoItems(ResourceHandler<ItemResource> handler) implements net.neoforged.neoforge.items.IItemHandler {
		public int getSlots() { return handler.size(); }
		public ItemStack getStackInSlot(int slot) { return handler.getResource(slot).toStack(amount(handler.getAmountAsLong(slot))); }
		public int getSlotLimit(int slot) { return amount(handler.getCapacityAsLong(slot, handler.getResource(slot))); }
		public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && handler.isValid(slot, ItemResource.of(stack)); }
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) { return insert(handler, slot, input, simulate); }
		public ItemStack extractItem(int slot, int maximum, boolean simulate) { return extract(handler, slot, maximum, simulate); }
	}
	private record Fluids(ResourceHandler<FluidResource> handler) implements net.minecraftforge.fluids.capability.IFluidHandler {
		public int getTanks() { return handler.size(); }
		public FluidStack getFluidInTank(int tank) {
			FluidStack stack = ForgeFluidMetadata.toForge(handler.getResource(tank), amount(handler.getAmountAsLong(tank)));
			return stack == null ? FluidStack.EMPTY : stack;
		}
		public int getTankCapacity(int tank) { return amount(handler.getCapacityAsLong(tank, handler.getResource(tank))); }
		public boolean isFluidValid(int tank, FluidStack stack) {
			FluidResource resource = ForgeFluidMetadata.toNeo(stack);
			return resource != null && !resource.isEmpty() && handler.isValid(tank, resource);
		}
		public int fill(FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			FluidResource resource = ForgeFluidMetadata.toNeo(stack); if (resource == null) return 0;
			return ForgeLegacyFacades.fill(handler, resource, stack.getAmount(), action.simulate());
		}
		public FluidStack drain(FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return FluidStack.EMPTY;
			FluidResource resource = ForgeFluidMetadata.toNeo(stack);
			return resource == null ? FluidStack.EMPTY : drain(resource, stack.getAmount(), action);
		}
		public FluidStack drain(int maximum, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0) return FluidStack.EMPTY;
			for (int slot = 0; slot < handler.size(); slot++) {
				FluidResource resource = handler.getResource(slot);
				if (!resource.isEmpty() && handler.getAmountAsLong(slot) > 0 && ForgeFluidMetadata.toForge(resource, 1) != null) {
					FluidStack extracted = drain(resource, maximum, action);
					if (!extracted.isEmpty()) return extracted;
				}
			}
			return FluidStack.EMPTY;
		}
		private FluidStack drain(FluidResource resource, int maximum, FluidAction action) {
			if (ForgeFluidMetadata.toForge(resource, 1) == null) return FluidStack.EMPTY;
			int moved = ForgeLegacyFacades.drain(handler, resource, maximum, action.simulate());
			FluidStack result = ForgeFluidMetadata.toForge(resource, moved);
			// The codec is called again deliberately; a stateful or newly failing codec cannot lose the extracted data.
			return result == null ? FluidStack.EMPTY : result;
		}
	}
	private record NeoFluids(ResourceHandler<FluidResource> handler) implements net.neoforged.neoforge.fluids.capability.IFluidHandler {
		public int getTanks() { return handler.size(); }
		public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) { return toNeo(handler.getResource(tank), amount(handler.getAmountAsLong(tank))); }
		public int getTankCapacity(int tank) { return amount(handler.getCapacityAsLong(tank, handler.getResource(tank))); }
		public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
			FluidResource resource = fromNeo(stack);
			return resource != null && !resource.isEmpty() && handler.isValid(tank, resource);
		}
		public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			FluidResource resource = fromNeo(stack); if (resource == null) return 0;
			return ForgeLegacyFacades.fill(handler, resource, stack.getAmount(), action.simulate());
		}
		public net.neoforged.neoforge.fluids.FluidStack drain(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (stack.isEmpty()) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			FluidResource resource = fromNeo(stack);
			return resource == null ? net.neoforged.neoforge.fluids.FluidStack.EMPTY : drain(resource, stack.getAmount(), action);
		}
		public net.neoforged.neoforge.fluids.FluidStack drain(int maximum, FluidAction action) {
			java.util.Objects.requireNonNull(action);
			if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			for (int slot = 0; slot < handler.size(); slot++) {
				FluidResource resource = handler.getResource(slot);
				if (!resource.isEmpty() && handler.getAmountAsLong(slot) > 0) {
					net.neoforged.neoforge.fluids.FluidStack extracted = drain(resource, maximum, action);
					if (!extracted.isEmpty()) return extracted;
				}
			}
			return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
		}
		private net.neoforged.neoforge.fluids.FluidStack drain(FluidResource resource, int maximum, FluidAction action) {
			int moved = ForgeLegacyFacades.drain(handler, resource, maximum, action.simulate());
			return toNeo(resource, moved);
		}
		private static net.neoforged.neoforge.fluids.FluidStack toNeo(FluidResource resource, int amount) {
			if (resource.isEmpty() || amount == 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			var stack = new net.neoforged.neoforge.fluids.FluidStack(resource.getFluid(), amount);
			if (!resource.componentsPatchEmpty()) stack.applyComponents(resource.componentsPatch());
			return stack;
		}
		private static FluidResource fromNeo(net.neoforged.neoforge.fluids.FluidStack stack) {
			return stack.isEmpty() ? FluidResource.EMPTY : FluidResource.of(stack.getFluid(), stack.getComponentsPatch());
		}
	}
	private record Energy(EnergyHandler handler) implements net.minecraftforge.energy.IEnergyStorage {
		public int receiveEnergy(int maximum, boolean simulate) { return move(handler, maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(handler, maximum, simulate, false); }
		public int getEnergyStored() { return EnergyUnits.saturated(handler.getAmountAsLong()); }
		public int getMaxEnergyStored() { return EnergyUnits.saturated(handler.getCapacityAsLong()); }
		public boolean canReceive() { return EnergyAbilities.forgeCanInsert(handler); }
		public boolean canExtract() { return EnergyAbilities.forgeCanExtract(handler); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
	private record NeoEnergy(EnergyHandler handler) implements net.neoforged.neoforge.energy.IEnergyStorage {
		public int receiveEnergy(int maximum, boolean simulate) { return move(handler, maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(handler, maximum, simulate, false); }
		public int getEnergyStored() { return EnergyUnits.saturated(handler.getAmountAsLong()); }
		public int getMaxEnergyStored() { return EnergyUnits.saturated(handler.getCapacityAsLong()); }
		public boolean canReceive() { return EnergyAbilities.forgeCanInsert(handler); }
		public boolean canExtract() { return EnergyAbilities.forgeCanExtract(handler); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}
