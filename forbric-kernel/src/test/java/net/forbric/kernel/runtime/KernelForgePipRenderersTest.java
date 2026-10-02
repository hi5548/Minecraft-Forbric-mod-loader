package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The 1.21.1 contract of {@link KernelForgePipRenderers}: {@code build()} (its only entry point) answers with a
 * non-null, empty map, under the switch as well.
 *
 * <p>This class used to drive the 26.2 surface that filled the vanilla {@code GuiRenderer} map —
 * {@code poolRegistrations(List)}, {@code build(List)} and {@code close(Map)}, together with
 * {@code PictureInPictureRenderer}, NeoForge's {@code PictureInPictureRendererRegistration} and Forge's
 * {@code RegisterPictureInPictureRendererEvent}. None of those types exist on 1.21.1 (the whole
 * {@code net.minecraft.client.gui.render} package is absent), the production class's 26.2 members were removed
 * rather than stubbed because their parameter and return types cannot load here, and there is no registration
 * behaviour left to assert. What can be asserted is the contract the one surviving entry point still promises to
 * the boot side: never null, always empty, and never throwing a {@code NoSuchMethodError} at the caller.
 */
@ResourceLock("system-properties")
class KernelForgePipRenderersTest {
	@TempDir Path temporary;

	@Test
	void buildIsANonNullEmptyMapWithTheSwitchOnAndOff() throws Exception {
		Path runtime = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		assumeTrue(Files.isRegularFile(runtime.resolve("net/forbric/kernel/runtime/KernelForgePipRenderers.class")),
				"runtime source set not compiled");
		String property = "forbric.forgePipRenderers";
		String previous = System.getProperty(property);
		try (var loader = new URLClassLoader(new URL[] {runtime.toUri().toURL()}, getClass().getClassLoader())) {
			Class<?> bridge = loader.loadClass("net.forbric.kernel.runtime.KernelForgePipRenderers");
			var build = bridge.getMethod("build");
			Map<?, ?> on = (Map<?, ?>) build.invoke(null);
			assertNotNull(on, "a boot-side repair that assigns this map cannot receive null");
			assertTrue(on.isEmpty(), "nothing registers picture-in-picture renderers on 1.21.1");

			System.setProperty(property, "off");
			Map<?, ?> off = (Map<?, ?>) build.invoke(null);
			assertNotNull(off);
			assertTrue(off.isEmpty(), "the negative control keeps the map empty rather than changing its shape");
		} finally {
			if (previous == null) System.clearProperty(property);
			else System.setProperty(property, previous);
		}
	}
}
