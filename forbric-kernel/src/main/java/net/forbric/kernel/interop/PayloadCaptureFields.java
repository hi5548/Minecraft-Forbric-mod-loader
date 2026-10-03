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

package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.util.Map;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The captured fields the merged custom-payload codec provider hands {@link PayloadInterop#findCodec} — resolved
 * from the class that declares them, never spelled.
 *
 * <p>{@code MergedBaseBuilder} splices a {@code findCodec} body into the merged
 * {@code CustomPacketPayload$1$forbricneo} that reads the anonymous class's captured fields. Those names come from
 * the decompile pipeline that produced the NeoForge half, and they are NOT stable across generations: the 26.2
 * base names the id→type map {@code val$idToType} and the fallback {@code val$fallback}, while the 1.21.1 base
 * names them {@code val$map} and {@code val$p_319839_}. The builder emits whichever spelling its own generation
 * had; the kernel owns what its own base links against, so both halves that touch these fields — the splice repair
 * in {@code ForbricMergedBaseCompatTransformer} and the reflective read in {@link PayloadInterop} — take the name
 * from this one resolution instead of a literal.
 *
 * <p>One resolution, two shapes: {@link #idToType(Class)} for the live class and {@link #uniqueDeclared(ClassNode,
 * String)} for the parsed bytes. Both answer the same question — "the class's one field of this descriptor" — so
 * the splice and the read cannot drift apart again. When the answer is not unique the resolution stands down
 * (answers {@code null}) rather than guess: the failure this removes is a hard {@code NoSuchFieldError} on the
 * first handshake packet, and a wrong guess would only move it somewhere less legible.
 */
public final class PayloadCaptureFields {
	/** The id→type map's erased descriptor. The one capture whose type is not a game class, so the one the
	 * runtime read can find by {@code java.util.Map} alone. */
	public static final String ID_TO_TYPE = "Ljava/util/Map;";

	private PayloadCaptureFields() {
	}

	/**
	 * The live class's sole {@code java.util.Map} capture, cached per class.
	 *
	 * <p>{@code null} when the class declares none or more than one — the caller then stands down instead of
	 * reading a field it cannot be sure of.
	 */
	public static String idToType(Class<?> owner) {
		if (owner == null) return null;
		String name = ID_TO_TYPE_NAMES.get(owner);
		return name.isEmpty() ? null : name;
	}

	private static final ClassValue<String> ID_TO_TYPE_NAMES = new ClassValue<>() {
		@Override
		protected String computeValue(Class<?> owner) {
			String found = null;
			for (Field field : owner.getDeclaredFields()) {
				if (!isTheMap(field)) continue;
				if (found != null) return ""; // more than one candidate: stand down
				found = field.getName();
			}
			return found == null ? "" : found;
		}
	};

	/**
	 * A field's type can be unloadable in a registry that does not carry the whole game. That is a field which is
	 * not the map; it is not this resolution's business to fail over it.
	 */
	private static boolean isTheMap(Field field) {
		try {
			return field.getType() == Map.class;
		} catch (Throwable unloadable) {
			return false;
		}
	}

	/**
	 * The parsed class's sole field of {@code desc}, or {@code null} when it declares none or more than one.
	 *
	 * <p>The bytecode half of the resolution, used by the transformer's repair of the splice the builder already
	 * emitted. It reads the class's own field table rather than any pipeline's naming convention.
	 */
	public static String uniqueDeclared(ClassNode owner, String desc) {
		String found = null;
		for (FieldNode field : owner.fields) {
			if (!desc.equals(field.desc)) continue;
			if (found != null) return null; // ambiguous: stand down
			found = field.name;
		}
		return found;
	}

	/** Whether the class already declares exactly {@code name:desc} — the read is not one this repair must move. */
	public static boolean declares(ClassNode owner, String name, String desc) {
		for (FieldNode field : owner.fields) {
			if (name.equals(field.name) && desc.equals(field.desc)) return true;
		}
		return false;
	}
}
