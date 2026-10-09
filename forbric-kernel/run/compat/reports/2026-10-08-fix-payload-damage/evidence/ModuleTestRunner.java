package net.forbric.kernel.transform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the two shape tests without Gradle's {@code test} task. The task pulls in the game side, which needs the
 * pinned Team Reborn Energy jar this machine does not have; the tests themselves need only the staged jars and ASM.
 * JUnit is used only for its annotations and its {@code TestAbortedException} (fixture-absent = skip), never for
 * discovery, so a skip reads exactly as JUnit's {@code Assumptions.assumeTrue} would.
 *
 * <p>Usage: {@code ModuleTestRunner <stagedRoot>}.
 */
public final class ModuleTestRunner {
	public static void main(String[] args) throws Exception {
		System.setProperty("forbric.stagedRoot", args[0]);
		System.setProperty("forbric.mcVersion", "1.21.1");
		int pass = 0, fail = 0, skip = 0;
		for (Class<?> type : new Class<?>[] { PayloadWorkOrderingTransformerTest.class, ForgeDamageSeamsInjectorTest.class }) {
			System.out.println("== " + type.getName());
			for (Method test : type.getDeclaredMethods()) {
				if (!test.isAnnotationPresent(Test.class)) continue;
				Object instance = type.getDeclaredConstructor().newInstance();
				test.setAccessible(true);
				try {
					test.invoke(instance);
					System.out.println("  PASS " + test.getName());
					pass++;
				} catch (InvocationTargetException wrapped) {
					Throwable cause = wrapped.getCause();
					if (cause instanceof org.opentest4j.TestAbortedException) {
						System.out.println("  SKIP " + test.getName() + " - " + cause.getMessage());
						skip++;
					} else {
						System.out.println("  FAIL " + test.getName() + " - " + cause);
						fail++;
					}
				} finally {
					for (Method after : type.getDeclaredMethods()) {
						if (!after.isAnnotationPresent(AfterEach.class)) continue;
						after.setAccessible(true);
						after.invoke(instance);
					}
				}
			}
		}
		System.out.println("pass=" + pass + " fail=" + fail + " skip=" + skip);
		if (fail > 0) System.exit(1);
	}
}
