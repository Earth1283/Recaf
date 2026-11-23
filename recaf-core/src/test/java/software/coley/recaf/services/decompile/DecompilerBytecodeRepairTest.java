package software.coley.recaf.services.decompile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.util.bytecode.TryCatchRepairUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class DecompilerBytecodeRepairTest extends TestBase {
	static DecompilerManager decompilerManager;
	static DecompilerManagerConfig config;
	DecompilerManagerConfig previousConfig;

	@BeforeAll
	static void setup() {
		decompilerManager = recaf.get(DecompilerManager.class);
	}

	@BeforeEach
	void resetConfig() {
		config = new DecompilerManagerConfig();
		config.getCacheDecompilations().setValue(false);
		assertDoesNotThrow(() -> {
			Field field = DecompilerManager.class.getDeclaredField("config");
			Object target = unwrapProxy(decompilerManager);
			previousConfig = ReflectUtil.quietGet(target, field);
			ReflectUtil.quietSet(target, field, config);
		});
	}

	@AfterEach
	void restoreConfig() {
		if (previousConfig != null) {
			assertDoesNotThrow(() -> ReflectUtil.quietSet(unwrapProxy(decompilerManager),
					DecompilerManager.class.getDeclaredField("config"),
					previousConfig));
		}
	}

	@Test
	void repairsOverlappingIdentityRethrowTryCatchesForVineflowerAndCfr() throws Exception {
		JvmClassInfo cls = TestClassUtils.createClass("demo/ZkmLikeRethrow", node -> {
			MethodNode id = new MethodNode(ACC_PRIVATE | ACC_STATIC, "a", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", null, null);
			id.visitVarInsn(ALOAD, 0);
			id.visitInsn(ARETURN);
			id.visitMaxs(1, 1);
			node.methods.add(id);

			MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "compute", "(I)I", null, null);
			Label s0 = new Label(), e0 = new Label(), h0 = new Label();
			Label s1 = new Label(), e1 = new Label(), h1 = new Label();
			Label join = new Label();
			// Overlapping try-catch blocks with identity rethrow handlers
			m.visitTryCatchBlock(s0, e0, h0, "java/lang/RuntimeException");
			m.visitTryCatchBlock(s1, e1, h1, "java/lang/RuntimeException");
			m.visitLabel(s0);
			m.visitVarInsn(ILOAD, 0);
			m.visitJumpInsn(IFEQ, s1);
			m.visitLabel(e0);
			m.visitInsn(ICONST_1);
			m.visitJumpInsn(GOTO, join);
			m.visitLabel(h0);
			m.visitMethodInsn(INVOKESTATIC, "demo/ZkmLikeRethrow", "a", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", false);
			m.visitInsn(ATHROW);
			m.visitLabel(s1);
			m.visitInsn(ICONST_2);
			m.visitLabel(e1);
			m.visitJumpInsn(GOTO, join);
			m.visitLabel(h1);
			m.visitMethodInsn(INVOKESTATIC, "demo/ZkmLikeRethrow", "a", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", false);
			m.visitInsn(ATHROW);
			m.visitLabel(join);
			m.visitInsn(IRETURN);
			m.visitMaxs(2, 1);
			node.methods.add(m);
		});

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(cls));
		JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(vf);
		assertNotNull(cfr);

		DecompileResult vfRes = decompilerManager.decompile(vf, ws, cls).get(10, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, vfRes.getType());
		assertTrue(vfRes.getDiagnostics().isEmpty(), () -> "Unexpected VF diagnostics: " + vfRes.getDiagnostics());
		assertTrue(vfRes.getText().contains("return 1") || vfRes.getText().contains("compute"));
		assertFalse(vfRes.getText().contains("throw a("));

		DecompileResult cfrRes = decompilerManager.decompile(cfr, ws, cls).get(10, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, cfrRes.getType());
		assertTrue(cfrRes.getDiagnostics().isEmpty(), () -> "Unexpected CFR diagnostics: " + cfrRes.getDiagnostics());
		assertFalse(cfrRes.getText().contains("throw a("), () -> "Unexpected rethrow call in CFR output:\n" + cfrRes.getText());
		assertTrue(cfrRes.getText().contains("? 1 : 2") || cfrRes.getText().contains("return 1"),
				() -> "Expected simplified compute method in CFR output:\n" + cfrRes.getText());
	}

	@Test
	void preservesRealSynchronizedMonitorExitSelfCatchWhileDroppingEmptyTryCatches() {
		ClassNode cn = new ClassNode();
		cn.version = V17;
		cn.access = ACC_PUBLIC;
		cn.name = "demo/SyncAndEmpty";
		cn.superName = "java/lang/Object";

		MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "sync", "(Ljava/lang/Object;)V", null, null);
		Label emptyS = new Label(), emptyE = new Label(), emptyH = new Label();
		Label tryS = new Label(), tryE = new Label(), catchH = new Label(), catchEnd = new Label(), done = new Label();
		// Empty/inverted try-catch block (should be removed)
		m.visitTryCatchBlock(emptyE, emptyS, emptyH, "java/lang/Throwable");
		// Real javac-style synchronized self-catching block around MONITOREXIT (must be preserved!)
		m.visitTryCatchBlock(tryS, tryE, catchH, null);
		m.visitTryCatchBlock(catchH, catchEnd, catchH, null);

		m.visitVarInsn(ALOAD, 0);
		m.visitInsn(DUP);
		m.visitVarInsn(ASTORE, 1);
		m.visitInsn(MONITORENTER);
		m.visitLabel(tryS);
		m.visitLabel(emptyS);
		m.visitLabel(emptyE);
		m.visitVarInsn(ALOAD, 1);
		m.visitInsn(MONITOREXIT);
		m.visitLabel(tryE);
		m.visitJumpInsn(GOTO, done);

		m.visitLabel(catchH);
		m.visitVarInsn(ASTORE, 2);
		m.visitVarInsn(ALOAD, 1);
		m.visitInsn(MONITOREXIT);
		m.visitLabel(catchEnd);
		m.visitVarInsn(ALOAD, 2);
		m.visitInsn(ATHROW);

		m.visitLabel(emptyH);
		m.visitInsn(ATHROW);
		m.visitLabel(done);
		m.visitInsn(RETURN);
		m.visitMaxs(2, 3);
		cn.methods.add(m);

		assertTrue(TryCatchRepairUtil.repairTryCatches(cn));
		assertEquals(2, m.tryCatchBlocks.size(), "Expected synchronized try-catch blocks to be preserved");
	}

	@Test
	void repairsMalformedLocalVariablesWithoutStrippingValidNames() {
		ClassNode cn = new ClassNode();
		cn.version = V17;
		cn.access = ACC_PUBLIC;
		cn.name = "demo/LvtRepair";
		cn.superName = "java/lang/Object";

		MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "work", "(I)I", null, null);
		Label start = new Label(), mid = new Label(), end = new Label(), orphan = new Label();
		m.visitLabel(start);
		m.visitVarInsn(ILOAD, 0);
		m.visitInsn(ICONST_1);
		m.visitInsn(IADD);
		m.visitVarInsn(ISTORE, 1);
		m.visitLabel(mid);
		m.visitVarInsn(ILOAD, 1);
		m.visitInsn(IRETURN);
		m.visitLabel(end);
		m.visitMaxs(2, 2);

		// Valid entry
		m.visitLocalVariable("goodVar", "I", null, start, end, 1);
		// Inverted range
		m.visitLocalVariable("inverted", "I", null, end, start, 1);
		// Missing label
		m.visitLocalVariable("missing", "I", null, orphan, end, 1);
		// Void descriptor
		m.visitLocalVariable("voidVar", "V", null, start, end, 0);
		// Overlapping incompatible type on slot 1
		m.visitLocalVariable("clashObj", "Ljava/lang/String;", null, start, mid, 1);
		cn.methods.add(m);

		assertTrue(TryCatchRepairUtil.repairLocalVariables(cn));
		assertEquals(1, m.localVariables.size());
		assertEquals("goodVar", m.localVariables.getFirst().name);
	}

	@Test
	void cacheInvalidatesWhenFilterTogglesChange() throws Exception {
		config.getCacheDecompilations().setValue(true);
		JvmClassInfo cls = TestClassUtils.createEmptyClass("demo/CacheTarget");
		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(cls));
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(cfr);

		DecompileResult r1 = decompilerManager.decompile(cfr, ws, cls).get(5, TimeUnit.SECONDS);
		DecompileResult r2 = decompilerManager.decompile(cfr, ws, cls).get(5, TimeUnit.SECONDS);
		assertSame(r1, r2);

		config.getFilterRepairTryCatch().setValue(false);
		DecompileResult r3 = decompilerManager.decompile(cfr, ws, cls).get(5, TimeUnit.SECONDS);
		assertNotSame(r1, r3);

		config.getFilterRemoveConstantDefinitions().setValue(true);
		DecompileResult r4 = decompilerManager.decompile(cfr, ws, cls).get(5, TimeUnit.SECONDS);
		assertNotSame(r3, r4);
	}

	@Test
	void removeConstantDefinitionsFilterOnlyAffectsDecompiledJavaNotBytecode() throws Exception {
		JvmClassInfo cls = TestClassUtils.createClass("demo/JavaOnlyConstCleanup", node -> {
			node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "PRIVATE_SECRET", "I", null, 42));
			node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "PUBLIC_KEEP", "I", null, 7));
			node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "serialVersionUID", "J", null, 1L));

			MethodNode clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
			node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "STATIC_INIT_CONST", "Ljava/lang/String;", null, null));
			clinit.visitLdcInsn("secret-token");
			clinit.visitFieldInsn(PUTSTATIC, "demo/JavaOnlyConstCleanup", "STATIC_INIT_CONST", "Ljava/lang/String;");
			clinit.visitInsn(RETURN);
			clinit.visitMaxs(1, 0);
			node.methods.add(clinit);

			MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "compute", "()Ljava/lang/String;", null, null);
			Label l0 = new Label(), l1 = new Label();
			m.visitLabel(l0);
			m.visitIntInsn(BIPUSH, 10);
			m.visitVarInsn(ISTORE, 0);
			m.visitIntInsn(BIPUSH, -3);
			m.visitVarInsn(ISTORE, 1);
			m.visitFieldInsn(GETSTATIC, "demo/JavaOnlyConstCleanup", "STATIC_INIT_CONST", "Ljava/lang/String;");
			m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
			m.visitVarInsn(ILOAD, 0);
			m.visitInsn(IADD);
			m.visitVarInsn(ILOAD, 1);
			m.visitInsn(IADD);
			m.visitFieldInsn(GETSTATIC, "demo/JavaOnlyConstCleanup", "PRIVATE_SECRET", "I");
			m.visitInsn(IADD);
			m.visitFieldInsn(GETSTATIC, "demo/JavaOnlyConstCleanup", "PUBLIC_KEEP", "I");
			m.visitInsn(IADD);
			m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
			m.visitLabel(l1);
			m.visitInsn(ARETURN);
			m.visitLocalVariable("localConst", "I", null, l0, l1, 0);
			m.visitLocalVariable("negConst", "I", null, l0, l1, 1);
			m.visitMaxs(3, 2);
			node.methods.add(m);
		});

		byte[] originalBytecode = cls.getBytecode().clone();
		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(cls));
		JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(vf);

		// Default (false): constant definitions remain in decompiled Java
		DecompileResult defaultRes = decompilerManager.decompile(vf, ws, cls).get(10, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, defaultRes.getType());
		assertTrue(defaultRes.getText().contains("STATIC_INIT_CONST"), () -> defaultRes.getText());
		assertTrue(defaultRes.getText().contains("PUBLIC_KEEP"), () -> defaultRes.getText());

		// Enabled (true): propagates constants and strips dead private static/local constant definitions in Java only
		config.getFilterRemoveConstantDefinitions().setValue(true);
		DecompileResult filteredRes = decompilerManager.decompile(vf, ws, cls).get(10, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, filteredRes.getType());
		String filtered = filteredRes.getText();
		assertFalse(filtered.contains("STATIC_INIT_CONST"), () -> "Expected private static constant field & static init assignment removed:\n" + filtered);
		assertFalse(filtered.contains("PRIVATE_SECRET"), () -> "Expected private static final constant field removed:\n" + filtered);
		assertFalse(filtered.contains("localConst"), () -> "Expected dead constant local removed:\n" + filtered);
		assertFalse(filtered.contains("negConst"), () -> "Expected dead negative constant local removed:\n" + filtered);
		assertTrue(filtered.contains("PUBLIC_KEEP"), () -> "Expected public constant field preserved:\n" + filtered);
		assertTrue(filtered.contains("serialVersionUID"), () -> "Expected serialVersionUID preserved:\n" + filtered);
		assertTrue(filtered.contains("\"secret-token\""), () -> "Expected propagated string literal:\n" + filtered);

		// Class bytecode in workspace must remain completely untouched
		assertArrayEquals(originalBytecode, cls.getBytecode());
	}
}
