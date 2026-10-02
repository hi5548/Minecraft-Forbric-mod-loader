package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.forbric.kernel.transform.ForgeTransferShapeAudit;

/**
 * Reversible access to explicitly audited native implementations, and the pivot item/fluid views built on them.
 *
 * <p>PORT(1.21.1): 26.2 could lean on NeoForge's transactional {@code ResourceHandler}; NeoForge 21.1's
 * {@code IItemHandler}/{@code IFluidHandler} have only simulate/execute and no rollback at all. So the audited
 * snapshot journal that used to guard Forge's {@code ItemStackHandler}/{@code FluidTank} now guards the NeoForge
 * standard classes too — they are the same shapes ({@code protected NonNullList<ItemStack> stacks},
 * {@code protected FluidStack fluid/capacity/validator}) — because a Fabric transaction that writes a native store
 * and later aborts has to be able to put it back. Non-audited native handlers stay readable but their transactional
 * writes are refused and reported, exactly as Forge's always were.
 *
 * <p>One object-graph journal per native transaction thread preserves aliases, including two handlers sharing a
 * NonNullList, different lists sharing an ItemStack, and tanks/external callers sharing a FluidStack. Restoring only
 * container values would leave those aliases mutated. Every live nested snapshot learns newly encountered objects,
 * retaining its earlier copy of shared ones. Subclasses, proxies, unapproved validators and unapproved transformed
 * standard classes receive no write facade.
 *
 * <p>The journal is now a {@link PairedTransactions.Journal} over Fabric's callbacks (PORT(1.21.1): 26.2's
 * {@code SnapshotJournal} is gone); its nesting, rollback and root-commit semantics are unchanged.
 */
