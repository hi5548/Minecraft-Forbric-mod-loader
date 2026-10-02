package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.forbric.kernel.transform.ForgeTransferShapeAudit;

/**
 * Declared-capability energy on the pivot side of a transaction.
 *
 * <p>{@link #neo}: a transaction-aware view of a native energy store. Forge's and NeoForge 21.1's
 * {@code IEnergyStorage} have no transactions, only simulate/execute, so a write can be taken back only by
 * restoring the store's own state. That is proved for exactly one implementation per ecosystem: the ecosystem's
 * standard {@code net.minecraftforge.energy.EnergyStorage} / {@code net.neoforged.neoforge.energy.EnergyStorage},
 * whose final definition carries the ForgeTransferShapeAudit certificate, and a subclass of it only when no class
 * below it declares any of the six IEnergyStorage methods (so the standard receive/extract code is what runs, and
 * its whole state is the int {@code energy} field). Writes go through the store's own receiveEnergy/extractEnergy
 * (its capacity and maxReceive/maxExtract limits apply); one journal per thread snapshots the {@code energy} field of
 * every store it touches, restores it on abort at any nesting depth, and dirties each block entity once per root
 * commit. Any other IEnergyStorage gets no view at all and is reported once per class as
 * FORGE_HANDLER_NOT_ROLLBACK_SAFE; there is no switch that grants it one. A non-transactional write to the same
 * store while a transaction holding it is open is undone with that transaction if it aborts, as with the item and
 * fluid journals.
 *
 * <p>PORT(1.21.1): 26.2's {@code neoforge.transfer.energy.EnergyHandler} could be returned directly as NeoForge's
 * capability; in 21.1 NeoForge's capability is {@code net.neoforged.neoforge.energy.IEnergyStorage}, so this view is
 * a pivot and a declared-capability facade (ForgeLegacyFacades) presents it again. The audited native store set now
 * covers NeoForge's standard EnergyStorage too, not just Forge's — 21.1 has no other way to roll a Fabric
 * transaction back. The journal is a {@link PairedTransactions.Journal} over Fabric's callbacks (26.2's
 * {@code SnapshotJournal} is gone); semantics are unchanged.
 *
 * <p>The standard store can hold more than its capacity (or less than nothing): its deserializeNBT sets the field
 * unclamped, so a save made before a config lowered the capacity loads that way. Its own receiveEnergy then answers
 * a NEGATIVE amount and lowers the field. That is the ecosystem's code on a state it allows, not a broken provider,
 * so the view refuses the operation instead of throwing into the consumer's tick: the field is put back as it was
 * and nothing moves. The store still gives its energy away normally, down into its bounds.
 *
 * <p>Each ecosystem's own {@code EmptyEnergyStorage} (that exact class) holds nothing and accepts nothing. It is
 * the owner's answer "no energy here", not a store to audit: it becomes an empty view that answers for the owner,
 * and no finding.
 */
