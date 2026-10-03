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
import net.minecraft.core.Registry;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Registers a foreign ecosystem's {@link EntityDataSerializer} where NeoForge's networking can see it.
 *
 * <h2>What the merged base does to the vanilla entry point</h2>
 *
 * <p>NeoForge patches {@code EntityDataSerializers.registerSerializer} into a <em>caller-identity guard</em>:
 * {@code StackWalker.getCallerClass()} must equal {@code EntityDataSerializers.class} or it logs and throws
 * {@code UnsupportedOperationException("Modded EntityDataSerializers must be registered to
 * NeoForgeRegistries.ENTITY_DATA_SERIALIZERS instead to prevent ID mismatches between client and server!")}.
 * The guard is right about the destination and wrong about the audience: a Fabric-ecosystem mod is written
 * against the vanilla call and has no way to know that registry exists. Cobblemon's Fabric entrypoint dies on
 * it in {@code preInitialize}, before any of its registrations run — which is the single cause behind 0/43
 * custom-registry lines and the 86 {@code worldgen/processor_list} parse failures that follow (its
 * {@code STRUCTURE_PROCESSOR} entries never register, so {@code cobblemon:height_range} is unknown when the
 * datapack resolves it).
 *
 * <p>{@code ForbricMergedBaseCompatTransformer.routeForeignEntityDataSerializersToNeoForge} replaces exactly
 * that throw block with a call here, leaving the guard's own class's {@code SERIALIZERS.add} path (vanilla's
 * thirty-odd defaults) untouched. Nothing about the guest is rewritten: the same call site now lands in the
 * registry NeoForge wanted it to land in.
 *
 * <h2>Why the ID is deterministic, which is the whole risk</h2>
 *
 * <p>The vanilla overload carries <em>no name</em>; NeoForge's registry keys every entry by
 * {@link ResourceLocation} and assigns the wire id from <b>insertion order</b>
 * ({@code CommonHooks.getSerializerId} returns {@code NeoForgeRegistries.ENTITY_DATA_SERIALIZERS.getId(value) + 256}
 * for anything the vanilla map does not hold). So the id is only deterministic if the NAME is. A synthetic
 * counter would be deterministic only as long as registration order never changes; this derives the name from
 * the serializer's own class instead, so the same mod set produces the same name and therefore the same id on
 * both sides, whatever order the guests run in. The namespace is {@code forbric} because the guest ships no
 * NeoForge name for it — the Fabric jar only ever had the anonymous vanilla overload.
 *
 * <h2>What this costs, recorded</h2>
 *
 * <p>A guest's serializer now lives in NeoForge's synced id space (wire id 256 + registry id), so a stock
 * Fabric or vanilla client cannot resolve it; a Forbric client gets it from the registry sync by name. That is
 * the same trade every cross-ecosystem registration on this base makes, and it is what removes the mismatch the
 * guard was guarding against. The alternative — standing the guard down and letting the guest use the vanilla
 * {@code CrudeIncrementalIntIdentityHashBiMap} — is rejected: that map is capped at 256 and is not synced, so
 * one extra serializer on the client shifts every id after it.
 */
public final class KernelEntityDataSerializers {
	/**
	 * The namespace the guest's nameless registration is given. Deliberately not the guest's own mod id: deriving
	 * that needs the owning container, and the property that matters here is determinism, not prettiness.
	 */
	private static final String NAMESPACE = "forbric";
	private static final String PREFIX = "guest_serializer/";

	private KernelEntityDataSerializers() {
	}

	/** The seam the repaired {@code registerSerializer} calls for every caller that is not the class itself. */
	public static void register(EntityDataSerializer<?> serializer) {
		if (serializer == null) return;
		Registry<EntityDataSerializer<?>> registry = NeoForgeRegistries.ENTITY_DATA_SERIALIZERS;
		if (registry.getId(serializer) >= 0) return;        // already routed: a guest may register twice

		ResourceLocation name = nameFor(serializer, registry);
		register(registry, name, serializer);
		ForbricLog.info("[Forbric/DataSerializers] %s registered as %s in NeoForge's synced registry (wire id %d) "
						+ "— a foreign ecosystem cannot name NeoForge's registry, and the merged base's vanilla "
						+ "entry point refuses every caller but itself",
				serializer.getClass().getName(), name, registry.getId(serializer) + 256);
	}

	/**
	 * A name that is a pure function of the serializer's class — see the class doc. A second instance of the same
	 * class (or a shaded namesake from another jar) takes the first free {@code _N} suffix; that path is only
	 * reached when the registry already holds a different object under the plain name, so it stays deterministic
	 * for the ordinary one-class-one-instance case every real registration is.
	 */
	private static ResourceLocation nameFor(EntityDataSerializer<?> serializer,
			Registry<EntityDataSerializer<?>> registry) {
		String path = PREFIX + sanitised(serializer.getClass().getName());
		ResourceLocation name = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
		for (int suffix = 2; registry.containsKey(name) && registry.get(name) != serializer; suffix++) {
			name = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path + "_" + suffix);
		}
		return name;
	}

	/** Lower-cases the binary name and maps everything a {@link ResourceLocation} path may not carry to {@code _}. */
	private static String sanitised(String binaryName) {
		StringBuilder out = new StringBuilder(binaryName.length());
		for (int i = 0; i < binaryName.length(); i++) {
			char c = binaryName.charAt(i);
			if (c >= 'A' && c <= 'Z') c += 32;
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '/' || c == '_' || c == '-') {
				out.append(c);
			} else {
				out.append('_');
			}
		}
		return out.toString();
	}

	/**
	 * Raw on purpose: {@code Registry.register(Registry<V>, ResourceLocation, T)} cannot express
	 * {@code Registry<EntityDataSerializer<?>>} without a capture that no value satisfies, and this is the
	 * destination NeoForge's own guard names, so it is typed once here rather than reflected.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void register(Registry registry, ResourceLocation name, EntityDataSerializer<?> serializer) {
		Registry.register(registry, name, serializer);
	}
}
