/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.zip.ZipFile;import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.TestFixtures;

/**
 * Real carrier bytecode: each relocated Create seam still verifies and is idempotent on 1.21.1.
 *
 * <p>The targets are the 1.21.1 shapes: {@code CommonHooks.onLivingBreathe(LivingEntity, int, int)} for breathing
 * (26.2 carried a {@code ServerLevel} argument that is gone), and vanilla {@code Entity} / {@code LivingEntity} for
 * step and fall sounds (NeoForge's {@code IBlockExtension.playStepSound}/{@code playFallSound} are gone). The HUD
 * context injector stands down on this generation and is asserted to pass every class through unchanged.
 */
class CreateCarrierHooksTest {
	@Test
	void realNativeCarrierHooksRemainVerifiableAndAreIdempotent()throws Exception{
		Path merged = TestFixtures.mergedBase();
		org.junit.jupiter.api.Assumptions.assumeTrue(merged != null && Files.isRegularFile(merged), "staged merged base absent");
		for(var row:List.of(
				new Object[]{new CreateBreathingInjector(),CreateBreathingInjector.TARGET,"neoforge-runtime/neoforge-runtime.jar",2},
				new Object[]{new CreateSoundQueryInjector(),CreateSoundQueryInjector.TARGET,"merged",3},
				new Object[]{new CreateSoundQueryInjector(),CreateSoundQueryInjector.FALL_TARGET,"merged",1})){
			Path jar= "merged".equals(row[2]) ? merged : TestFixtures.stagedRoot().resolve((String)row[2]);
			org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar));
			byte[] original;try(var zip=new ZipFile(jar.toFile())){original=zip.getInputStream(zip.getEntry(((String)row[1]).replace('.','/')+".class")).readAllBytes();}
			ClassTransformer transformer=(ClassTransformer)row[0];byte[] patched=transformer.transform((String)row[1],original,null);assertNotSame(original,patched);assertSame(patched,transformer.transform((String)row[1],patched,null));ClassNode node=new ClassNode();new ClassReader(patched).accept(node,0);int count=0;
			for(var method:node.methods){if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&(call.owner.equals("net/forbric/kernel/interop/CreateBreathingScope")||call.owner.equals("net/forbric/kernel/runtime/KernelCreateSoundQuery")))count++;}
			assertEquals(row[3],count,(String)row[1]);
		}
	}

	/** The HUD context injector has no 1.21.1 target: every class must come back byte-identical. */
	@Test void theHudContextSeamStandsDown() throws Exception {
		ClassTransformer injector = new CreateHudContextInjector();
		byte[] any = {1, 2, 3};
		assertSame(any, injector.transform(CreateHudContextInjector.TARGET, any, null));
		assertTrue(injector.anchors().anchors().isEmpty(), "a documented stand-down is not a missed anchor");
	}
}
