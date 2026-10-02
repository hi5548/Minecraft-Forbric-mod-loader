package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-structure pins for the fluid sprite/tint funnel — a chunk-mesh hot path.
 *
 * <p>PORT(1.21.1): 26.2's renderer chose a {@code FluidModel} and consulted Forge's extensions for a model plus a
 * no-argument tint; neither type exists on 1.21.1. The funnel's two sites are now {@code sprites(...)} (the
 * same-descriptor stand-in for NeoForge's {@code FluidSpriteCache.getFluidSprites}) and {@code tintColor(...)}
 * (NeoForge's {@code IClientFluidTypeExtensions.getTintColor} with the receiver prepended), and the pins below
 * follow those shapes. Every invariant the 26.2 pins carried — off-switch before any carrier call, the DEFAULT
 * identity short-circuit, the contains-before-add count line, any Throwable falling back to NeoForge on both
 * sites, and the switch never cached — has a 1.21.1 counterpart here, so none was dropped.
 */
class KernelForgeFluidsTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeFluids.java");

	private static String source() throws Exception {
		return Files.readString(SOURCE, StandardCharsets.UTF_8);
	}

	/** Site A, from its signature to the private logger it owns. */
	private static String sprites() throws Exception {
		String s = source();
		return s.substring(s.indexOf("public static TextureAtlasSprite[] sprites("), s.indexOf("private static void report("));
	}

	@Test
	void theDefaultExtensionShortCircuitsToNeoForgesSpritesByIdentity() throws Exception {
		String m = sprites();
		int shortCircuit = m.indexOf(
				"if (extensions == net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT) {");
		assertTrue(shortCircuit > 0, "vanilla fluids must not ask Forge's extensions, and must render byte-for-byte as before");
		assertTrue(m.substring(shortCircuit, m.indexOf("}", shortCircuit))
						.contains("return FluidSpriteCache.getFluidSprites(level, pos, state);"),
				"the DEFAULT extension answers NeoForge's own sprites by identity");
		assertTrue(shortCircuit < m.indexOf("net.minecraftforge.client.ForgeHooksClient.getFluidSprites("),
				"DEFAULT is checked before Forge's own sprite ask");
		assertTrue(m.indexOf("!ASKED.contains(fluid) && ASKED.add(fluid)") < shortCircuit,
				"a vanilla fluid in view is counted, so a gate can see the funnel");
	}

	@Test
	void theCountLineIsGatedByAContainsCheckBeforeTheAdd() throws Exception {
		String m = sprites();
		assertTrue(m.contains("!ASKED.contains(fluid) && ASKED.add(fluid)"),
				"contains-check first: this runs once per fluid tesselation");
	}

	@Test
	void theSwitchAndAnyFailureFallBackToVanillaOnBothSites() throws Exception {
		String s = source();
		String m = sprites();
		assertTrue(m.indexOf("return FluidSpriteCache.getFluidSprites(level, pos, state);") < m.indexOf("try {"),
				"off-switch answers NeoForge's sprites before the try, so no carrier failure can swallow it");
		assertTrue(m.indexOf("return FluidSpriteCache.getFluidSprites(level, pos, state);")
						< m.indexOf("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(state)"),
				"off-switch answers NeoForge's sprites before any Forge call");
		assertTrue(m.contains("catch (Throwable t)")
						&& m.substring(m.indexOf("catch (Throwable t)"))
								.contains("return FluidSpriteCache.getFluidSprites(level, pos, state);"),
				"any Throwable at site A answers NeoForge's sprites");
		String tint = s.substring(s.indexOf("public static int tintColor("));
		assertTrue(tint.contains("return neo.getTintColor(state, level, pos);"));
		assertTrue(tint.indexOf("return neo.getTintColor(state, level, pos);") < tint.indexOf("try {"),
				"off-switch answers NeoForge's tint before any Forge call");
		assertTrue(tint.contains("catch (Throwable t)") && tint.substring(tint.indexOf("catch (Throwable t)"))
						.contains("return neo.getTintColor(state, level, pos);"),
				"any Throwable at site B answers NeoForge's tint");
		assertFalse(s.contains("static final boolean"), "the switch is read per call");
	}
}
