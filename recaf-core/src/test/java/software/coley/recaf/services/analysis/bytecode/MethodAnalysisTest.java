package software.coley.recaf.services.analysis.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.MethodAnalysis.DefinitionKind.*;

class MethodAnalysisTest {
	@Test
	void slicesOnlyTheSelectedLocalOrStackValueAtTheObservationSite() throws Exception {
		var method = new MethodNode(ACC_STATIC, "combine", "(II)I", null, null);
		method.visitVarInsn(ILOAD, 0);
		method.visitVarInsn(ILOAD, 1);
		method.visitInsn(IADD);
		method.visitInsn(IRETURN);
		method.visitMaxs(2, 2);

		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		assertEquals(List.of(0, 1, 2), result.backwardSlice(2).instructions());
		assertEquals(List.of(1, 2), result.backwardSliceStack(2, 1).instructions());
		assertEquals(List.of(new MethodAnalysis.Definition(PARAMETER, 1)), result.backwardSliceStack(2, 1).entryDefinitions());
		assertEquals(List.of(2), result.backwardSliceLocal(2, 0).instructions());
		assertEquals(List.of(new MethodAnalysis.Definition(PARAMETER, 0)), result.backwardSliceLocal(2, 0).entryDefinitions());
		assertFalse(result.backwardSliceLocal(2, 0).dependsOnInitialHeap());
		assertThrows(IllegalArgumentException.class, () -> result.backwardSliceStack(2, 2));
	}

	@Test
	void sliceIncludesLoopCarriedMemoryWritesAndOpaqueCalls() throws Exception {
		var method = new MethodNode(ACC_STATIC, "heap", "([II)I", null, null);
		var loop = new Label();
		var write = new Label();
		method.visitLabel(loop);
		method.visitVarInsn(ILOAD, 1);
		method.visitJumpInsn(IFNE, write);
		method.visitVarInsn(ALOAD, 0);
		method.visitInsn(ICONST_0);
		method.visitInsn(IALOAD);
		method.visitInsn(IRETURN);
		method.visitLabel(write);
		method.visitVarInsn(ALOAD, 0);
		method.visitInsn(ICONST_0);
		method.visitInsn(ICONST_2);
		method.visitInsn(IASTORE);
		method.visitMethodInsn(INVOKESTATIC, "Fixture", "mutate", "()V", false);
		method.visitIincInsn(1, -1);
		method.visitJumpInsn(GOTO, loop);
		method.visitMaxs(3, 2);

		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		var slice = result.backwardSliceStack(5, 0);
		assertTrue(slice.dependsOnInitialHeap());
		assertTrue(slice.instructions().containsAll(List.of(4, 5, 6, 7, 8, 9, 10)));
		assertEquals(slice, result.summary().returnSlice());
	}

	@Test
	void computesReachingDefinitionsDominanceAbstractValuesAndSlicesAtAJoin() throws Exception {
		var method = new MethodNode(ACC_STATIC, "diamond", "(I)I", null, null);
		var alternate = new Label();
		var join = new Label();
		method.visitVarInsn(ILOAD, 0);
		method.visitJumpInsn(IFEQ, alternate);
		method.visitInsn(ICONST_2);
		method.visitVarInsn(ISTORE, 1);
		method.visitJumpInsn(GOTO, join);
		method.visitLabel(alternate);
		method.visitInsn(ICONST_2);
		method.visitVarInsn(ISTORE, 1);
		method.visitLabel(join);
		method.visitVarInsn(ILOAD, 1);
		method.visitInsn(ICONST_3);
		method.visitInsn(IADD);
		method.visitInsn(IRETURN);
		method.visitInsn(ICONST_0);
		method.visitInsn(IRETURN);
		method.visitMaxs(2, 2);

		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		var merge = result.instructions().get(7);
		var firstStore = new MethodAnalysis.Definition(INSTRUCTION, 3);
		var secondStore = new MethodAnalysis.Definition(INSTRUCTION, 6);
		var parameter = new MethodAnalysis.Definition(PARAMETER, 0);
		assertEquals(1, merge.immediateDominator());
		assertEquals(7, result.instructions().get(1).immediatePostDominator());
		assertEquals(List.of(1), result.instructions().get(2).controlDependencies());
		assertTrue(merge.controlDependencies().isEmpty());
		assertEquals(List.of(firstStore, secondStore), merge.inputs());
		assertEquals(List.of(firstStore, secondStore), merge.locals().getLast().definitions());
		assertEquals("I:2", merge.locals().getLast().value());
		assertEquals(List.of(7), result.uses().get(firstStore));
		assertEquals(List.of(7), result.uses().get(secondStore));
		assertEquals(List.of(0), result.uses().get(parameter));
		assertEquals(List.of(parameter), result.backwardSlice(10).entryDefinitions());
		assertEquals(List.of(0, 1, 2, 3, 5, 6, 7, 8, 9, 10), result.backwardSlice(10).instructions());
		assertEquals(List.of("I:5"), result.summary().returnValues());
		assertEquals(List.of(10), result.summary().returns());
		assertFalse(result.instructions().get(11).reachable());
	}

