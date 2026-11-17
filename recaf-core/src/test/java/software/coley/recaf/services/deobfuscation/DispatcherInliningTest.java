package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import software.coley.recaf.services.deobfuscation.transform.generic.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherInliningTest extends TransformerTestBase {
	private static final List<Class<? extends software.coley.recaf.services.transform.JvmClassTransformer>> PLAN = List.of(
			DispatcherInliningTransformer.class, OpaquePredicateFoldingTransformer.class, DeadCodeRemovingTransformer.class,
			GotoInliningTransformer.class, VariableFoldingTransformer.class);

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
