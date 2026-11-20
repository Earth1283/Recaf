package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.services.deobfuscation.transform.generic.EmptyControlFlowRemovingTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class EmptyControlFlowRemovingTransformerTest extends TransformerTestBase {
	private static final List<Class<? extends JvmClassTransformer>> PLAN = List.of(EmptyControlFlowRemovingTransformer.class);

	@Test
	void removesEmptyIfsButStillEvaluatesConditionsAndCatchesExceptions() throws Exception {
		compileFull(CLASS_NAME, """
				public class Example {
				    static int calls;
				    static boolean tick() { calls++; return true; }
				    static int next() { return ++calls; }
				    static int run(boolean flag, int divisor) {
				        if (flag) {}
				        if (tick()) {} else {}
				        if (next() == next()) {}
				        try {
				            if (1 / divisor > 0) {}
				            return 10;
				        } catch (ArithmeticException exception) {
				            return 20;
				        }
				    }
				    public static int example() { return run(true, 1) + run(false, 0) + calls; }
				}
				""");
		assertEquals(36, invokeExample());
		assertTrue(opcodes("run").contains(IFEQ));
		apply();
		assertEquals(36, invokeExample());
		List<Integer> instructions = opcodes("run");
		assertTrue(instructions.containsAll(List.of(INVOKESTATIC, IDIV, POP, POP2)));
		assertTrue(instructions.stream().noneMatch(op -> op >= IFEQ && op <= IF_ACMPNE));
		assertTrue(newApplier().transformJvm(PLAN).getTransformedClasses().isEmpty(), "Cleanup must converge");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void keepsWorkInTheNonEmptyArmOfEitherClause(int flag) throws Exception {
		assemble("""
				.method public static example ()I {
				    code: {
				    Start:
				        iconst_0
				        istore result
				        iconst_%d
				        ifeq ElseWork
				        goto FirstJoin
				    ElseWork:
				        iinc result 1
				    FirstJoin:
				        iconst_%d
				        ifeq EmptyElse
				        iinc result 10
				        goto End
				    EmptyElse:
				        nop
				    End:
				        iload result
				        ireturn
				    }
				}
				""".formatted(flag, flag), false);
		assertEquals(flag == 0 ? 1 : 10, invokeExample());
		apply();
		assertEquals(flag == 0 ? 1 : 10, invokeExample());
		List<Integer> instructions = opcodes("example");
		assertTrue(instructions.containsAll(List.of(IFEQ, IFNE)));
		assertFalse(instructions.contains(GOTO));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void removesEmptySwitchWithSeparateTargetsAndKeepsSelectorEvaluation(boolean table) throws Exception {
		String select = table ? "tableswitch { min: 1, max: 1, cases: { Empty }, default: Join }" :
				"lookupswitch { 1: Empty, default: Join }";
		assemble("""
				.method public static example ()I {
				    code: {
				    Start:
				        ldc Ljava/lang/Object;
				        invokestatic java/lang/System.identityHashCode (Ljava/lang/Object;)I
				        %s
				    Empty:
				        nop
				        goto Join
				    Join:
				        bipush 9
				        ireturn
				    }
				}
				""".formatted(select), false);
		assertEquals(9, invokeExample());
		apply();
		assertEquals(9, invokeExample());
		assertFalse(opcodes("example").contains(table ? TABLESWITCH : LOOKUPSWITCH));
		assertTrue(opcodes("example").containsAll(List.of(INVOKESTATIC, POP)));
	}

	@Test
	void removesEmptyLoopsWhenLiteralTestsPreventRepetition() throws Exception {
		assemble("""
				.method public static example ()I {
				    code: {
				    TopTest:
				        iconst_0
				        ifeq BottomTest
				        nop
				        goto TopTest
				    BottomTest:
				        nop
				        iconst_0
				        ifne BottomTest
				        bipush 7
				        ireturn
				    }
				}
				""", false);
		assertEquals(7, invokeExample());
		apply();
		assertEquals(7, invokeExample());
		assertFalse(opcodes("example").stream().anyMatch(op -> op == GOTO || op == IFEQ || op == IFNE));
	}

	@Test
	void preservesEmptyLoopWithObservableRepeatedCondition() throws Exception {
		compileFull(CLASS_NAME, """
				public class Example {
				    static int calls;
				    static boolean tick() { return ++calls < 4; }
				    public static int example() {
				        while (tick()) {}
				        return calls;
				    }
				}
				""");
		assertEquals(4, invokeExample());
		JvmTransformResult result = newApplier().transformJvm(PLAN);
		assertTrue(result.getTransformerFailures().isEmpty());
		assertTrue(result.getTransformedClasses().isEmpty());
	}

	@Test
	void leavesDistinctGotoCyclesIntact() {
		validateNoTransformation("""
				.method public static example ()V {
				    code: {
				    Start:
				        iconst_0
				        ifeq Right
				    Left:
				        goto Left
				    Right:
				        goto Right
				    }
				}
				""", PLAN);
	}

	private Object invokeExample() {
		return invokeStatic(CLASS_NAME, "example", CLASS_NAME);
	}

	private void apply() throws Exception {
		JvmTransformResult result = newApplier(4).transformJvm(PLAN);
		assertTrue(result.getTransformerFailures().isEmpty(), () -> result.getTransformerFailures().toString());
		assertEquals(1, result.getTransformedClasses().size());
		result.apply();
	}

	private List<Integer> opcodes(String methodName) {
		ClassNode node = new ClassNode();
		get(CLASS_NAME).getClassReader().accept(node, 0);
		var method = node.methods.stream().filter(candidate -> candidate.name.equals(methodName)).findFirst().orElseThrow();
		return Arrays.stream(method.instructions.toArray()).map(AbstractInsnNode::getOpcode).toList();
	}
}
