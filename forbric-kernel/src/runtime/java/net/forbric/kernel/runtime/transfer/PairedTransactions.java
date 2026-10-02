package net.forbric.kernel.runtime.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * The bridge's transaction layer, over Fabric's engine.
 *
 * <p>PORT(1.21.1): 26.2's {@code net.neoforged.neoforge.transfer.transaction.{Transaction, TransactionManager,
 * SnapshotJournal}} does not exist in NeoForge 21.1, so there is nothing left to <em>pair</em>. Fabric's
 * {@link TransactionContext} is the only real nested, revertible transaction in the pack; native 21.1 handlers
 * (Forge and NeoForge {@code IItemHandler}/{@code IFluidHandler}/{@code IEnergyStorage}) have only simulate/execute
 * and no snapshot concept at all. This class therefore supplies the two pieces the removed engine used to:
 *
 * <ul>
 *   <li>{@link #scope()} — the bridge's own place to run an operation that must be taken back: a nested Fabric
 *       transaction under an already-open one, an outer Fabric transaction otherwise. The old
 *       {@code ForgeLegacyFacades.scope()} and {@code PairedTransactions.neo/fabric} both reduce to this.</li>
 *   <li>{@link Journal} — a per-thread, nesting-aware participant for exactly the audited native handlers that can
 *       be restored (see ForgeSnapshotAdapters/ForgeEnergyAdapters). It is Fabric's
 *       {@code SnapshotParticipant} contract re-stated here rather than extended: the bridge needs every live
 *       snapshot to learn a handler the moment it is first written, which {@code SnapshotParticipant}'s private
 *       snapshot list cannot express ({@code liveSnapshots()}).</li>
 * </ul>
 *
 * <p>The 26.2 transform hooks ({@code beforeOpen}/{@code beforeClose}/{@code afterClose}/{@code fabricFinal}/
 * {@code neoFinal}) and their startup {@code checkHooks} are gone with the NeoForge transaction manager. Nothing in
 * the runtime calls them; the boot-side {@code TransferTransactionHooks} transformer must be retired with them.
 */
public final class PairedTransactions {
	private PairedTransactions() { }

	/** The currently-open Fabric transaction, or null. */
	static TransactionContext current() {
		return Transaction.isOpen() ? Transaction.getCurrentUnsafe() : null;
	}

	/**
	 * A scope that can be closed without committing: nested under the current Fabric transaction when one is open
	 * (its owner keeps the final say), otherwise a fresh outer transaction. Callers abort it for simulate, commit it
	 * for execute.
	 */
	static Transaction scope() {
		return Transaction.isOpen() ? Transaction.getCurrentUnsafe().openNested() : Transaction.openOuter();
	}

	/**
	 * A nesting-aware journal participant over Fabric's transaction callbacks.
	 *
	 * <p>Semantics match 26.2's {@code SnapshotJournal}: one snapshot per currently-open depth, a rollback publishes
	 * a deeper snapshot to its parent for a longer-lived abort, and final notifications run only after the outer
	 * transaction has closed. Subclasses keep their own thread-local instance and reset it when no transaction is
	 * open, exactly as the removed journals did.
	 */
	abstract static class Journal<T> implements TransactionContext.CloseCallback, TransactionContext.OuterCloseCallback {
		private final List<T> snapshots = new ArrayList<>();

		/** A new snapshot of every store this journal has been told about so far. */
		protected abstract T createSnapshot();
		/** Restore every store to the state in {@code snapshot}. */
		protected abstract void revertToSnapshot(T snapshot);
		/** The snapshot's scope closed without being restored; drop its resources. */
		protected void releaseSnapshot(T snapshot) { }
		/** The outer transaction committed; run the pending final notifications once. */
		protected void onRootCommit() { }

		/** Every live snapshot, outermost first, so a store first written at depth n is still restored by a shallower abort. */
		protected final List<T> liveSnapshots() { return snapshots; }

		final void updateSnapshots(TransactionContext context) {
			int depth = context.nestingDepth();
			while (snapshots.size() <= depth) snapshots.add(null);
			if (snapshots.get(depth) != null) return;
			T snapshot = Objects.requireNonNull(createSnapshot(), "Journal snapshot may not be null");
			snapshots.set(depth, snapshot);
			context.addCloseCallback(this);
		}

		public final void onClose(TransactionContext context, TransactionContext.Result result) {
			int depth = context.nestingDepth();
			T snapshot = snapshots.set(depth, null);
			if (snapshot == null) return;
			if (result.wasAborted()) {
				revertToSnapshot(snapshot);
				releaseSnapshot(snapshot);
				return;
			}
			if (depth > 0) {
				if (snapshots.get(depth - 1) == null) {
					snapshots.set(depth - 1, snapshot);
					context.getOpenTransaction(depth - 1).addCloseCallback(this);
				} else {
					releaseSnapshot(snapshot);
				}
			} else {
				releaseSnapshot(snapshot);
				context.addOuterCloseCallback(this);
			}
		}

		public final void afterOuterClose(TransactionContext.Result result) {
			if (!result.wasAborted()) onRootCommit();
		}
	}
}