public final class ForgeSnapshotAdapters {
	private ForgeSnapshotAdapters() { }
	private static final Field COMPONENTS = field(ItemStack.class, "components");
	private static final Field COUNT = field(ItemStack.class, "count");
	private static final Field POP_TIME = field(ItemStack.class, "popTime");
	private static final Field NEO_FLUID_COMPONENTS = fieldOrNull(neoFluidStack(), "components");
	private static final ClassValue<Field> STACKS = member("stacks");
	private static final ClassValue<Field> TANK_FLUID = member("fluid");
	private static final ClassValue<Field> TANK_CAPACITY = member("capacity");
	private static final ClassValue<Field> TANK_VALIDATOR = member("validator");
	private static final Set<Predicate<?>> PURE_VALIDATORS = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final ThreadLocal<ForgeJournal> CURRENT = new ThreadLocal<>();
	private static volatile boolean itemHelpersReady, fluidHelpersReady;
	private static boolean defaultForgeValidatorKnown, defaultNeoValidatorKnown;
	private static final ClassValue<Boolean> CERTIFIED = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try { type.getDeclaredMethod(ForgeTransferShapeAudit.MARKER); return true; }
			catch (NoSuchMethodException | LinkageError unverified) { return false; }
		}
	};
	/** An {@link Item} whose stack limit and component helpers are the game's own, so (item, patch) is a full identity. */
	private static final ClassValue<Boolean> PURE_ITEM = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("getMaxStackSize", ItemStack.class).getDeclaringClass().getName().equals(ITEM_EXTENSION)
						&& type.getMethod("components").getDeclaringClass() == Item.class
						&& type.getMethod("builtInRegistryHolder").getDeclaringClass() == Item.class
						&& type.getMethod("getDefaultMaxStackSize").getDeclaringClass() == Item.class;
			} catch (ReflectiveOperationException | LinkageError unknown) { return false; }
		}
	};
	private static final String ITEM_EXTENSION = "net.neoforged.neoforge.common.extensions.IItemExtension";
	private static final String FORGE_ITEM_HANDLER = "net.minecraftforge.items.ItemStackHandler";
	private static final String NEO_ITEM_HANDLER = "net.neoforged.neoforge.items.ItemStackHandler";
	private static final String FORGE_FLUID_TANK = "net.minecraftforge.fluids.capability.templates.FluidTank";
	private static final String NEO_FLUID_TANK = "net.neoforged.neoforge.fluids.capability.templates.FluidTank";

	private static Class<?> neoFluidStack() {
		try { return Class.forName("net.neoforged.neoforge.fluids.FluidStack", false, ForgeSnapshotAdapters.class.getClassLoader()); }
		catch (ClassNotFoundException absent) { return null; }
	}
	private static Field field(Class<?> type, String name) {
		try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
	}
	private static Field fieldOrNull(Class<?> type, String name) {
		if (type == null) return null;
		try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException absent) { return null; }
	}
	/** A field of the same name on every concrete handler class this adapter audits (they share the shape). */
	private static ClassValue<Field> member(String name) {
		return new ClassValue<>() {
			protected Field computeValue(Class<?> type) {
				for (Class<?> at = type; at != null; at = at.getSuperclass()) {
					try { Field field = at.getDeclaredField(name); field.setAccessible(true); return field; }
					catch (NoSuchFieldException absent) { }
				}
				return null;
			}
		};
	}
	@SuppressWarnings("unchecked") private static NonNullList<ItemStack> backing(Object handler) {
		try { return (NonNullList<ItemStack>) STACKS.get(handler.getClass()).get(handler); }
		catch (IllegalAccessException | NullPointerException failure) { throw new IllegalStateException(failure); }
	}
	@SuppressWarnings("unchecked") private static Predicate<?> validator(Object tank) {
		try { return (Predicate<?>) TANK_VALIDATOR.get(tank.getClass()).get(tank); }
		catch (IllegalAccessException | NullPointerException failure) { throw new IllegalStateException(failure); }
	}
	private static Object tankFluid(Object tank) {
		try { return TANK_FLUID.get(tank.getClass()).get(tank); }
		catch (IllegalAccessException | NullPointerException failure) { throw new IllegalStateException(failure); }
	}
	private static int tankCapacity(Object tank) {
		try { return TANK_CAPACITY.get(tank.getClass()).getInt(tank); }
		catch (IllegalAccessException | NullPointerException failure) { throw new IllegalStateException(failure); }
	}
	private static void tankRestore(Object tank, Object fluid, int capacity, Object validator) {
		try {
			TANK_FLUID.get(tank.getClass()).set(tank, fluid);
			TANK_CAPACITY.get(tank.getClass()).setInt(tank, capacity);
			TANK_VALIDATOR.get(tank.getClass()).set(tank, validator);
		} catch (IllegalAccessException | NullPointerException failure) { throw new IllegalStateException(failure); }
	}
	/** Register only an explicitly audited, side-effect-free validator INSTANCE; never a name-based guess. */
	public static synchronized void approvePureValidator(Predicate<net.minecraftforge.fluids.FluidStack> validator) { PURE_VALIDATORS.add(java.util.Objects.requireNonNull(validator)); }
	public static synchronized void approvePureNeoValidator(Predicate<net.neoforged.neoforge.fluids.FluidStack> validator) { PURE_VALIDATORS.add(java.util.Objects.requireNonNull(validator)); }

	/** Whether this exact object is an audited, reversible item handler (Forge's or NeoForge's standard class). */
	public static boolean supportsItems(Object handler) {
		if (handler == null) return false;
		String name = handler.getClass().getName();
		return (name.equals(FORGE_ITEM_HANDLER) || name.equals(NEO_ITEM_HANDLER))
				&& backing(handler).getClass() == NonNullList.class && audited(handler.getClass()) && itemHelpers();
	}
	/** Whether this exact object is an audited, reversible fluid tank (Forge's or NeoForge's standard class). */
	public static synchronized boolean supportsFluids(Object handler) {
		if (handler == null) return false;
		String name = handler.getClass().getName();
		if (!(name.equals(FORGE_FLUID_TANK) || name.equals(NEO_FLUID_TANK)) || !audited(handler.getClass()) || !fluidHelpers()) return false;
		// Do not run even a probe constructor until its final definition and helpers have been certified.
		if (name.equals(FORGE_FLUID_TANK)) {
			if (!defaultForgeValidatorKnown) { PURE_VALIDATORS.add(validator(newTank(FORGE_FLUID_TANK))); defaultForgeValidatorKnown = true; }
		} else {
			if (!defaultNeoValidatorKnown) { PURE_VALIDATORS.add(validator(newTank(NEO_FLUID_TANK))); defaultNeoValidatorKnown = true; }
		}
		return PURE_VALIDATORS.contains(validator(handler));
	}
	private static Object newTank(String name) {
		try {
			Class<?> type = Class.forName(name, false, ForgeSnapshotAdapters.class.getClassLoader());
			return type.getConstructor(int.class).newInstance(0);
		} catch (ReflectiveOperationException | LinkageError absent) { throw new IllegalStateException("A certified tank class could not be instantiated", absent); }
	}
	private static boolean audited(Class<?> type) {
		boolean approved = CERTIFIED.get(type);
		if (!approved) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", type.getName(), ForgeTransferShapeAudit.declined(type.getName()));
		return approved;
	}
	private static boolean helpers(List<String> names) {
		for (String name : names) try {
			if (!audited(Class.forName(name, false, ForgeSnapshotAdapters.class.getClassLoader()))) return false;
		} catch (ClassNotFoundException | LinkageError absent) {
			TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", name, "required transfer helper is absent"); return false;
		}
		return true;
	}
	private static boolean itemHelpers() {
		if (itemHelpersReady) return true;
		if (!helpers(ForgeTransferShapeAudit.ITEM_HELPERS) || !safeAddedInterfaces(ItemStack.class) || !safeAddedInterfaces(Item.class)) return false;
		itemHelpersReady = true; return true;
	}
	private static boolean fluidHelpers() {
		if (fluidHelpersReady) return true;
		if (!helpers(ForgeTransferShapeAudit.FLUID_HELPERS)) return false;
		fluidHelpersReady = true; return true;
	}
	private static boolean safeAddedInterfaces(Class<?> owner) {
		Set<String> critical = Set.of("get", "getOrDefault", "getComponents", "getComponentsPatch", "components", "builtInRegistryHolder", "getDefaultMaxStackSize", "getMaxStackSize", "getCount", "setCount", "grow", "shrink", "copy", "copyWithCount", "isEmpty", "typeHolder", "getItem", "gatherCapabilities");
		for (Class<?> contract : owner.getInterfaces()) {
			if (!ForgeTransferShapeAudit.NON_TRANSFER_FABRIC_INTERFACES.contains(contract.getName().replace('.', '/'))) continue;
			boolean safe = contract.getInterfaces().length == 0;
			for (var method : contract.getDeclaredMethods()) if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) && critical.contains(method.getName())) safe = false;
			if (!safe) { TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", contract.getName(), "an added interface can override a transfer-critical helper"); return false; }
		}
		return true;
	}
	private static boolean pureItem(Item item) {
		boolean pure = PURE_ITEM.get(item.getClass());
		if (!pure) TransferIssues.report("ITEM_TRANSFER_HELPER_UNVERIFIED", item, "custom capacity/component helper needs an explicit reversible adapter");
		return pure;
	}
	private static void refused(Object handler, String kind) {
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", handler,
				"The " + kind + " provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
	}
	/** PORT(1.21.1): the audit now covers the NeoForge standard item handler as well as Forge's. A handler that is
	 * not audited still gets a view (reads and {@code isValid} work, and a write outside any transaction is final),
	 * but a write that a Fabric transaction would have to be able to roll back is refused and reported. */
	public static ResourceHandler<ItemResource> items(net.minecraftforge.items.IItemHandler handler) { return items(handler, handler, () -> { }); }
	public static ResourceHandler<ItemResource> items(net.minecraftforge.items.IItemHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		var unwrapped = ForgeLegacyFacades.unwrapItems(handler); if (unwrapped != null) return unwrapped;
		if (handler == null) return null;
		return new ItemView(handler, new ForgeItems(), owner, changed, reversibleItems(handler));
	}
	public static ResourceHandler<ItemResource> items(net.neoforged.neoforge.items.IItemHandler handler) { return items(handler, handler, () -> { }); }
	public static ResourceHandler<ItemResource> items(net.neoforged.neoforge.items.IItemHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		if (handler == null) return null;
		return new ItemView(handler, new NeoItems(), owner, changed, reversibleItems(handler));
	}
	public static ResourceHandler<FluidResource> fluids(net.minecraftforge.fluids.capability.IFluidHandler handler) { return fluids(handler, handler, () -> { }); }
	public static ResourceHandler<FluidResource> fluids(net.minecraftforge.fluids.capability.IFluidHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		var unwrapped = ForgeLegacyFacades.unwrapFluids(handler); if (unwrapped != null) return unwrapped;
		if (handler == null) return null;
		return new FluidView(handler, new ForgeFluids(), owner, changed, reversibleFluids(handler));
	}
	public static ResourceHandler<FluidResource> fluids(net.neoforged.neoforge.fluids.capability.IFluidHandler handler) { return fluids(handler, handler, () -> { }); }
	public static ResourceHandler<FluidResource> fluids(net.neoforged.neoforge.fluids.capability.IFluidHandler handler, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		if (handler == null) return null;
		return new FluidView(handler, new NeoFluids(), owner, changed, reversibleFluids(handler));
	}
	private static boolean reversibleItems(Object handler) {
		boolean reversible = supportsItems(handler);
		if (!reversible) refused(handler, "item");
		return reversible;
	}
	private static boolean reversibleFluids(Object handler) {
		boolean reversible = supportsFluids(handler);
		if (!reversible) refused(handler, "fluid");
		return reversible;
	}
	private static ForgeJournal journal() {
		ForgeJournal journal = CURRENT.get();
		if (journal == null || !Transaction.isOpen()) { journal = new ForgeJournal(); CURRENT.set(journal); }
		return journal;
	}

	/** The native handler operations the pivot view needs; one per ecosystem interface (they share the ItemStack type). */
	private interface ItemOps {
		int slots(Object handler);
		ItemStack get(Object handler, int slot);
		int insert(Object handler, int slot, ItemStack stack);
		int extract(Object handler, int slot, int amount);
		int limit(Object handler, int slot);
		boolean valid(Object handler, int slot, ItemStack stack);
	}
	private static final class ForgeItems implements ItemOps {
		public int slots(Object handler) { return ((net.minecraftforge.items.IItemHandler) handler).getSlots(); }
		public ItemStack get(Object handler, int slot) { return ((net.minecraftforge.items.IItemHandler) handler).getStackInSlot(slot); }
		public int insert(Object handler, int slot, ItemStack stack) { return stack.getCount() - ((net.minecraftforge.items.IItemHandler) handler).insertItem(slot, stack, false).getCount(); }
		public int extract(Object handler, int slot, int amount) { return ((net.minecraftforge.items.IItemHandler) handler).extractItem(slot, amount, false).getCount(); }
		public int limit(Object handler, int slot) { return ((net.minecraftforge.items.IItemHandler) handler).getSlotLimit(slot); }
		public boolean valid(Object handler, int slot, ItemStack stack) { return ((net.minecraftforge.items.IItemHandler) handler).isItemValid(slot, stack); }
	}
	private static final class NeoItems implements ItemOps {
		public int slots(Object handler) { return ((net.neoforged.neoforge.items.IItemHandler) handler).getSlots(); }
		public ItemStack get(Object handler, int slot) { return ((net.neoforged.neoforge.items.IItemHandler) handler).getStackInSlot(slot); }
		public int insert(Object handler, int slot, ItemStack stack) { return stack.getCount() - ((net.neoforged.neoforge.items.IItemHandler) handler).insertItem(slot, stack, false).getCount(); }
		public int extract(Object handler, int slot, int amount) { return ((net.neoforged.neoforge.items.IItemHandler) handler).extractItem(slot, amount, false).getCount(); }
		public int limit(Object handler, int slot) { return ((net.neoforged.neoforge.items.IItemHandler) handler).getSlotLimit(slot); }
		public boolean valid(Object handler, int slot, ItemStack stack) { return ((net.neoforged.neoforge.items.IItemHandler) handler).isItemValid(slot, stack); }
	}
	/** The fluid operations, including the per-ecosystem metadata conversion and snapshot value handling. */
	private interface FluidOps {
		int tanks(Object handler);
		Object fluid(Object handler, int tank);
		int amount(Object stack);
		boolean isEmpty(Object stack);
		FluidResource resource(Object stack);
		Object request(FluidResource resource, int maximum, Object held);
		int fill(Object handler, Object stack);
		Object drain(Object handler, Object stack);
		int capacity(Object handler, int tank);
		boolean valid(Object handler, Object stack);
		Object copy(Object stack);
		void restore(Object stack, Object value);
	}
	private static final class ForgeFluids implements FluidOps {
		public int tanks(Object handler) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).getTanks(); }
		public Object fluid(Object handler, int tank) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).getFluidInTank(tank); }
		public int amount(Object stack) { return ((net.minecraftforge.fluids.FluidStack) stack).getAmount(); }
		public boolean isEmpty(Object stack) { return ((net.minecraftforge.fluids.FluidStack) stack).isEmpty(); }
		public FluidResource resource(Object stack) { return ForgeFluidMetadata.toNeo((net.minecraftforge.fluids.FluidStack) stack); }
		public Object request(FluidResource resource, int maximum, Object held) {
			// A tank holding an empty-but-present tag ({}) must be filled with a request carrying that exact tag:
			// FluidTank compares with null != {}. See the class comment; this mirrors 26.2's request().
			var stack = (net.minecraftforge.fluids.FluidStack) held;
			if (!stack.isEmpty() && stack.hasTag() && stack.getTag().isEmpty() && resource.componentsPatchEmpty()
					&& stack.getFluid() == resource.getFluid()) return new net.minecraftforge.fluids.FluidStack(stack, maximum);
			return ForgeFluidMetadata.toForge(resource, maximum);
		}
		public int fill(Object handler, Object stack) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).fill((net.minecraftforge.fluids.FluidStack) stack, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE); }
		public Object drain(Object handler, Object stack) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).drain((net.minecraftforge.fluids.FluidStack) stack, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE); }
		public int capacity(Object handler, int tank) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).getTankCapacity(tank); }
		public boolean valid(Object handler, Object stack) { return ((net.minecraftforge.fluids.capability.IFluidHandler) handler).isFluidValid(0, (net.minecraftforge.fluids.FluidStack) stack); }
		public Object copy(Object stack) {
			var value = (net.minecraftforge.fluids.FluidStack) stack;
			return new ForgeFluidValue(value.getAmount(), value.hasTag() ? value.getTag().copy() : null);
		}
		public void restore(Object stack, Object value) { ((ForgeFluidValue) value).restore((net.minecraftforge.fluids.FluidStack) stack); }
	}
	private static final class NeoFluids implements FluidOps {
		public int tanks(Object handler) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).getTanks(); }
		public Object fluid(Object handler, int tank) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).getFluidInTank(tank); }
		public int amount(Object stack) { return ((net.neoforged.neoforge.fluids.FluidStack) stack).getAmount(); }
		public boolean isEmpty(Object stack) { return ((net.neoforged.neoforge.fluids.FluidStack) stack).isEmpty(); }
		public FluidResource resource(Object stack) {
			var value = (net.neoforged.neoforge.fluids.FluidStack) stack;
			return value.isEmpty() ? FluidResource.EMPTY : FluidResource.of(value.getFluid(), value.getComponentsPatch());
		}
		public Object request(FluidResource resource, int maximum, Object held) {
			var stack = new net.neoforged.neoforge.fluids.FluidStack(resource.getFluid(), maximum);
			if (!resource.componentsPatchEmpty()) stack.applyComponents(resource.componentsPatch());
			return stack;
		}
		public int fill(Object handler, Object stack) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).fill((net.neoforged.neoforge.fluids.FluidStack) stack, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE); }
		public Object drain(Object handler, Object stack) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).drain((net.neoforged.neoforge.fluids.FluidStack) stack, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE); }
		public int capacity(Object handler, int tank) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).getTankCapacity(tank); }
		public boolean valid(Object handler, Object stack) { return ((net.neoforged.neoforge.fluids.capability.IFluidHandler) handler).isFluidValid(0, (net.neoforged.neoforge.fluids.FluidStack) stack); }
		public Object copy(Object stack) {
			var value = (net.neoforged.neoforge.fluids.FluidStack) stack;
			return new NeoFluidValue(value.getAmount(), value.getComponents().copy());
		}
		public void restore(Object stack, Object value) { ((NeoFluidValue) value).restore((net.neoforged.neoforge.fluids.FluidStack) stack); }
	}
	private record ForgeFluidValue(int amount, CompoundTag tag) {
		void restore(net.minecraftforge.fluids.FluidStack stack) { stack.setTag(tag == null ? null : tag.copy()); stack.setAmount(amount); }
	}
	private record NeoFluidValue(int amount, PatchedDataComponentMap components) {
		void restore(net.neoforged.neoforge.fluids.FluidStack stack) {
			try { ((PatchedDataComponentMap) NEO_FLUID_COMPONENTS.get(stack)).restorePatch(components.asPatch()); }
			catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
			stack.setAmount(amount);
		}
	}
	private record ItemView(Object handler, ItemOps ops, Object owner, Runnable changed, boolean reversible) implements ResourceHandler<ItemResource> {
		public int size() { return ops.slots(handler); }
		public ItemResource getResource(int slot) {
			ItemStack stack = ops.get(handler, slot);
			return stack.isEmpty() || !pureItem(stack.getItem()) ? ItemResource.EMPTY : ItemResource.of(stack);
		}
		public long getAmountAsLong(int slot) { return getResource(slot).isEmpty() ? 0 : ops.get(handler, slot).getCount(); }
		public long getCapacityAsLong(int slot, ItemResource resource) {
			return !resource.isEmpty() && !pureItem(resource.getItem()) ? 0 : Math.min(ops.limit(handler, slot), resource.isEmpty() ? Integer.MAX_VALUE : resource.getMaxStackSize());
		}
		public boolean isValid(int slot, ItemResource resource) { return !resource.isEmpty() && pureItem(resource.getItem()) && ops.valid(handler, slot, resource.toStack(1)); }
		public int insert(int slot, ItemResource resource, int maximum, TransactionContext tx) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0 || resource.isEmpty()) return 0;
			if (!pureItem(resource.getItem())) return 0;
			if (tx != null && !reversible) return 0; // reported once per class when the view was built
			ForgeJournal journal = tx == null ? null : journal();
			if (journal != null) journal.prepare(handler, tx);
			int moved = ops.insert(handler, slot, resource.toStack(maximum)); checkAmount(moved, maximum);
			if (moved > 0) { if (journal == null) changed.run(); else journal.notifications.put(owner, changed); }
			return moved;
		}
		public int extract(int slot, ItemResource resource, int maximum, TransactionContext tx) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0 || resource.isEmpty() || !resource.matches(ops.get(handler, slot))) return 0;
			if (!pureItem(resource.getItem())) return 0;
			if (tx != null && !reversible) return 0; // reported once per class when the view was built
			ForgeJournal journal = tx == null ? null : journal();
			if (journal != null) journal.prepare(handler, tx);
			int moved = ops.extract(handler, slot, maximum); checkAmount(moved, maximum);
			if (moved > 0) { if (journal == null) changed.run(); else journal.notifications.put(owner, changed); }
			return moved;
		}
	}
	private record FluidView(Object handler, FluidOps ops, Object owner, Runnable changed, boolean reversible) implements ResourceHandler<FluidResource> {
		public int size() { return 1; }
		public FluidResource getResource(int slot) { java.util.Objects.checkIndex(slot, 1); var resource = ops.resource(ops.fluid(handler, 0)); return resource == null ? FluidResource.EMPTY : resource; }
		public long getAmountAsLong(int slot) { return getResource(slot).isEmpty() ? 0 : ops.amount(ops.fluid(handler, 0)); }
		public long getCapacityAsLong(int slot, FluidResource resource) { java.util.Objects.checkIndex(slot, 1); return reversible && request(resource, 1) != null ? ops.capacity(handler, 0) : 0; }
		public boolean isValid(int slot, FluidResource resource) {
			java.util.Objects.checkIndex(slot, 1); Object stack = request(resource, 1);
			return reversible && stack != null && !ops.isEmpty(stack) && ops.valid(handler, stack);
		}
		/**
		 * The native request for a resource the tank already holds, so an empty-but-present tag is preserved where the
		 * ecosystem distinguishes it (see ForgeFluids.request).
		 */
		private Object request(FluidResource resource, int maximum) {
			return ops.request(resource, maximum, ops.fluid(handler, 0));
		}
		public int insert(int slot, FluidResource resource, int maximum, TransactionContext tx) {
			java.util.Objects.checkIndex(slot, 1); if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0 || resource.isEmpty() || (tx != null && !reversible)) return 0;
			Object stack = request(resource, maximum); if (stack == null) return 0;
			ForgeJournal journal = tx == null ? null : journal();
			if (journal != null) journal.prepare(handler, tx);
			int moved = ops.fill(handler, stack); checkAmount(moved, maximum);
			if (journal != null) journal.nativeFluidOperationFinished(handler);
			if (moved > 0) { if (journal == null) changed.run(); else journal.notifications.put(owner, changed); }
			return moved;
		}
		public int extract(int slot, FluidResource resource, int maximum, TransactionContext tx) {
			java.util.Objects.checkIndex(slot, 1); if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0 || resource.isEmpty() || (tx != null && !reversible)) return 0;
			Object stack = request(resource, maximum); if (stack == null) return 0;
			ForgeJournal journal = tx == null ? null : journal();
			if (journal != null) journal.prepare(handler, tx);
			Object drained = ops.drain(handler, stack);
			int moved = drained == null ? 0 : ops.amount(drained); checkAmount(moved, maximum);
			if (journal != null) journal.nativeFluidOperationFinished(handler);
			if (moved > 0) { if (journal == null) changed.run(); else journal.notifications.put(owner, changed); }
			return moved;
		}
	}
	static void checkAmount(int moved, int maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned invalid amount " + moved + "/" + maximum);
	}

	private record TankBinding(Object fluid, int capacity, Object validator) {
		static TankBinding of(Object tank) { return new TankBinding(tankFluid(tank), tankCapacity(tank), ForgeSnapshotAdapters.validator(tank)); }
		boolean matches(Object tank) { return tankFluid(tank) == fluid && tankCapacity(tank) == capacity && ForgeSnapshotAdapters.validator(tank) == validator; }
	}
	private static final class GraphSnapshot {
		final IdentityHashMap<Object, NonNullList<ItemStack>> bindings = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, List<ItemStack>> slots = new IdentityHashMap<>();
		final IdentityHashMap<ItemStack, ItemValue> items = new IdentityHashMap<>();
		final IdentityHashMap<Object, TankBinding> tanks = new IdentityHashMap<>();
		final IdentityHashMap<Object, Object> fluids = new IdentityHashMap<>();
		final IdentityHashMap<Object, FluidOps> fluidOps = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		GraphSnapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }
		void include(Object handler) {
			if (handler.getClass().getName().equals(FORGE_ITEM_HANDLER) || handler.getClass().getName().equals(NEO_ITEM_HANDLER)) includeItems(handler);
			else includeTank(handler);
		}
		private void includeItems(Object handler) {
			NonNullList<ItemStack> list = backing(handler); bindings.putIfAbsent(handler, list);
			if (slots.containsKey(list)) return;
			List<ItemStack> originals = List.copyOf(list); slots.put(list, originals);
			for (ItemStack original : originals) if (original != ItemStack.EMPTY) items.computeIfAbsent(original, ItemValue::of);
		}
		private void includeTank(Object tank) {
			tanks.putIfAbsent(tank, TankBinding.of(tank));
			Object original = tankFluid(tank);
			if (original == null || fluids.containsKey(original)) return;
			FluidOps ops = tankOps(tank);
			fluids.put(original, ops.copy(original)); fluidOps.put(original, ops);
		}
		void restore() {
			try {
				// Restore the actual mutable stack objects before reconnecting their containers. External aliases
				// and OTHER containers then see rollback too, instead of retaining the uncommitted count/tag.
				for (var entry : items.entrySet()) entry.getValue().restore(entry.getKey());
				for (var entry : fluids.entrySet()) fluidOps.get(entry.getKey()).restore(entry.getKey(), entry.getValue());
				for (var entry : slots.entrySet()) {
					NonNullList<ItemStack> list = entry.getKey(); List<ItemStack> originals = entry.getValue();
					while (list.size() > originals.size()) list.remove(list.size() - 1);
					while (list.size() < originals.size()) list.add(ItemStack.EMPTY);
					for (int i = 0; i < originals.size(); i++) list.set(i, originals.get(i));
				}
				for (var entry : bindings.entrySet()) {
					Field field = STACKS.get(entry.getKey().getClass());
					field.set(entry.getKey(), entry.getValue());
				}
				for (var entry : tanks.entrySet()) {
					TankBinding state = entry.getValue();
					tankRestore(entry.getKey(), state.fluid(), state.capacity(), state.validator());
				}
			} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
	}
	private static FluidOps tankOps(Object tank) {
		return tank.getClass().getName().equals(FORGE_FLUID_TANK) ? new ForgeFluids() : new NeoFluids();
	}
	private record ItemValue(int count, int popTime, PatchedDataComponentMap components) {
		static ItemValue of(ItemStack stack) {
			try { return new ItemValue(COUNT.getInt(stack), POP_TIME.getInt(stack), ((PatchedDataComponentMap) COMPONENTS.get(stack)).copy()); }
			catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
		void restore(ItemStack stack) throws IllegalAccessException {
			COUNT.setInt(stack, count); POP_TIME.setInt(stack, popTime);
			((PatchedDataComponentMap) COMPONENTS.get(stack)).restorePatch(components.asPatch());
		}
	}
	private static final class ForgeJournal extends PairedTransactions.Journal<GraphSnapshot> {
		final IdentityHashMap<Object, NonNullList<ItemStack>> items = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, Integer> sizes = new IdentityHashMap<>();
		final IdentityHashMap<Object, TankBinding> tanks = new IdentityHashMap<>();
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();
		void prepare(Object handler, TransactionContext context) {
			if (context == null) return; // no transaction: the write is final, nothing to restore
			validate();
			for (GraphSnapshot snapshot : liveSnapshots()) if (snapshot != null) snapshot.include(handler);
			if (handler.getClass().getName().equals(FORGE_ITEM_HANDLER) || handler.getClass().getName().equals(NEO_ITEM_HANDLER)) {
				NonNullList<ItemStack> list = backing(handler); items.putIfAbsent(handler, list); sizes.putIfAbsent(list, list.size());
			} else {
				tanks.putIfAbsent(handler, TankBinding.of(handler));
			}
			updateSnapshots(context);
		}
		void nativeFluidOperationFinished(Object tank) { tanks.put(tank, TankBinding.of(tank)); }
		void validate() {
			for (var entry : items.entrySet()) if (backing(entry.getKey()) != entry.getValue())
				throw new IllegalStateException("Native inventory backing changed during a transaction; abort before using its replacement");
			for (var entry : sizes.entrySet()) if (entry.getKey().size() != entry.getValue())
				throw new IllegalStateException("Native inventory size changed outside the transaction");
			for (var entry : tanks.entrySet()) if (!entry.getValue().matches(entry.getKey()))
				throw new IllegalStateException("Native tank binding/capacity/validator changed outside the transaction");
		}
		protected GraphSnapshot createSnapshot() {
			GraphSnapshot snapshot = new GraphSnapshot(notifications);
			for (var handler : items.keySet()) snapshot.include(handler);
			for (var tank : tanks.keySet()) snapshot.include(tank);
			return snapshot;
		}
		protected void revertToSnapshot(GraphSnapshot snapshot) {
			snapshot.restore(); notifications = new IdentityHashMap<>(snapshot.notifications);
			for (var handler : items.keySet()) items.put(handler, backing(handler));
			sizes.clear(); for (var list : items.values()) sizes.put(list, list.size());
			for (var tank : tanks.keySet()) tanks.put(tank, TankBinding.of(tank));
		}
		protected void onRootCommit() {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			items.clear(); sizes.clear(); tanks.clear(); notifications.clear();
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}
}
