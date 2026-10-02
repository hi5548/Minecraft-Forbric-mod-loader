/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilityProviderImpl;
import net.minecraftforge.fml.ModList;

/**
 * The composed MinecraftForge capability provider for the three root types the merge put under NeoForge's
 * {@code AttachmentHolder} instead of Forge's {@code CapabilityProvider}.
 *
 * <p>Java has no multiple inheritance, so no merge can give {@code Entity} both superclasses; composition is the
 * only correct shape, and it is Forge's own — {@code LevelChunk} carries a {@code CapabilityProvider$AsField}
 * exactly like this (verified on the 1.21.1 merged base, where that field survived the merge; on {@code Entity},
 * {@code BlockEntity} and {@code Level} it did not, which is why they are composed here). The transformer adds a
 * lazily-created field of that type to each root plus straight-line delegates; this class supplies the three
 * {@code AsField} subclasses and the null-checking helpers, so that every synthesised method is branch-free — the
 * frame recomputer never touches these classes.
 *
 * <p>Everything else — gathering, dispatching, LazyOptional invalidation, NBT (de)serialisation, lazy replay —
 * is Forge's {@code CapabilityProvider}/{@code CapabilityDispatcher} code, in Forge's own lazy mode: the
 * {@code AttachCapabilitiesEvent} fires on the first query or deserialise, not in the constructor.
 * {@code -Dforbric.forgeCapabilities=off} means nothing references this class.
 *
 * <h2>PORT(1.21.1): who fires {@code AttachCapabilitiesEvent}, and with what</h2>
 *
 * <p>26.2's {@code CapabilityProvider.AsField} had two overridable hooks —
 * {@code fireAttachCapabilitiesEvent(owner)} and {@code shouldFireAttachCapabilitiesEvent()} — and the per-type
 * events were posted on per-type buses ({@code AttachCapabilitiesEvent.Entities.BUS}). Neither exists on 1.21.1.
 * There, {@code AsField(Class&lt;B&gt; baseClass, B owner)} stores the base class and
 * {@code ForgeEventFactory.gatherCapabilities(baseClass, provider, parent)} posts ONE generic
 * {@code new AttachCapabilitiesEvent&lt;T&gt;(baseClass, provider)} through {@code MinecraftForge.EVENT_BUS}
 * (verified with {@code javap -c} on {@code forge-runtime.jar} 52.1.16: the private
 * {@code gatherCapabilities(AttachCapabilitiesEvent, ICapabilityProvider)} starts with
 * {@code post(Event)}). So the subclasses here only supply the base class — {@code Entity.class},
 * {@code BlockEntity.class}, {@code Level.class}, exactly what Forge's own patched classes pass — and there is no
 * listener-presence short-circuit to write: Forge 52's {@code IEventBus} has no {@code hasListeners}.
 *
 * <p>{@code AsField.initInternal()} still means "gather now if not lazy, else on first use", which is what
 * Forge's own field-holding classes do after constructing the field, so {@link #create} is unchanged.
 *
 * <h2>PORT(1.21.1): ForgeCaps is a CompoundTag again</h2>
 *
 * <p>26.2 serialised through {@code ValueInput}/{@code ValueOutput} and read the lookup off the input. 1.21.1 has
 * neither class: Forge's own patched {@code BlockEntity} writes
 * {@code tag.put("ForgeCaps", serializeCaps(registries))} in {@code saveAdditional(CompoundTag, HolderLookup.Provider)}
 * and reads {@code if (tag.contains("ForgeCaps")) deserializeCaps(registries, tag.getCompound("ForgeCaps"))} in
 * {@code loadAdditional(CompoundTag, HolderLookup.Provider)} — verified by decompiling
 * {@code patched-mc-forge-1.21.1.jar}. The save helpers here write that same key with the same tag type, and the
 * lookup comes from the owner, since a {@code CompoundTag} carries none.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public final class KernelForgeCapabilities {
	public static final String PROPERTY = "forbric.forgeCapabilities";

	private KernelForgeCapabilities() {
	}

	/** Exposes the one protected-final member a delegate on the owner needs. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	abstract static class Composed extends CapabilityProvider.AsField {
		Composed(Class<?> base, Object owner) {
			// AsField(Class<B>, B, boolean isLazy): Forge's own Entity/BlockEntity/LevelChunk fields are created
			// with the type's own class, and the third argument is Forge's lazy mode, which 26.2's
			// `super((ICapabilityProviderImpl) owner, true)` also asked for.
			super(base, (ICapabilityProviderImpl) owner, true);
		}

		public CapabilityDispatcher dispatcher() {
			return getCapabilities();
		}
	}

	static final class Entities extends Composed {
		Entities(Object owner) {
			super(Entity.class, owner);
		}
	}

	static final class BlockEntities extends Composed {
		BlockEntities(Object owner) {
			super(BlockEntity.class, owner);
		}
	}

	static final class Levels extends Composed {
		Levels(Object owner) {
			super(Level.class, owner);
		}
	}

	// ---- the accessor's one branch, held here so the synthesised forbric$caps() has none

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField entity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Entities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField blockEntity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new BlockEntities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField level(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Levels(owner));
	}

	@SuppressWarnings("rawtypes")
	private static CapabilityProvider.AsField create(CapabilityProvider.AsField field) {
		// Forge's own LevelChunk constructor does exactly this after newing its AsField; in lazy mode it defers the
		// gather until the first query, so the AttachCapabilitiesEvent fires then.
		field.initInternal();
		return field;
	}

	// ---- read-without-creating helpers: invalidating or saving a never-gathered provider is a no-op on Forge too

	@SuppressWarnings("rawtypes")
	public static void invalidate(CapabilityProvider.AsField field) {
		if (field != null) field.invalidateCaps();
	}

	@SuppressWarnings("rawtypes")
	public static void revive(CapabilityProvider.AsField field) {
		if (field != null) field.reviveCaps();
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityDispatcher dispatcher(CapabilityProvider.AsField field) {
		return field == null ? null : ((Composed) field).dispatcher();
	}

	@SuppressWarnings("rawtypes")
	public static CompoundTag serialize(CapabilityProvider.AsField field, HolderLookup.Provider lookup) {
		return field == null ? null : field.serializeInternal(lookup);
	}

	/** Forge's own save shape: {@code tag.put("ForgeCaps", serializeCaps(registries))}. */
	@SuppressWarnings("rawtypes")
	public static void saveBlockEntity(CapabilityProvider.AsField field, CompoundTag output, Object owner) {
		if (field == null) return;
		try {
			CompoundTag tag = field.serializeInternal(lookupOf(owner));
			if (tag != null) output.put("ForgeCaps", tag);
		} catch (Throwable t) {
			reportOnce("saving a block entity's MinecraftForge capabilities", t);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void saveEntity(CapabilityProvider.AsField field, CompoundTag output, Object owner) {
		if (field == null) return;
		try {
			CompoundTag tag = field.serializeInternal(((Entity) owner).registryAccess());
			if (tag != null) output.put("ForgeCaps", tag);
		} catch (Throwable t) {
			reportOnce("saving an entity's MinecraftForge capabilities", t);
		}
	}

	/**
	 * Forge's own load shape ({@code if (tag.contains("ForgeCaps")) deserializeCaps(registries, …)}); in lazy mode
	 * the tag is parked and replayed on the first getCapabilities.
	 */
	@SuppressWarnings("rawtypes")
	public static void load(CapabilityProvider.AsField field, CompoundTag input, Object owner) {
		if (field == null) return;
		try {
			if (input.contains("ForgeCaps")) field.deserializeInternal(lookupOf(owner), input.getCompound("ForgeCaps"));
		} catch (Throwable t) {
			reportOnce("loading MinecraftForge capabilities from ForgeCaps", t);
		}
	}

	/** The merged NeoForge saveAdditional's own choice for a level-less block entity: an empty registry access. */
	private static HolderLookup.Provider lookupOf(Object owner) {
		Level level = owner instanceof BlockEntity be ? be.getLevel() : null;
		return level != null ? level.registryAccess() : RegistryAccess.EMPTY;
	}

	private static volatile boolean reported;

	private static void reportOnce(String what, Throwable t) {
		if (reported) return;
		reported = true;
		ForbricLog.warn("[Forbric/Capabilities] " + what + " threw — that data is skipped; later failures of this "
				+ "kind are not repeated", Reflect.unwrap(t));
	}

	// ---- registration stage

	/**
	 * Forge's own {@code INJECT_CAPABILITIES} stage: {@code CapabilityManager.injectCapabilities(ModList)} scans
	 * mod scan data for {@code @AutoRegisterCapability} and marks each as registered. Advisory on this base
	 * ({@code isRegistered()} is read only by Forge's own manager), so the count is returned rather than acted on.
	 *
	 * <p>The count was structurally zero while the seeded {@code ModFile}s carried an EMPTY scan data — this read
	 * the same nothing SuperMartijn642's Core Lib did. It is a real number now; see {@code ModFileScanner.scanForge}
	 * and {@code -Dforbric.forgeScanData=off}.
	 *
	 * <p>PORT(1.21.1): Forge 52's entry point takes the {@code ModList} and the scan data is reached through
	 * {@code ModList.get()} — both are instance-shaped now, where 26.2's were
	 * {@code CapabilityManager.injectCapabilities()} and {@code ModList.getAllScanData()}. Verified with javap
	 * against forge-runtime.jar 52.1.16.
	 */
	public static int injectCapabilities() {
		CapabilityManager.injectCapabilities(ModList.get());
		int annotated = 0;
		try {
			for (net.minecraftforge.forgespi.language.ModFileScanData scan : ModList.get().getAllScanData()) {
				for (net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData annotation : scan.getAnnotations()) {
					if ("Lnet/minecraftforge/common/capabilities/AutoRegisterCapability;".equals(annotation.annotationType().getDescriptor())) annotated++;
				}
			}
		} catch (Throwable t) {
			return -1;
		}
		return annotated;
	}
}
