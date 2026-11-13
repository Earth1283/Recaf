package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class DeadCodeRemovingTransformerTest {
	@Test
	void removesLongDeadBlockAndRepairsLocalVariableLabels() throws Exception {
		ClassNode owner = new ClassNode();
		MethodNode method = new MethodNode(ACC_STATIC, "run", "()V", null, null);
		LabelNode deadStart = new LabelNode();
		LabelNode live = new LabelNode();
		LabelNode end = new LabelNode();
		method.instructions.add(new JumpInsnNode(GOTO, live));
		method.instructions.add(deadStart);
		for (int i = 0; i < 20_000; i++)
			method.instructions.add(new InsnNode(NOP));
		method.instructions.add(live);
		method.instructions.add(new InsnNode(RETURN));
		method.instructions.add(end);
		method.localVariables.add(new LocalVariableNode("value", "I", null, deadStart, end, 0));

		DeadCodeRemovingTransformer transformer = new DeadCodeRemovingTransformer();
		assertTrue(transformer.prune(owner, method));
		assertFalse(method.instructions.contains(deadStart));
		assertTrue(method.instructions.contains(live));
		assertTrue(method.instructions.contains(end));
		for (AbstractInsnNode insn : method.instructions)
			assertNotEquals(NOP, insn.getOpcode());
		for (LocalVariableNode variable : method.localVariables) {
			assertTrue(method.instructions.contains(variable.start));
			assertTrue(method.instructions.contains(variable.end));
		}
		assertFalse(transformer.prune(owner, method), "Repeated cleanup must converge");
	}

	@Test
	void retainsReachableCatchCodeAndProtectedBoundaryLabels() throws Exception {
		ClassNode owner = new ClassNode();
		MethodNode method = new MethodNode(ACC_STATIC, "run", "()V", null, null);
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		method.instructions.add(start);
		method.instructions.add(new InsnNode(ACONST_NULL));
		method.instructions.add(new InsnNode(ATHROW));
		method.instructions.add(new InsnNode(NOP));
		method.instructions.add(end);
		method.instructions.add(handler);
		method.instructions.add(new InsnNode(POP));
		method.instructions.add(new InsnNode(RETURN));
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

		assertTrue(new DeadCodeRemovingTransformer().prune(owner, method));
		assertEquals(1, method.tryCatchBlocks.size());
		assertTrue(method.instructions.contains(start));
		assertTrue(method.instructions.contains(end));
		assertTrue(method.instructions.contains(handler));
		assertEquals(POP, handler.getNext().getOpcode());
	}
}
