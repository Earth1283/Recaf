package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.coley.recaf.services.transform.BuiltinTransformationPresets;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherInliningTest extends TransformerTestBase {
	private static final List<Class<? extends software.coley.recaf.services.transform.JvmClassTransformer>> PLAN = List.of(
			DispatcherInliningTransformer.class, OpaquePredicateFoldingTransformer.class, DeadCodeRemovingTransformer.class,
			GotoInliningTransformer.class, VariableFoldingTransformer.class);

	@Test void stackCarriedConstantChainEliminatesDispatcher() {
		assemble("""
				.method public static example ()I {
				    code: {
				    A:
				        ldc -801258686
				        goto Dispatch
				    Done:
				        ldc Ljava/lang/Object;
				        invokestatic java/lang/System.identityHashCode (Ljava/lang/Object;)I
				        pop
				        bipush 42
				        ireturn
				    First:
				        ldc 1769454168
				        goto Dispatch
				    Second:
				        ldc 2094957433
				        goto Dispatch
				    Third:
				        ldc -952468007
				    Dispatch:
				        lookupswitch {
				            -952468007: Done,
				            -801258686: First,
				            1769454168: Second,
				            2094957433: Third,
				            default: Done
				        }
				    End:
				    }
				}
				""", false);
		assertEquals(42, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		var preset = BuiltinTransformationPresets.CONTROL_FLOW_SIMPLIFICATION;
		List<Class<? extends JvmClassTransformer>> transformers = preset.transformers().stream()
				.<Class<? extends JvmClassTransformer>>map(entry -> {
					try {
						return Class.forName(entry.transformerClassName()).asSubclass(JvmClassTransformer.class);
					} catch (ClassNotFoundException ex) {
						throw new AssertionError(ex);
					}
				}).toList();
		var result = assertDoesNotThrow(() -> newApplier(preset.maxPasses()).transformJvm(transformers));
		assertTrue(result.getTransformerFailures().isEmpty());
		result.apply();
		assertEquals(42, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		String output = disassemble(false);
		assertFalse(output.contains("switch"), output);
		assertTrue(output.contains("System.identityHashCode"), output);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, -1, 7})
	void stackTableDispatcherPreservesLowerValuesAndRuntimeDefaults(int initialState) {
		assemble("""
				.method public static example ()I {
				    code: {
				    A:
				        ldc 100L
				        bipush %d
				        goto Dispatch
				    First:
				        ldc Ljava/lang/Object;
				        invokestatic java/lang/System.identityHashCode (Ljava/lang/Object;)I
				        pop
				        iconst_1
				        goto Dispatch
				    Second:
				        iconst_2
				        goto Dispatch
				    Done:
				        l2i
				        ireturn
				    Default:
				        l2i
				        iconst_3
				        iadd
				        ireturn
				    Dispatch:
				        tableswitch {
				            min: 0,
				            max: 2,
				            cases: { First, Second, Done },
				            default: Default
				        }
				    End:
				    }
				}
				""".formatted(initialState), false);
		int expected = initialState >= 0 && initialState <= 2 ? 100 : 103;
		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		var result = assertDoesNotThrow(() -> newApplier(6).transformJvm(PLAN));
		assertTrue(result.getTransformerFailures().isEmpty());
		result.apply();
		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		assertFalse(disassemble(false).contains("switch"));
		assertEquals(initialState == 0, disassemble(false).contains("System.identityHashCode"));
	}

	@Test void runtimeStackSelectorKeepsItsSwitchAndDefault() {
		assemble("""
				.method public static example ()I {
				    code: {
				    A:
				        ldc Ljava/lang/Object;
				        invokestatic java/lang/System.identityHashCode (Ljava/lang/Object;)I
				        goto Dispatch
				    Done:
				        iconst_5
				        ireturn
				    Dispatch:
				        lookupswitch {
				            123: Done,
				            default: Done
				        }
				    End:
				    }
				}
				""", false);
		var result = assertDoesNotThrow(() -> newApplier().transformJvm(List.of(DispatcherInliningTransformer.class)));
		assertTrue(result.getTransformerFailures().isEmpty());
		assertTrue(result.getTransformedClasses().isEmpty());
		assertTrue(disassemble(false).contains("switch"));
	}

	@Test void constantChainPreservesEffectsAndEliminatesDispatcher() throws Exception {
		compileFull("Example", """
				public class Example {
				  static int count;
				  public static int example() {
				    int state = 123;
				    while (true) switch (state) {
				      case 123 -> { count += 2; state = 321; }
				      case 321 -> { count *= 3; state = 69; }
				      case 69 -> { return count + 1; }
				      default -> throw new AssertionError();
				    }
				  }
				}
				""");
		assertEquals(7, invokeStatic("Example", "example", "Example"));
		var result = newApplier(6).transformJvm(PLAN);
		assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(7, invokeStatic("Example", "example", "Example"));
		assertFalse(disassemble(true).contains("switch"));
	}

	@Test void conditionalTransitionsRetainRealBranches() throws Exception {
		compileFull("Example", """
				public class Example {
				  static int run(boolean allowed) {
				    int state = 123;
				    while (true) switch (state) {
				      case 123 -> { if (allowed) state = 321; else state = 69; }
				      case 321 -> { return 7; }
				      case 69 -> { return 11; }
				      default -> throw new AssertionError();
				    }
				  }
				  public static int example() { return run(true) * 100 + run(false); }
				}
				""");
		assertEquals(711, invokeStatic("Example", "example", "Example"));
		var result = newApplier(6).transformJvm(PLAN); assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(711, invokeStatic("Example", "example", "Example"));
		assertFalse(disassemble(true).contains("switch"));
		assertTrue(disassemble(true).contains("ifeq") || disassemble(true).contains("ifne"));
	}

	@Test void runtimeStateKeepsItsSwitchAndDefault() throws Exception {
		compileFull("Example", """
				public class Example {
				  static int run(int state) { while (true) switch(state) { case 123: return 5; case 321: return 9; default: return 42; } }
				  public static int example() { return run(123) + run(321) + run(-1); }
				}
				""");
		var result = newApplier().transformJvm(List.of(DispatcherInliningTransformer.class));
		assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(56, invokeStatic("Example", "example", "Example"));
		assertTrue(disassemble(true).contains("switch"));
	}

	@Test void ternaryTransitionWithASharedStoreIsSpecializedWithoutLosingTheCondition() throws Exception {
		compileFull("Example", """
				public class Example {
				  static int run(boolean allowed) {
				    int state = 123;
				    while (true) switch (state) {
				      case 123 -> state = allowed ? 321 : 69;
				      case 321 -> { return 7; }
				      case 69 -> { return 11; }
				      default -> throw new AssertionError();
				    }
				  }
				  public static int example() { return run(true) * 100 + run(false); }
				}
				""");
		var result = newApplier(6).transformJvm(PLAN); assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(711, invokeStatic("Example", "example", "Example"));
		assertFalse(disassemble(true).contains("switch"));
	}

	@Test void resettingStateInsideLoopDoesNotReachTheNextCase() throws Exception {
		compileFull("Example", """
				public class Example {
				  public static int example() {
				    int result = 0;
				    for (int i = 0; i < 4; i++) {
				      int state = 123;
				      switch(state) { case 123 -> { state = 321; result++; } case 321 -> result += 100; }
				    }
				    return result;
				  }
				}
				""");
		var result = newApplier(6).transformJvm(PLAN); assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(4, invokeStatic("Example", "example", "Example"));
	}

	@Test void defaultTransitionAndExceptionHandlerKeepStateAndEffects() throws Exception {
		compileFull("Example", """
				public class Example {
				  public static int example() {
				    int state = 1, count = 0;
				    while (true) {
				      try { switch(state) {
				        case 1: count++; state = -7; break;
				        case 2: return -100;
				        default: throw new IllegalArgumentException();
				      } } catch (IllegalArgumentException e) { return state + count; }
				    }
				  }
				}
				""");
		assertEquals(-6, invokeStatic("Example", "example", "Example"));
		var result = newApplier(6).transformJvm(PLAN); assertTrue(result.getTransformerFailures().isEmpty()); result.apply();
		assertEquals(-6, invokeStatic("Example", "example", "Example"));
	}
}
