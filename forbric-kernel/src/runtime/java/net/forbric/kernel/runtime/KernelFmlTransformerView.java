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

/**
 * Where a NeoForge mod asks for FML's {@code TransformingClassLoader} — the kernel's answer on THIS generation.
 *
 * <h2>What it was on 26.2</h2>
 *
 * <p>On 26.2 this class fabricated FML's {@code ClassProcessor} graph
 * ({@code net.neoforged.fml.classloading.transformation}, {@code net.neoforged.neoforgespi.transformation})
 * around the kernel's live Mixin weaver, because one NeoForge guest (LibJF's ASM layer) walks
 * {@code (TransformingClassLoader) getContextClassLoader()} → {@code classTransformer} → {@code processors} →
 * {@code ClassProcessorIds.MIXIN} → {@code FMLMixinClassProcessor.transformer} and writes its own transformer
 * back. Handing it that graph is what made the write-back authoritative, and {@code MixinWeaverSlot} followed
 * the field so a guest's wrapper wove every later class.
 *
 * <h2>Why it cannot exist on 1.21.1</h2>
 *
 * <p>NeoForge 21.1 has no {@code net.neoforged.fml.classloading.transformation} and no
 * {@code net.neoforged.neoforgespi.transformation} — that framework is a later generation, and this carrier is
 * ModLauncher 11-based. There is no 21.1 object graph to fabricate, and no 1.21.1 guest has been observed
 * walking an equivalent path. Building a 26.2-shaped graph would hand a guest an instance of a class this
 * carrier does not contain.
 *
 * <h2>The contract kept instead</h2>
 *
 * <p>{@link #contextLoader} answers {@code real}. That is exactly what 26.2's view did on its own
 * "cannot be built" path: the guest gets the kernel's loader, its cast fails, and it runs without its class
 * patches — the behaviour before the shim existed. The seam is unchanged ({@code FmlContextLoaderRewriter}
 * still injects the call, {@code KernelRuntimeClasses} still checks the signature), so porting a real view
 * later is a body change, not a rewiring.
 *
 * <p><b>P2 item</b>: identify which 1.21.1 guests (if any) reach for a transformer through the context loader,
 * and fabricate the 21.1-era graph for them — ModLauncher 11's {@code TransformingClassLoader} with the Mixin
 * transformer exposed under {@code cpw.mods.modlauncher.api.ITransformer}.
 */
public final class KernelFmlTransformerView {
	private KernelFmlTransformerView() {
	}

	private static boolean saidUnavailable;

	/**
	 * What a NeoForge mod's {@code (TransformingClassLoader) getContextClassLoader()} should cast in this
	 * generation: the loader it already had. {@code FmlContextLoaderRewriter} puts this call between those two
	 * instructions and leaves the cast in place; on 26.2 this could answer a fabricated view instead.
	 */
	public static ClassLoader contextLoader(ClassLoader real) {
		if (!saidUnavailable) {
			saidUnavailable = true;
			ForbricLog.warn("[Forbric/FmlView] a NeoForge mod walked FML's TransformingClassLoader path; this "
					+ "generation (1.21.1) has no class-processor graph to hand it — NeoForge 21.1 does not carry "
					+ "one — so it gets the kernel's loader and runs without its class patches (see this class's "
					+ "javadoc, P2 item)");
		}
		return real;
	}
}
