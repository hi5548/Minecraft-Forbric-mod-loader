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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.GeometryLoaderManager;

import net.forbric.kernel.transform.ModelFormatFunnelInjector;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a model format NeoForge does not own be parsed by the code that does own it.
 *
 * <h2>One deserializer, two readers of the {@code "loader"} key</h2>
 *
 * <p>Every block model on the 1.21.1 merged base is read by NeoForge's
 * {@code ExtendedBlockModelDeserializer} (the {@code BlockModel} adapter the game's GSON holds). Its
 * {@code deserialize} calls the vanilla {@code BlockModel$Deserializer} first and then
 * {@code deserializeGeometry(context, json)}, which reads {@code "loader"} and dispatches to a NeoForge
 * {@code IGeometryLoader} — and THROWS {@code Model loader '%s' not found. Registered loaders: %s} for an id it did
 * not register. That happens before anything else can read the key.
 *
 * <p>On MinecraftForge the same key is read by the vanilla deserializer's geometry hook, and mods that add a format
 * without a geometry loader hook that deserializer too (fusion claims {@code "loader": "fusion:model"} at its
 * HEAD). On the merged base NeoForge's throw — or its geometry dispatch — comes first, so none of those readers is
 * ever reached.
 *
 * <h2>The funnel</h2>
 *
 * <p>{@code ModelFormatFunnelInjector} inserts one call to {@link #foreign} ahead of NeoForge's dispatch. A
 * non-null answer is returned as the model; null means "NeoForge's", and its dispatch runs exactly as before.
 * The rules:
 * <ul>
 *   <li>A {@code "loader"} id NeoForge registered is always NeoForge's. Precedence between the two readers of that
 *       key is not renegotiated.</li>
 *   <li>An id NeoForge does NOT own is parsed by the vanilla {@code BlockModel$Deserializer} directly, so a
 *       MinecraftForge geometry loader or a guest hook on that deserializer reads the key as it does on
 *       MinecraftForge. An id none of them claims still parses as a plain cuboid (the merged deserializer ignores
 *       the key entirely), which is the model the JSON describes minus the geometry.</li>
 *   <li>A string loader that will not even parse as a resource location is left to NeoForge, whose
 *       {@code ResourceLocation.parse} reports it in its own words.</li>
 *   <li>NeoForge's object form, {@code {"id": ..., "optional": ...}}, is NeoForge's own dialect; a non-optional
 *       miss keeps NeoForge's error. An OPTIONAL miss means "parse this as a plain model", and the plain parse
 *       gets a copy without the key.</li>
 * </ul>
 *
 * <h2>Linkage errors stay inside one model</h2>
 *
 * <p>The funnel runs guest code — MinecraftForge's geometry loaders, fusion's hook — inside
 * {@code ModelManager}'s per-model {@code catch}, which covers {@code Exception} and nothing else. A
 * {@code NoSuchMethodError} or {@code NoClassDefFoundError} from code compiled against another base is not an
 * {@code Exception}: it would leave that catch, fail the whole block-model load, and a failed resource reload makes
 * Minecraft drop every resource pack, reload, and sit on a black screen. So a {@code LinkageError} from anything
 * the funnel dispatches to is rethrown as a {@code JsonParseException} naming the format: that model fails, logged
 * by the game's own "Failed to load model", and the reload goes on.
 *
 * <p><b>PORT(1.21.1), two losses, both recorded rather than papered over:</b>
 * <ul>
 *   <li>Fabric's {@code "fabric:type"} route is GONE. 26.2 read it through fabric-model-loading's
 *       {@code UnbakedModelDeserializer} registry. 1.21.1's {@code fabric-model-loading-api-v1} (nested in
 *       fabric-api 0.116.17+1.21.1, checked with javap) has no such class and no {@code fabric:type} string
 *       anywhere: its surface is {@code ModelLoadingPlugin} / {@code ModelModifier} / {@code ModelResolver}. A
 *       model carrying only {@code fabric:type} therefore parses as a plain cuboid here, exactly as it does on any
 *       loader without that reader. Restoring it needs a replacement written against {@code ModelResolver}.</li>
 *   <li>MinecraftForge's own GEOMETRY LOADERS are unreachable, and the funnel cannot fix that: the merged
 *       {@code BlockModel$Deserializer} names no geometry class at all (checked against its constant pool), and
 *       Forge's {@code IUnbakedGeometry} cannot be attached through NeoForge's {@code BlockGeometryBakingContext}.
 *       So a {@code "loader"} id only MinecraftForge knows now yields the plain cuboid. Restoring it is a boot-side
 *       seam (an injector into the merged deserializer that re-adds the Forge geometry hook), not a change here.</li>
 * </ul>
 *
 * <p>{@code -Dforbric.modelFormatFunnel=off} restores the previous behaviour on both halves: the injector is not
 * registered, and this method answers null.
 */
public final class KernelModelFormats {
	private static final String LOADER_KEY = "loader";

	/** One line per route and format id, the first time it is taken — the live evidence, bounded by format count. */
	private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

	private KernelModelFormats() {
	}

	/**
	 * The model for {@code json} when its format is not NeoForge's to parse, or null to let NeoForge's deserializer
	 * carry on.
	 *
	 * <p>Called from the head of NeoForge's {@code ExtendedBlockModelDeserializer.deserialize}, so its descriptor
	 * is the one the inserted call site pushes: the {@code JsonObject} NeoForge has just unwrapped and the context
	 * it was handed. {@code ModelFormatFunnelInjector} is re-anchored there on 1.21.1 (26.2 anchored inside
	 * {@code UnbakedModelParser$Deserializer}, which does not exist here).
	 */
	public static UnbakedModel foreign(JsonObject json, JsonDeserializationContext context) {
		if (!enabled() || json == null) return null;
		JsonElement loader = json.get(LOADER_KEY);
		if (loader == null) return null;
		if (loader.isJsonPrimitive() && loader.getAsJsonPrimitive().isString()) {
			ResourceLocation id = ResourceLocation.tryParse(loader.getAsString());
			// An id that does not parse is left to NeoForge, whose ResourceLocation.parse reports it as it always has.
			if (id == null || neoForgeOwns(id)) return null;
			announce(LOADER_KEY, id, "\"loader\": \"%s\" is not a NeoForge loader — handed to the vanilla cuboid "
					+ "deserializer, where a MinecraftForge geometry loader or a guest hook on it (fusion) reads the "
					+ "key as it does on MinecraftForge; an id none of them claims parses as a plain model");
			return asBlockModel(json, context, "\"loader\": \"" + id + "\"");
		}
		if (loader.isJsonObject()) {
			JsonObject spec = loader.getAsJsonObject();
			if (!GsonHelper.isStringValue(spec, "id")) return null;
			ResourceLocation id = ResourceLocation.tryParse(spec.get("id").getAsString());
			if (id == null || neoForgeOwns(id)) return null;
			// NeoForge's own dialect: a required miss is NeoForge's error to report, in NeoForge's words.
			if (!GsonHelper.getAsBoolean(spec, "optional", false)) return null;
			JsonObject plain = json.deepCopy();
			plain.remove(LOADER_KEY);
			announce("optional " + LOADER_KEY, id, "optional loader %s is absent — parsed as a plain model without "
					+ "the loader object, which the deserializer would otherwise have to reject");
			return asBlockModel(plain, context, "a plain model (optional loader " + id + " absent)");
		}
		return null;
	}

	/**
	 * Whether NeoForge registered a loader under {@code id}.
	 *
	 * <p>This is the same registry {@code deserializeGeometry} consults, so an id answered "NeoForge's" here is
	 * dispatched there one instruction later and an id answered otherwise really would have thrown.
	 *
	 * <p>Before NeoForge's loader registry is initialised the lookup NPEs; that is answered "NeoForge's", so the
	 * failure is NeoForge's own, raised one instruction later by the code that always raised it.
	 */
	private static boolean neoForgeOwns(ResourceLocation id) {
		try {
			return GeometryLoaderManager.get(id) != null;
		} catch (RuntimeException notYetInitialised) {
			return true;
		}
	}

	/**
	 * The vanilla deserializer's answer, reached without going back through Gson.
	 *
	 * <p>{@code context.deserialize(json, BlockModel.class)} is NOT usable: on 1.21.1 the registered adapter for
	 * {@code BlockModel} is NeoForge's {@code ExtendedBlockModelDeserializer}, the very class this is called from, so
	 * it would re-enter the funnel with the same object. Constructing the vanilla deserializer directly reaches the
	 * same code NeoForge's own {@code super.deserialize} reaches, with the real context for nested reads.
	 *
	 * <p>{@code format} names what was handed on, for the one failure this does not pass through as it came: a
	 * {@code LinkageError} (see the class javadoc).
	 */
	private static UnbakedModel asBlockModel(JsonObject json, JsonDeserializationContext context, String format) {
		try {
			return new BlockModel.Deserializer().deserialize(json, BlockModel.class, context);
		} catch (LinkageError e) {
			throw doesNotLink(format, e);
		}
	}

	private static void announce(String route, ResourceLocation id, String what) {
		if (ANNOUNCED.add(route + " " + id)) ForbricLog.info("[Forbric/ModelFormats] " + what, id);
	}

	/**
	 * A {@code LinkageError} from guest code as the {@code Exception} the game's per-model catch can hold — so one
	 * model fails, not the resource reload. Said once per format at WARN, since the game's own line names only the
	 * model file.
	 */
	private static JsonParseException doesNotLink(String format, LinkageError error) {
		if (ANNOUNCED.add("linkage " + format)) {
			ForbricLog.warn("[Forbric/ModelFormats] the code parsing %s does not link on the merged base (%s) — each "
					+ "model in that format fails on its own instead of failing the resource reload, which would drop "
					+ "every resource pack", format, String.valueOf(error));
		}
		return new JsonParseException(format + " does not link on the merged base: " + error, error);
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(ModelFormatFunnelInjector.PROPERTY, "on"));
	}
}
