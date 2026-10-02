package net.forbric.kernel.runtime.transfer;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorage;

/**
 * Team Reborn Energy (the Fabric ecosystem's energy API) and the bridge pivot {@link EnergyHandler}: each operation
 * runs in a real nested transaction, opened under the consumer's current Fabric transaction, and commits into it.
 * Nothing moves outside the consumer's scope, a nested abort restores the provider through its own journal, and
 * final notifications wait for both roots.
 *
 * <p>PORT(1.21.1): 26.2 paired two native transaction engines here. In 21.1 Team Reborn Energy already speaks
 * Fabric's {@link TransactionContext} (that is what its {@code insert}/{@code extract} take), and the pivot's
 * native-backed views journal into the same Fabric transaction, so both directions just carry the Fabric context
 * through and open their nested scope with {@link Transaction#openNested(TransactionContext)}. The old
 * {@code PairedTransactions.fabric/neo} pairing is gone with the NeoForge transaction manager.
 *
 * <p>Units are 1:1 (see EnergyUnits). A Reborn request larger than an int is asked of the pivot as
 * Integer.MAX_VALUE; the rest is never moved and stays in its source. A provider answer outside [0, request] is
 * rejected before the nested scope commits, so it is rolled back.
 *
 * <p>Only this class, RebornEnergyBridge and nothing else in the runtime names a Reborn type. They are loaded only
 * when KernelTransferInterop found Team Reborn Energy installed; without it no Reborn class is ever requested.
 */
public final class RebornEnergyAdapters {
	private RebornEnergyAdapters() { }

	/** A pivot view of a Reborn store; our own Reborn view of a pivot handler unwraps to that handler. */
	public static EnergyHandler neo(EnergyStorage storage) {
		Objects.requireNonNull(storage);
		if (storage instanceof FromNeo own) return own.handler();
		return new FromFabric(storage);
	}
	/** A Reborn view of a pivot handler; our own pivot view of a Reborn store unwraps to that store. */
	public static EnergyStorage fabric(EnergyHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof FromFabric own) return own.storage();
		return new FromNeo(handler);
	}

	/** A Reborn store resolved again for every operation, under LiveTransferEndpoints' rules. */
	public static EnergyStorage live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new Live(lookup, valid, generation);
	}

	private record FromFabric(EnergyStorage storage) implements EnergyHandler, EnergyAbilities {
		public long getAmountAsLong() { return EnergyUnits.reported(storage.getAmount()); }
		public long getCapacityAsLong() { return EnergyUnits.reported(storage.getCapacity()); }
		public int insert(int maximum, TransactionContext context) { return move(maximum, context, true); }
		public int extract(int maximum, TransactionContext context) { return move(maximum, context, false); }
		private int move(int maximum, TransactionContext context, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			try (Transaction nested = Transaction.openNested(context)) {
				long moved = EnergyUnits.moved(insert ? storage.insert(maximum, nested) : storage.extract(maximum, nested), maximum);
				nested.commit();
				return (int) moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, storage);
				TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public boolean canInsert() { return storage.supportsInsertion(); }
		public boolean canExtract() { return storage.supportsExtraction(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record FromNeo(EnergyHandler handler) implements EnergyStorage {
		public boolean supportsInsertion() { return EnergyAbilities.fabricCanInsert(handler); }
		public boolean supportsExtraction() { return EnergyAbilities.fabricCanExtract(handler); }
		public long insert(long maximum, TransactionContext context) { return move(maximum, context, true); }
		public long extract(long maximum, TransactionContext context) { return move(maximum, context, false); }
		private long move(long maximum, TransactionContext context, boolean insert) {
			int request = EnergyUnits.request(maximum);
			if (request == 0) return 0;
			try {
				long moved = EnergyUnits.moved(insert ? handler.insert(request, context) : handler.extract(request, context), request);
				return moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public long getAmount() { return EnergyUnits.reported(handler.getAmountAsLong()); }
		public long getCapacity() { return EnergyUnits.reported(handler.getCapacityAsLong()); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record Live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) implements EnergyStorage {
		private EnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		public boolean supportsInsertion() { var s = current(); return s != null && s.supportsInsertion(); }
		public boolean supportsExtraction() { var s = current(); return s != null && s.supportsExtraction(); }
		public long insert(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.insert(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long extract(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.extract(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long getAmount() { var s = current(); return s == null ? 0 : s.getAmount(); }
		public long getCapacity() { var s = current(); return s == null ? 0 : s.getCapacity(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}
