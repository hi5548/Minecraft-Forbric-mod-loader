/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The two generations' names for the id accessor and the identifier class. */
class IdentifierNamesTest {
	/** The 1.21.1 shape: {@code location()} and no {@code identifier()}. */
	static final class OldKey {
		public Object location() {
			return "id";
		}
	}

	/** The newer generation's shape: {@code identifier()}. */
	static final class NewKey {
		public Object identifier() {
			return "id";
		}
	}

	/** Both declared: the 1.21.1 accessor is the one to take, so a base carrying both resolves the running one. */
	static final class BothKey {
		public Object location() {
			return "old";
		}

		public Object identifier() {
			return "new";
		}
	}

	@Test
	void readsTheOlderGenerationsAccessor() throws Exception {
		Method method = IdentifierNames.idGetter(OldKey.class);
		assertEquals("location", method.getName());
		assertEquals("id", method.invoke(new OldKey()));
	}

	@Test
	void readsTheNewerGenerationsAccessorWhenItIsAllThereIs() throws Exception {
		Method method = IdentifierNames.idGetter(NewKey.class);
		assertEquals("identifier", method.getName());
		assertEquals("id", method.invoke(new NewKey()));
	}

	@Test
	void prefersTheRunningsGenerationsAccessor() throws Exception {
		assertEquals("location", IdentifierNames.idGetter(BothKey.class).getName());
	}

	@Test
	void saysSoWhenNeitherNameExists() {
		assertThrows(NoSuchMethodException.class, () -> IdentifierNames.idGetter(String.class));
	}

	@Test
	void resolvesTheIdentifierClassUnderEitherName() throws Exception {
		// Neither class is on this JVM's classpath, so each loader stands in for one generation's base.
		assertEquals("net.minecraft.resources.ResourceLocation",
				IdentifierNames.identifierClass(loader("net.minecraft.resources.ResourceLocation")).getName());
		assertEquals("net.minecraft.resources.Identifier",
				IdentifierNames.identifierClass(loader("net.minecraft.resources.Identifier")).getName());
	}

	private static ClassLoader loader(String exposed) {
		return new ClassLoader(IdentifierNamesTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!exposed.equals(name)) throw new ClassNotFoundException(name);
				byte[] bytes = emptyClass(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
	}

	private static byte[] emptyClass(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}
}