public final class ForgeEnergyAdapters {
	private ForgeEnergyAdapters() { }
	/** Every IEnergyStorage method. A subclass declaring any of them no longer runs the audited code. */
	private static final Set<String> CRITICAL = Set.of("receiveEnergy", "extractEnergy", "getEnergyStored", "getMaxEnergyStored", "canReceive", "canExtract");
	private static final ClassValue<Field> ENERGY = new ClassValue<>() {
		protected Field computeValue(Class<?> type) {
			for (Class<?> at = type; at != null; at = at.getSuperclass()) {
				try { Field field = at.getDeclaredField("energy"); field.setAccessible(true); return field; }
				catch (NoSuchFieldException absent) { }
			}
			return null;
		}
	};
	private static final ThreadLocal<Journal> CURRENT = new ThreadLocal<>();
	private static final ClassValue<Boolean> CERTIFIED = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			try { type.getDeclaredMethod(ForgeTransferShapeAudit.MARKER); return true; }
			catch (NoSuchMethodException | LinkageError unverified) { return false; }
		}
	};
	private static final ClassValue<Boolean> STANDARD_SHAPE = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			Class<?> base = standardBase(type);
			if (base == null) return false;
			try {
				for (Class<?> at = type; at != base; at = at.getSuperclass())
					for (Method method : at.getDeclaredMethods()) if (CRITICAL.contains(method.getName())) return false;
				return true;
			} catch (LinkageError unreadable) { return false; }
		}
	};
	private static final String FORGE_STORE = "net.minecraftforge.energy.EnergyStorage";
	private static final String NEO_STORE = "net.neoforged.neoforge.energy.EnergyStorage";
	private static final String FORGE_EMPTY = "net.minecraftforge.energy.EmptyEnergyStorage";
	private static final String NEO_EMPTY = "net.neoforged.neoforge.energy.EmptyEnergyStorage";

	private static Class<?> standardBase(Class<?> type) {
		for (Class<?> at = type; at != null; at = at.getSuperclass()) {
			String name = at.getName();
			if (name.equals(FORGE_STORE) || name.equals(NEO_STORE)) return at;
		}
		return null;
	}
	private static int read(Object storage) {
		try { return ENERGY.get(storage.getClass()).getInt(storage); }
		catch (IllegalAccessException | NullPointerException impossible) { throw new IllegalStateException(impossible); }
	}
	private static void write(Object storage, int value) {
		try { ENERGY.get(storage.getClass()).setInt(storage, value); }
		catch (IllegalAccessException | NullPointerException impossible) { throw new IllegalStateException(impossible); }
	}
	/** The native operations the pivot view needs; one per ecosystem interface (they share the int energy field). */
	private interface Ops {
		boolean canReceive(Object storage);
		boolean canExtract(Object storage);
		int receive(Object storage, int maximum);
		int extract(Object storage, int maximum);
	}
	private static final class ForgeOps implements Ops {
		public boolean canReceive(Object storage) { return ((net.minecraftforge.energy.IEnergyStorage) storage).canReceive(); }
		public boolean canExtract(Object storage) { return ((net.minecraftforge.energy.IEnergyStorage) storage).canExtract(); }
		public int receive(Object storage, int maximum) { return ((net.minecraftforge.energy.IEnergyStorage) storage).receiveEnergy(maximum, false); }
		public int extract(Object storage, int maximum) { return ((net.minecraftforge.energy.IEnergyStorage) storage).extractEnergy(maximum, false); }
	}
	private static final class NeoOps implements Ops {
		public boolean canReceive(Object storage) { return ((net.neoforged.neoforge.energy.IEnergyStorage) storage).canReceive(); }
		public boolean canExtract(Object storage) { return ((net.neoforged.neoforge.energy.IEnergyStorage) storage).canExtract(); }
		public int receive(Object storage, int maximum) { return ((net.neoforged.neoforge.energy.IEnergyStorage) storage).receiveEnergy(maximum, false); }
		public int extract(Object storage, int maximum) { return ((net.neoforged.neoforge.energy.IEnergyStorage) storage).extractEnergy(maximum, false); }
	}

	/** Whether {@code storage} may be written transactionally: a certified standard class (Forge or NeoForge), or its exact shape. */
	public static boolean supports(Object storage) {
		if (storage == null) return false;
		Class<?> base = standardBase(storage.getClass());
		return base != null && STANDARD_SHAPE.get(storage.getClass()) && certified(base);
	}
	private static boolean certified(Class<?> base) {
		boolean approved = CERTIFIED.get(base);
		if (!approved) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", base.getName(),
				ForgeTransferShapeAudit.declined(base.getName()));
		return approved;
	}
	private static void refused(Object storage) {
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", storage,
				"The energy provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
	}
	private static boolean empty(Object storage) {
		String name = storage.getClass().getName();
		return name.equals(FORGE_EMPTY) || name.equals(NEO_EMPTY);
	}
	private static Ops ops(Object storage) {
		return standardBase(storage.getClass()).getName().equals(NEO_STORE) ? new NeoOps() : new ForgeOps();
	}

	public static EnergyHandler neo(net.minecraftforge.energy.IEnergyStorage storage) { return neo(storage, storage, () -> { }); }
	public static EnergyHandler neo(net.neoforged.neoforge.energy.IEnergyStorage storage) { return neo(storage, storage, () -> { }); }
	/** A pivot view of a native store. {@code changed} runs once per root commit that moved energy (or immediately
	 * when there is no transaction). A store that is not audited still gets a view — reads and direction flags work,
	 * and a write outside any transaction is final — but a write a Fabric transaction would have to roll back is
	 * refused and reported. */
	public static EnergyHandler neo(net.minecraftforge.energy.IEnergyStorage storage, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		if (storage == null) return null;
		if (empty(storage)) return Empty.INSTANCE;
		return new View(storage, new ForgeOps(), owner, changed, reversible(storage));
	}
	/** PORT(1.21.1): the NeoForge standard store gets the same audited view. */
	public static EnergyHandler neo(net.neoforged.neoforge.energy.IEnergyStorage storage, Object owner, Runnable changed) {
		java.util.Objects.requireNonNull(changed);
		if (storage == null) return null;
		if (empty(storage)) return Empty.INSTANCE;
		return new View(storage, new NeoOps(), owner, changed, reversible(storage));
	}
	private static boolean reversible(Object storage) {
		boolean reversible = supports(storage);
		if (!reversible) refused(storage);
		return reversible;
	}

	private static Journal journal() {
		Journal journal = CURRENT.get();
		if (journal == null || !Transaction.isOpen()) { journal = new Journal(); CURRENT.set(journal); }
		return journal;
	}

	/** Identity semantics on purpose: two equal-valued snapshots at different depths are different. */
	private static final class Snapshot {
		final IdentityHashMap<Object, Integer> values = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		Snapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }
	}
	private static final class Journal extends PairedTransactions.Journal<Snapshot> {
		final Set<Object> tracked = Collections.newSetFromMap(new IdentityHashMap<>());
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();
		void prepare(Object storage, TransactionContext context) {
			if (context == null) return; // no transaction: the write is final, nothing to restore
			// A store first touched at depth n must still be restored if a shallower scope aborts: every live snapshot
			// learns its value now, before this operation changes it.
			for (Snapshot snapshot : liveSnapshots()) if (snapshot != null) snapshot.values.putIfAbsent(storage, read(storage));
			tracked.add(storage);
			updateSnapshots(context);
		}
		protected Snapshot createSnapshot() {
			Snapshot snapshot = new Snapshot(notifications);
			for (Object storage : tracked) snapshot.values.put(storage, read(storage));
			return snapshot;
		}
		protected void revertToSnapshot(Snapshot snapshot) {
			for (var entry : snapshot.values.entrySet()) write(entry.getKey(), entry.getValue());
			notifications = new IdentityHashMap<>(snapshot.notifications);
		}
		protected void onRootCommit() {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			tracked.clear(); notifications.clear();
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}

	private record View(Object storage, Ops ops, Object owner, Runnable changed, boolean reversible) implements EnergyHandler, EnergyAbilities {
		// Reads never move energy; a malformed negative native amount reads as empty rather than failing a render.
		public long getAmountAsLong() { return Math.max(0, read(storage)); }
		public long getCapacityAsLong() { return Math.max(0, capacity()); }
		private int capacity() {
			return ops instanceof ForgeOps
					? ((net.minecraftforge.energy.IEnergyStorage) storage).getMaxEnergyStored()
					: ((net.neoforged.neoforge.energy.IEnergyStorage) storage).getMaxEnergyStored();
		}
		public int insert(int maximum, TransactionContext transaction) { return move(maximum, transaction, true); }
		public int extract(int maximum, TransactionContext transaction) { return move(maximum, transaction, false); }
		private int move(int maximum, TransactionContext transaction, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			if (transaction != null && !reversible) return 0; // reported once per class when the view was built
			Journal journal = transaction == null ? null : journal();
			if (journal != null) journal.prepare(storage, transaction);
			// The store's own code, with its own capacity and receive/extract limits.
			int before = read(storage);
			int moved = insert ? ops.receive(storage, maximum) : ops.extract(storage, maximum);
			if (moved < 0 || moved > maximum) {
				// Only a store outside its own bounds answers this (see the class comment); the certified code cannot
				// otherwise. Nothing moved: the field is exactly what it was, with or without a later abort.
				write(storage, before);
				return 0;
			}
			if (moved > 0) { if (journal == null) changed.run(); else journal.notifications.put(owner, changed); }
			return moved;
		}
		public boolean canInsert() { return ops.canReceive(storage); }
		public boolean canExtract() { return ops.canExtract(storage); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	/** The view of a native EmptyEnergyStorage: nothing stored, nothing accepted, no direction. Never touches the store. */
	private enum Empty implements EnergyHandler, EnergyAbilities {
		INSTANCE;
		public long getAmountAsLong() { return 0; }
		public long getCapacityAsLong() { return 0; }
		public int insert(int maximum, TransactionContext transaction) { return nothing(maximum); }
		public int extract(int maximum, TransactionContext transaction) { return nothing(maximum); }
		private static int nothing(int maximum) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			return 0;
		}
		public boolean canInsert() { return false; }
		public boolean canExtract() { return false; }
	}
}
