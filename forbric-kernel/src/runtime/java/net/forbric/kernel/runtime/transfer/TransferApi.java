package net.forbric.kernel.runtime.transfer;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * PORT(1.21.1): the bridge's own transfer vocabulary.
 *
 * <p>NeoForge 26.2's {@code net.neoforged.neoforge.transfer.*} (Resource/ItemResource/FluidResource,
 * ResourceHandler, EnergyHandler and their transaction engine) does not exist in NeoForge 21.1. The bridge still
 * needs one internal, count-less resource identity and one handler shape to compose every direction through, so
 * this file re-creates exactly that minimum here, over the 21.1 capability types:
 *
 * <ul>
 *   <li>Items: {@link ItemResource} = item + {@link DataComponentPatch}, converted to and from Forge/NeoForge
 *       {@link ItemStack} at every ecosystem adapter (no amount lives in the identity).</li>
 *   <li>Fluids: {@link FluidResource} = fluid + {@link DataComponentPatch}.</li>
 *   <li>{@link ResourceHandler} replaces {@code neoforge.transfer.ResourceHandler} (its slot-indexed
 *       insert/extract), and {@link EnergyHandler} replaces {@code neoforge.transfer.energy.EnergyHandler}.</li>
 * </ul>
 *
 * <p>The transaction token is Fabric's own {@link TransactionContext}, not a NeoForge one: in 1.21.1 Fabric is the
 * only engine with real nested transactions and rollback. A native 21.1 handler has neither, so every write the
 * bridge makes to one through this vocabulary is paired with a journal participant (PairedTransactions.Journal)
 * that restores it if the Fabric transaction aborts.
 *
 * <p>These types are package-private on purpose: nothing outside this package — and nothing boot-side, which sees
 * only {@code BlockTransferBridge}'s public Object-typed seams — may name them.
 */
interface Resource {
	boolean isEmpty();
	DataComponentPatch componentsPatch();
	boolean componentsPatchEmpty();
}

/** PORT(1.21.1): replaces 26.2 {@code neoforge.transfer.item.ItemResource}. */
record ItemResource(Item item, DataComponentPatch componentsPatch) implements Resource {
	static final ItemResource EMPTY = new ItemResource(Items.AIR, DataComponentPatch.EMPTY);

	static ItemResource of(ItemStack stack) {
		return stack.isEmpty() ? EMPTY : new ItemResource(stack.getItem(), stack.getComponentsPatch());
	}
	static ItemResource of(Item item) {
		return new ItemResource(item, DataComponentPatch.EMPTY);
	}
	static ItemResource of(Item item, DataComponentPatch componentsPatch) {
		return item == Items.AIR ? EMPTY : new ItemResource(item, componentsPatch);
	}
	public boolean isEmpty() { return item == Items.AIR; }
	public boolean componentsPatchEmpty() { return componentsPatch.isEmpty(); }
	Item getItem() { return item; }
	/** The stack this identity describes at the given count. Never asks for more than an ItemStack can hold. */
	ItemStack toStack(int count) {
		if (isEmpty() || count <= 0) return ItemStack.EMPTY;
		ItemStack stack = new ItemStack(item, count);
		if (!componentsPatch.isEmpty()) stack.applyComponents(componentsPatch);
		return stack;
	}
	/** Whether an existing stack has exactly this item and component patch. */
	boolean matches(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == item && stack.getComponentsPatch().equals(componentsPatch);
	}
	/** The per-stack limit this identity's components imply, as the game itself would compute it. */
	int getMaxStackSize() { return toStack(1).getMaxStackSize(); }
}

/** PORT(1.21.1): replaces 26.2 {@code neoforge.transfer.fluid.FluidResource}. */
record FluidResource(Fluid fluid, DataComponentPatch componentsPatch) implements Resource {
	static final FluidResource EMPTY = new FluidResource(Fluids.EMPTY, DataComponentPatch.EMPTY);

	static FluidResource of(Fluid fluid) {
		return fluid == Fluids.EMPTY ? EMPTY : new FluidResource(fluid, DataComponentPatch.EMPTY);
	}
	static FluidResource of(Fluid fluid, DataComponentPatch componentsPatch) {
		return fluid == Fluids.EMPTY ? EMPTY : new FluidResource(fluid, componentsPatch);
	}
	public boolean isEmpty() { return fluid == Fluids.EMPTY; }
	public boolean componentsPatchEmpty() { return componentsPatch.isEmpty(); }
	Fluid getFluid() { return fluid; }
}

/**
 * PORT(1.21.1): replaces 26.2 {@code neoforge.transfer.ResourceHandler}. Slot-indexed; the whole-handler overloads
 * walk the slots in index order, exactly as the removed default methods did.
 */
interface ResourceHandler<N extends Resource> {
	int size();
	N getResource(int slot);
	long getAmountAsLong(int slot);
	long getCapacityAsLong(int slot, N resource);
	boolean isValid(int slot, N resource);
	int insert(int slot, N resource, int maximum, TransactionContext transaction);
	int extract(int slot, N resource, int maximum, TransactionContext transaction);

	default int insert(N resource, int maximum, TransactionContext transaction) {
		if (maximum < 0) throw new IllegalArgumentException("Negative transfer amount");
		int moved = 0;
		for (int slot = 0; slot < size() && moved < maximum; slot++) moved += insert(slot, resource, maximum - moved, transaction);
		return moved;
	}
	default int extract(N resource, int maximum, TransactionContext transaction) {
		if (maximum < 0) throw new IllegalArgumentException("Negative transfer amount");
		int moved = 0;
		for (int slot = 0; slot < size() && moved < maximum; slot++) moved += extract(slot, resource, maximum - moved, transaction);
		return moved;
	}
}

/** PORT(1.21.1): replaces 26.2 {@code neoforge.transfer.energy.EnergyHandler}. */
interface EnergyHandler {
	long getAmountAsLong();
	long getCapacityAsLong();
	int insert(int maximum, TransactionContext transaction);
	int extract(int maximum, TransactionContext transaction);
}
