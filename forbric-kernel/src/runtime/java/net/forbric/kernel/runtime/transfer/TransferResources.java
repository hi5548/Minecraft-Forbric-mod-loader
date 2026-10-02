package net.forbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

/**
 * The item and fluid identity mappings between Fabric's {@code ItemVariant}/{@code FluidVariant} and the bridge's
 * own {@link ItemResource}/{@link FluidResource}.
 *
 * <p>PORT(1.21.1): 26.2's exact identity mappings against {@code neoforge.transfer.item.ItemResource} /
 * {@code fluid.FluidResource} are gone with those classes. Both APIs still share the game's registry objects and
 * DataComponentPatch, and this version's Fabric {@code TransferVariant} exposes the patch as
 * {@code getComponents()} (26.2's was {@code getComponentsPatch()}); the mapping stays an exact identity, one
 * Fabric item per native item and 81 Fabric fluid units per native millibucket.
 */
public final class TransferResources {
	private TransferResources() { }
	public static final TransferCodec<ItemVariant, ItemResource> ITEMS = new TransferCodec<>() {
		public ItemResource toNeo(ItemVariant value) { return value.isBlank() ? ItemResource.EMPTY : ItemResource.of(value.getItem(), value.getComponents()); }
		public ItemVariant toFabric(ItemResource value) { return value.isEmpty() ? ItemVariant.blank() : ItemVariant.of(value.getItem(), value.componentsPatch()); }
		public boolean isFabricBlank(ItemVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 1; }
	};
	public static final TransferCodec<FluidVariant, FluidResource> FLUIDS = new TransferCodec<>() {
		public FluidResource toNeo(FluidVariant value) { return value.isBlank() ? FluidResource.EMPTY : FluidResource.of(value.getFluid(), value.getComponents()); }
		public FluidVariant toFabric(FluidResource value) { return value.isEmpty() ? FluidVariant.blank() : FluidVariant.of(value.getFluid(), value.componentsPatch()); }
		public boolean isFabricBlank(FluidVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 81; }
	};
}