	@Test
	void tracksHandlerInputsAndEscapingExceptionsInSlicesAndSummaries() throws Exception {
		var method = new MethodNode(ACC_STATIC, "guarded", "(I)I", null, null);
		var start = new Label();
		var end = new Label();
		var caught = new Label();
		var catchAll = new Label();
		method.visitTryCatchBlock(start, end, caught, "java/lang/Exception");
		method.visitTryCatchBlock(start, end, catchAll, null);
		method.visitLabel(start);
		method.visitVarInsn(ILOAD, 0);
		method.visitMethodInsn(INVOKESTATIC, "Fixture", "callee", "(I)I", false);
		method.visitInsn(IRETURN);
		method.visitLabel(end);
		method.visitLabel(caught);
		method.visitVarInsn(ASTORE, 1);
		method.visitInsn(ICONST_M1);
		method.visitInsn(IRETURN);
		method.visitLabel(catchAll);
		method.visitVarInsn(ASTORE, 1);
		method.visitVarInsn(ALOAD, 1);
		method.visitInsn(ATHROW);
		method.visitMaxs(1, 2);

		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		var caughtValue = new MethodAnalysis.Definition(CAUGHT_EXCEPTION, 3);
		assertEquals(List.of(new MethodAnalysis.Handler(3, "java/lang/Exception"), new MethodAnalysis.Handler(6, "java/lang/Throwable")),
				result.instructions().get(1).handlers());
		assertFalse(result.instructions().get(1).mayEscape());
		assertTrue(result.instructions().get(0).handlers().isEmpty());
		assertEquals(List.of(caughtValue), result.instructions().get(3).inputs());
		assertEquals(List.of(3), result.uses().get(caughtValue));
		assertEquals(1, result.instructions().get(3).immediateDominator());
		assertEquals(MethodAnalysis.METHOD_EXIT, result.instructions().get(1).immediatePostDominator());
		assertEquals(List.of(0, 1, 3), result.backwardSlice(3).instructions());
		assertEquals(List.of(new MethodAnalysis.Definition(PARAMETER, 0), caughtValue), result.backwardSlice(3).entryDefinitions());
		assertEquals(List.of("Fixture.callee(I)I"), result.summary().calls());
		assertEquals(List.of(8), result.summary().explicitThrows());
		assertEquals(List.of(8), result.summary().escapingExceptionSites());
	}

	@Test
	void filtersHandlersForArithmeticAndCapturesDirectEffects() throws Exception {
		var method = new MethodNode(ACC_STATIC, "divide", "(I)I", null, null);
		var start = new Label();
		var end = new Label();
		var wrong = new Label();
		var right = new Label();
		method.visitTryCatchBlock(start, end, wrong, "java/lang/NullPointerException");
		method.visitTryCatchBlock(start, end, right, "java/lang/ArithmeticException");
		method.visitFieldInsn(GETSTATIC, "Fixture", "counter", "I");
		method.visitVarInsn(ILOAD, 0);
		method.visitLabel(start);
		method.visitInsn(IDIV);
		method.visitLabel(end);
		method.visitInsn(DUP);
		method.visitFieldInsn(PUTSTATIC, "Fixture", "result", "I");
		method.visitInsn(IRETURN);
		method.visitLabel(wrong);
		method.visitInsn(POP);
		method.visitInsn(ICONST_1);
		method.visitInsn(IRETURN);
		method.visitLabel(right);
		method.visitInsn(POP);
		method.visitInsn(ICONST_0);
		method.visitInsn(IRETURN);
		method.visitMaxs(2, 1);

		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		assertEquals(List.of(new MethodAnalysis.Handler(9, "java/lang/ArithmeticException")), result.instructions().get(2).handlers());
		assertFalse(result.instructions().get(2).mayEscape());
		assertFalse(result.instructions().get(6).reachable());
		assertTrue(result.instructions().get(9).reachable());
		assertEquals(List.of(new MethodAnalysis.Definition(CAUGHT_EXCEPTION, 9)), result.instructions().get(9).inputs());
		assertEquals(List.of("Fixture.counter I"), result.summary().fieldReads());
		assertEquals(List.of("Fixture.result I"), result.summary().fieldWrites());
		assertEquals(List.of(0, 4), result.summary().escapingExceptionSites());
	}

	@Test
	void handlesCyclesAndCancellation() throws Exception {
		var method = new MethodNode(ACC_STATIC, "loop", "()V", null, null);
		var loop = new Label();
		method.visitInsn(ICONST_0);
		method.visitVarInsn(ISTORE, 0);
		method.visitLabel(loop);
		method.visitIincInsn(0, 1);
		method.visitJumpInsn(GOTO, loop);
		method.visitMaxs(1, 1);
		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		assertEquals(4, result.nonTerminatingInstructions());
		assertTrue(result.summary().returns().isEmpty());
		assertEquals(MethodAnalysis.NO_PARENT, result.instructions().get(2).immediatePostDominator());
		assertEquals(List.of(new MethodAnalysis.Definition(INSTRUCTION, 1), new MethodAnalysis.Definition(INSTRUCTION, 2)),
				result.instructions().get(2).inputs());
		assertEquals(List.of(0, 1, 2), result.backwardSlice(2).instructions());
		assertThrows(CancellationException.class, () -> MethodAnalysis.analyze("Fixture", method, () -> true));
	}

	@Test
	void slicesGuardedInstructionsInANonTerminatingRegion() throws Exception {
		var method = new MethodNode(ACC_STATIC, "loop", "(I)V", null, null);
		var head = new Label();
		var other = new Label();
		method.visitLabel(head);
		method.visitVarInsn(ILOAD, 0);
		method.visitJumpInsn(IFEQ, other);
		method.visitInsn(NOP);
		method.visitJumpInsn(GOTO, head);
		method.visitLabel(other);
		method.visitInsn(NOP);
		method.visitJumpInsn(GOTO, head);
		method.visitMaxs(1, 1);
		var result = MethodAnalysis.analyze("Fixture", method, () -> false);
		assertEquals(6, result.nonTerminatingInstructions());
		assertEquals(List.of(1), result.instructions().get(2).controlDependencies());
		assertEquals(List.of(0, 1, 2), result.backwardSlice(2).instructions());
	}
}
