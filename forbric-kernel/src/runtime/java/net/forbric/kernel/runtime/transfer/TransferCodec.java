package net.forbric.kernel.runtime.transfer;

/**
 * Resource identity conversion. A null conversion means unrepresentable, never "discard its metadata".
 *
 * <p>PORT(1.21.1): {@code N} is the bridge's own {@link Resource} (see TransferApi), not 26.2's
 * {@code net.neoforged.neoforge.transfer.resource.Resource}: the NeoForge 21.1 item/fluid capability types carry no
 * count-less resource identity of their own, so the codec is what maps a Fabric {@code ItemVariant}/{@code
 * FluidVariant} onto the bridge's identity and back.
 */
public interface TransferCodec<F, N extends Resource> {
	N toNeo(F resource);
	F toFabric(N resource);
	boolean isFabricBlank(F resource);
	/** Fabric units per one native NeoForge unit: one for items, 81 for fluids. */
	long fabricUnits();
}
