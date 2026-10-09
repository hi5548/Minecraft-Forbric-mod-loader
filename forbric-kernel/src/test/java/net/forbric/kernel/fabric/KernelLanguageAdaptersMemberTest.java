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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.LanguageAdapterException;

/**
 * The default adapter's {@code a.b.C::member} resolution — above all that a static METHOD is bound by DESCRIPTOR.
 *
 * <p>{@code Class.getDeclaredMethods()} has no defined order, so a class declaring two static methods of the same
 * name used to bind whichever the JVM listed first. When that was an overload the requested interface cannot be
 * implemented by (a different arity), {@code MethodHandleProxies} threw and the entrypoint failed to build. The
 * member the interface's own method type can adapt is the one that must be bound.
 */
@ResourceLock("KernelFabricEcosystem")
class KernelLanguageAdaptersMemberTest {
	@BeforeEach
	void forgetLoaderBinding() {
		KernelLanguageAdapters.reset();
	}

	@Test
	void anOverloadIsBoundByDescriptorNotDeclarationOrder() throws Exception {
		OverloadedEntrypoint.ZERO_ARG.set(0);
		OverloadedEntrypoint.STRING_ARG.set(0);

		ModInitializer mod = KernelLanguageAdapters.defaultAdapter().create(null,
				OverloadedEntrypoint.class.getName() + "::onInitialize", ModInitializer.class);
		mod.onInitialize();

		assertEquals(1, OverloadedEntrypoint.ZERO_ARG.get(), "the ()V method is the entrypoint");
		assertEquals(0, OverloadedEntrypoint.STRING_ARG.get(), "the (String) overload must never be bound");
	}

	@Test
	void aStaticMethodOfTheWrongArityIsNotBound() {
		assertThrows(LanguageAdapterException.class, () -> KernelLanguageAdapters.defaultAdapter().create(null,
				WrongArityEntrypoint.class.getName() + "::onInitialize", ModInitializer.class));
	}

	@Test
	void aStaticFieldIsStillTakenByValue() throws Exception {
		assertSame(FieldEntrypoint.INSTANCE, KernelLanguageAdapters.defaultAdapter().create(null,
				FieldEntrypoint.class.getName() + "::INSTANCE", ModInitializer.class));
	}

	@Test
	void theProvidesCheckFollowsTheSameRule() {
		assertTrue(KernelLanguageAdapters.hasStaticMember(OverloadedEntrypoint.class, "onInitialize", ModInitializer.class),
				"the ()V overload satisfies the interface");
		assertFalse(KernelLanguageAdapters.hasStaticMember(WrongArityEntrypoint.class, "onInitialize", ModInitializer.class),
				"an arity-mismatched overload does not");
		assertTrue(KernelLanguageAdapters.hasStaticMember(FieldEntrypoint.class, "INSTANCE", ModInitializer.class),
				"a static field of the interface type does");
		assertFalse(KernelLanguageAdapters.hasStaticMember(OverloadedEntrypoint.class, "absent", ModInitializer.class));
	}

	/** Two same-name static methods; only the zero-argument one implements {@link ModInitializer}. */
	public static final class OverloadedEntrypoint {
		static final AtomicInteger ZERO_ARG = new AtomicInteger();
		static final AtomicInteger STRING_ARG = new AtomicInteger();

		// Declared FIRST on purpose: the pre-fix name-only pick took the first same-name static method listed.
		public static void onInitialize(String wrong) {
			STRING_ARG.incrementAndGet();
		}

		public static void onInitialize() {
			ZERO_ARG.incrementAndGet();
		}
	}

	/** The requested interface needs {@code ()V}; this class only offers {@code (String)V}. */
	public static final class WrongArityEntrypoint {
		public static void onInitialize(String only) {
		}
	}

	/** The Kotlin-object shape the field branch exists for. */
	public static final class FieldEntrypoint {
		public static final ModInitializer INSTANCE = () -> {
		};
	}
}
