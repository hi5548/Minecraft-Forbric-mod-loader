package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class FabricRegistryLoaderMixinAdapterTest {
	private ClassNode mixin() throws Exception {
		Path path=Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");assumeTrue(Files.isRegularFile(path));
		try(ZipFile zip=new ZipFile(path.toFile())){return MixinFit.parse(zip.getInputStream(zip.getEntry("net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin.class")).readAllBytes());}
	}
	@Test void serverBindingAndAsyncCaptureUseBothLiveOverloads() throws Exception {
		ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/resources/RegistryDataLoader",false);
		assertEquals(2,FabricRegistryLoaderMixinAdapter.adapt(mixin,n->target));
		MethodNode wrap=StagedFabricMixinFixture.method(mixin,"wrapIsServerCall");
		assertTrue(wrap.desc.contains(";ZLcom/llamalad7"));
		assertTrue(String.valueOf(MixinFit.value(MixinFit.injectorOf(wrap),"method")).contains("Executor;Ljava/util/List;"));
		assertTrue(String.valueOf(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"supplyAsync")),"method")).contains("Executor;Z)"));
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name,wrap);
		ClassNode runtime=MixinFit.parse(Files.readAllBytes(Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelWrapOperations.class")));
		for(var i:wrap.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(runtime.name))assertTrue(runtime.methods.stream().anyMatch(m->m.name.equals(c.name)&&m.desc.equals(c.desc)),"the generated wrapper must link to the actual compiled game helper");
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"wrapIsServerCall$forbricOriginal")));
		assertEquals(0,FabricRegistryLoaderMixinAdapter.adapt(mixin,n->target));
	}
	@Test void aVanillaLoaderKeepsTheOriginalHandlers() throws Exception {
		ClassNode vanilla=StagedFabricMixinFixture.game("net/minecraft/resources/RegistryDataLoader",true);
		assertEquals(0,FabricRegistryLoaderMixinAdapter.adapt(mixin(),n->vanilla));
	}

	/**
	 * The oracle for the defect this adapter's name carried: the class a PIN names must be the class the installed
	 * module declares, or the pin suppresses nothing while the shipped mixin runs unmeasured. 0.116.17 (this branch's
	 * fabric-api) declares {@code RegistryLoaderMixin}; the 26.2 fixture declares {@code RegistryDataLoaderMixin};
	 * whichever is staged, every one the adapter knows must also be pinned. Red while the pin named only
	 * {@code RegistryDataLoaderMixin} and the module shipped the other name.
	 */
	@Test void everyRegistryLoaderNameTheStagedModuleDeclaresIsPinned() throws Exception {
		String config="fabric-registry-sync-v0.mixins.json";
		java.util.Optional<String> json=StagedFabricMixinFixture.mixinConfigText("fabric-registry-sync-v0",config);
		assumeTrue(json.isPresent(),"the staged fabric-api fixture is required for the generation check");
		Set<String> declared=FabricRegistryLoaderMixinAdapter.knownNames().stream()
				.map(n->n.substring(n.lastIndexOf('/')+1))
				.filter(simple->json.get().contains("\""+simple+"\""))
				.collect(java.util.stream.Collectors.toSet());
		assertFalse(declared.isEmpty(),"the staged fabric-api module must declare one generation's registry-loader mixin");
		for(String simple:declared){
			assertTrue(FabricRegistryLoaderMixinAdapter.PINS.contains(config+":"+simple),
					"the module declares "+simple+" and the adapter knows it, so a pin must name it — otherwise the "
							+"fallback suppresses nothing: "+FabricRegistryLoaderMixinAdapter.PINS);
		}
		assertTrue(FabricRegistryLoaderMixinAdapter.PINS.contains(config+":RegistryLoaderMixin"),
				"this branch's own generation (fabric-api 0.116.17) must be pinned: it is the name that applies here");
	}
}
