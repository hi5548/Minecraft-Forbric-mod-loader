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

package net.forbric.kernel.fabric;

import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.api.MappingResolver;

import net.forbric.kernel.mapping.FabricGuestMappings;
import net.forbric.kernel.mapping.ForbricMappings;

/**
 * The kernel's {@link MappingResolver}, backed by the mapping spine this launch staged.
 *
 * <p>It answers three questions, in the two states a launch can be in:
 *
 * <ul>
 *   <li><b>Mapping data staged (1.21.x, Fabric mods present).</b> The kernel runs the merged base under Mojmap,
 *       which the spine calls {@code named}, and a Fabric guest ships compiled against {@code intermediary}. So a
 *       query in {@code intermediary} is translated through {@link ForbricMappings}, and a query in the runtime
 *       namespace itself is the identity. This is the whole point of the type: a mod that asks instead of
 *       guessing is told the truth about names that really are different.</li>
 *   <li><b>No mapping data (26.2, or a pack with no Fabric mods).</b> Nothing is remapped anywhere in the launch,
 *       every name already IS the runtime name, and every lookup is the identity. {@link #RUNTIME_NAMESPACE} is
 *       what the resolver reports here. That is not a degraded mode — it is the correct answer for a game that
 *       needs no translation, and it stays the answer for every query in a namespace the resolver cannot
 *       translate, exactly as Fabric Loader answers an unmapped member with its input.</li>
 * </ul>
 *
 * <p>A query naming a class the spine does not know also comes back unchanged: the honest reply, and the one a
 * mod's own fallback expects.
 */
public final class KernelMappingResolver implements MappingResolver {
	/**
	 * The namespace the no-mapping-data kernel reports. This said {@code named}, and that is not what any real
	 * instance of this Fabric Loader reports: {@code javap} on {@code MappingConfiguration} in fabric-loader
	 * 0.19.5 shows the runtime namespace is read from {@code fabric.runtimeMappingNamespace} and falls back to
	 * the literal {@code official}. When mappings ARE staged the runtime namespace is the spine's {@code named}
	 * instead, because that is the namespace the remap stage targets and the merged base is compiled in — the
	 * two must agree or a mod branches on a name this resolver does not answer in.
	 *
	 * <p>The override property is honoured for the same reason it exists there: an instance that really is
	 * running under another naming can say so.
	 */
	public static final String RUNTIME_NAMESPACE =
			System.getProperty("fabric.runtimeMappingNamespace", "official");

	/** Null means no mapping data was staged; see the class javadoc. */
	private final FabricGuestMappings data;

	/** The identity resolver: no mapping data. */
	public KernelMappingResolver() {
		this(null);
	}

	/** @param data this launch's mapping data, or null for the identity resolver. */
	public KernelMappingResolver(FabricGuestMappings data) {
		this.data = data;
	}

	/** The spine, or null when this resolver has no mapping data. Fails loudly if the data cannot be parsed. */
	private ForbricMappings spine() {
		return data == null ? null : data.mappings();
	}

	@Override
	public Collection<String> getNamespaces() {
		if (spine() == null) return List.of(RUNTIME_NAMESPACE);

		// All three the spine carries, the runtime one included. A mod iterating this and translating from each
		// gets its own name back for the runtime namespace, which is the truthful answer rather than a trap.
		return List.of(ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY, ForbricMappings.OFFICIAL);
	}

	@Override
	public String getCurrentRuntimeNamespace() {
		return spine() == null ? RUNTIME_NAMESPACE : ForbricMappings.NAMED;
	}

	@Override
	public String mapClassName(String namespace, String className) {
		ForbricMappings mappings = spine();
		return mappings == null ? className
				: notation(className, mappings.mapClass(namespace, ForbricMappings.NAMED, internal(className)));
	}

	@Override
	public String unmapClassName(String targetNamespace, String className) {
		ForbricMappings mappings = spine();
		return mappings == null ? className
				: notation(className, mappings.mapClass(ForbricMappings.NAMED, targetNamespace, internal(className)));
	}

	@Override
	public String mapFieldName(String namespace, String owner, String name, String descriptor) {
		ForbricMappings mappings = spine();
		return mappings == null ? name
				: mappings.mapField(namespace, ForbricMappings.NAMED, internal(owner), name, descriptor);
	}

	@Override
	public String mapMethodName(String namespace, String owner, String name, String descriptor) {
		ForbricMappings mappings = spine();
		return mappings == null ? name
				: mappings.mapMethod(namespace, ForbricMappings.NAMED, internal(owner), name, descriptor);
	}

	/**
	 * The spine indexes class names the way a class file spells them — SLASHED. Callers do not agree: the kernel's
	 * own code and older consumers pass internal names, while the Fabric API is spelled the way
	 * {@code Class.getName()} is (DOTTED, {@code $} for nested), so a mod that uses the API as documented passes
	 * dots. A dotted name never reaches the index, the lookup misses, and the resolver answers its input
	 * unchanged — which reads as "the mapping does not know this class".
	 *
	 * <p>Measured on cardinal-components-base 6.1.3, the one consumer that has to ask rather than guess: its
	 * {@code CcaAsmHelper} names {@code ResourceLocation} inside a GENERATED class, so it calls
	 * {@code mapClassName("intermediary", "net.minecraft.class_2960")} and turns dots into slashes itself. With the
	 * dotted name missing the index, every generated component type referenced {@code net/minecraft/class_2960}
	 * and the first component lookup died with {@code NoClassDefFoundError: net/minecraft/class_2960} out of
	 * {@code Class.getDeclaredConstructors0} (bonfires-ex, fabric).
	 *
	 * <p>So the notation is the CALLER's: the query is normalized for the lookup and the answer comes back in the
	 * notation the question was asked in, exactly as the caller will go on to use it.
	 */
	private static String internal(String name) {
		return name == null ? null : name.replace('.', '/');
	}

	/** The converted answer, in the notation of {@code query} — see {@link #internal}. */
	private static String notation(String query, String mapped) {
		if (mapped == null || query == null) return mapped;
		boolean dotted = query.indexOf('/') < 0 && query.indexOf('.') >= 0;
		return dotted ? mapped.replace('/', '.') : mapped;
	}
}
