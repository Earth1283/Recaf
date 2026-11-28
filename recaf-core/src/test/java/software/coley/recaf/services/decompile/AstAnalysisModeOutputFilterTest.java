package software.coley.recaf.services.decompile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class AstAnalysisModeOutputFilterTest extends TestBase {
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
	void cacheInvalidatesWhenAstAnalysisModeToggles() throws Exception {
		config.getCacheDecompilations().setValue(true);
		JvmClassInfo cls = TestClassUtils.createEmptyClass("demo/AstCacheTarget");
		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(cls));
		JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		assertNotNull(vf);

		DecompileResult r1 = decompilerManager.decompile(vf, ws, cls).get(5, TimeUnit.SECONDS);
		config.getFilterAstAnalysisMode().setValue(true);
		DecompileResult r2 = decompilerManager.decompile(vf, ws, cls).get(5, TimeUnit.SECONDS);
		assertNotSame(r1, r2);
	}

	@Test
	void astAnalysisModeInlinesCrossClassFunctionsAndHidesUnusedWithoutMutatingBytecode() throws Exception {
		JvmClassInfo helper = TestClassUtils.createClass("demo/Helper", node -> {
			MethodNode version = new MethodNode(ACC_PUBLIC | ACC_STATIC, "version", "()Ljava/lang/String;", null, null);
			version.visitLdcInsn(Type.getObjectType("java/lang/Object"));
			version.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);
			version.visitInsn(POP);
			version.visitLdcInsn("1.4.4");
			version.visitInsn(ARETURN);
			version.visitMaxs(1, 0);
			node.methods.add(version);

			MethodNode keptApi = new MethodNode(ACC_PUBLIC | ACC_STATIC, "keepApi", "(I)I", null, null);
			keptApi.visitVarInsn(ILOAD, 0);
			keptApi.visitInsn(ICONST_1);
			keptApi.visitInsn(IADD);
			keptApi.visitInsn(IRETURN);
			keptApi.visitMaxs(2, 1);
			node.methods.add(keptApi);
		});

		JvmClassInfo caller = TestClassUtils.createClass("demo/Caller", node -> {
			MethodNode z3j = new MethodNode(ACC_PRIVATE | ACC_STATIC, "z3j", "()Ljava/lang/String;", null, null);
			z3j.visitLdcInsn("AIR");
			z3j.visitInsn(ARETURN);
			z3j.visitMaxs(1, 0);
			node.methods.add(z3j);

			MethodNode decoy = new MethodNode(ACC_PRIVATE | ACC_STATIC, "decoy", "()V", null, null);
			decoy.visitInsn(RETURN);
			decoy.visitMaxs(0, 0);
			node.methods.add(decoy);

			MethodNode run = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(I)Ljava/lang/String;", null, null);
			run.visitMethodInsn(INVOKESTATIC, "demo/Caller", "z3j", "()Ljava/lang/String;", false);
			run.visitMethodInsn(INVOKESTATIC, "demo/Helper", "version", "()Ljava/lang/String;", false);
			run.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
			run.visitVarInsn(ILOAD, 0);
			run.visitMethodInsn(INVOKESTATIC, "demo/Helper", "keepApi", "(I)I", false);
			run.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
			run.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
			run.visitInsn(ARETURN);
			run.visitMaxs(3, 1);
			node.methods.add(run);

			// Statement-level call whose return value is discarded must NOT become an invalid standalone literal.
			MethodNode stmt = new MethodNode(ACC_PUBLIC | ACC_STATIC, "stmtKeep", "()V", null, null);
			stmt.visitMethodInsn(INVOKESTATIC, "demo/Caller", "stmtHelper", "()Ljava/lang/String;", false);
			stmt.visitInsn(POP);
			stmt.visitInsn(RETURN);
			stmt.visitMaxs(1, 0);
			node.methods.add(stmt);

			MethodNode stmtHelper = new MethodNode(ACC_PRIVATE | ACC_STATIC, "stmtHelper", "()Ljava/lang/String;", null, null);
			stmtHelper.visitLdcInsn("KEEP_STMT");
			stmtHelper.visitInsn(ARETURN);
			stmtHelper.visitMaxs(1, 0);
			node.methods.add(stmtHelper);
		});

		byte[] callerBytesBefore = caller.getBytecode().clone();
		byte[] helperBytesBefore = helper.getBytecode().clone();

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(caller, helper));
		try {
			JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
			assertNotNull(vf);

			config.getFilterAstAnalysisMode().setValue(true);

			DecompileResult callerRes = decompilerManager.decompile(vf, ws, caller).get(10, TimeUnit.SECONDS);
			assertEquals(DecompileResult.ResultType.SUCCESS, callerRes.getType());
			String callerCode = callerRes.getText();
			assertNotNull(callerCode);
			assertTrue(callerCode.contains("\"AIR\""), () -> "Expected z3j() inlined to \"AIR\":\n" + callerCode);
			assertTrue(callerCode.contains("\"1.4.4\""), () -> "Expected cross-class Helper.version() inlined to \"1.4.4\":\n" + callerCode);
			assertFalse(callerCode.contains("z3j("), () -> "Expected z3j() definition and calls removed:\n" + callerCode);
			assertFalse(callerCode.contains("decoy("), () -> "Expected unused decoy() removed:\n" + callerCode);
			assertFalse(callerCode.contains("Helper.version()"), () -> "Expected Helper.version() call replaced:\n" + callerCode);
			assertTrue(callerCode.contains("stmtHelper()"), () -> "Expected statement-level call preserved as valid Java:\n" + callerCode);

			DecompileResult helperRes = decompilerManager.decompile(vf, ws, helper).get(10, TimeUnit.SECONDS);
			assertEquals(DecompileResult.ResultType.SUCCESS, helperRes.getType());
			String helperCode = helperRes.getText();
			assertNotNull(helperCode);
			assertFalse(helperCode.contains("version()"), () -> "Expected fully-inlined Helper.version() hidden:\n" + helperCode);
			assertTrue(helperCode.contains("keepApi"), () -> "Expected non-constant API method preserved:\n" + helperCode);

			assertArrayEquals(callerBytesBefore, caller.getBytecode());
			assertArrayEquals(helperBytesBefore, helper.getBytecode());
		} finally {
			ws.close();
		}
	}

	@Test
	void astAnalysisModePreservesNestmatePrivateMethodsAndSideEffectingArguments() throws Exception {
		JvmClassInfo host = TestClassUtils.createClass("demo/AstNestHost", node -> {
			node.visitNestMember("demo/AstNestHost$Child");
			node.visitField(ACC_PRIVATE | ACC_STATIC, "counter", "I", null, 0);

			MethodNode secret = new MethodNode(ACC_PRIVATE | ACC_STATIC, "nestUsed", "(I)I", null, null);
			secret.visitVarInsn(ILOAD, 0);
			secret.visitInsn(ICONST_1);
			secret.visitInsn(IADD);
			secret.visitInsn(IRETURN);
			secret.visitMaxs(2, 1);
			node.methods.add(secret);

			MethodNode publicApiConst = new MethodNode(ACC_PUBLIC | ACC_STATIC, "publicApiConst", "()I", null, null);
			publicApiConst.visitIntInsn(BIPUSH, 99);
			publicApiConst.visitInsn(IRETURN);
			publicApiConst.visitMaxs(1, 0);
			node.methods.add(publicApiConst);

			MethodNode withArg = new MethodNode(ACC_PRIVATE | ACC_STATIC, "ignoreArg", "(I)Ljava/lang/String;", null, null);
			withArg.visitLdcInsn("OK");
			withArg.visitInsn(ARETURN);
			withArg.visitMaxs(1, 1);
			node.methods.add(withArg);

			MethodNode sideEffect = new MethodNode(ACC_PUBLIC | ACC_STATIC, "sideEffect", "()I", null, null);
			sideEffect.visitFieldInsn(GETSTATIC, "demo/AstNestHost", "counter", "I");
			sideEffect.visitInsn(ICONST_1);
			sideEffect.visitInsn(IADD);
			sideEffect.visitInsn(DUP);
			sideEffect.visitFieldInsn(PUTSTATIC, "demo/AstNestHost", "counter", "I");
			sideEffect.visitInsn(IRETURN);
			sideEffect.visitMaxs(2, 0);
			node.methods.add(sideEffect);

			MethodNode test = new MethodNode(ACC_PUBLIC | ACC_STATIC, "test", "()Ljava/lang/String;", null, null);
			test.visitMethodInsn(INVOKESTATIC, "demo/AstNestHost", "sideEffect", "()I", false);
			test.visitMethodInsn(INVOKESTATIC, "demo/AstNestHost", "ignoreArg", "(I)Ljava/lang/String;", false);
			test.visitInsn(ARETURN);
			test.visitMaxs(1, 0);
			node.methods.add(test);
		});

		JvmClassInfo child = TestClassUtils.createClass("demo/AstNestHost$Child", node -> {
			node.visitNestHost("demo/AstNestHost");
			MethodNode run = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
			run.visitVarInsn(ILOAD, 0);
			run.visitMethodInsn(INVOKESTATIC, "demo/AstNestHost", "nestUsed", "(I)I", false);
			run.visitInsn(IRETURN);
			run.visitMaxs(1, 1);
			node.methods.add(run);
		});

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(host, child));
		try {
			JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
			assertNotNull(vf);
			config.getFilterAstAnalysisMode().setValue(true);

			DecompileResult hostRes = decompilerManager.decompile(vf, ws, host).get(10, TimeUnit.SECONDS);
			assertEquals(DecompileResult.ResultType.SUCCESS, hostRes.getType());
			String code = hostRes.getText();
			assertNotNull(code);
			assertTrue(code.contains("ignoreArg(sideEffect())"),
					() -> "Call with non-pure sideEffect() argument must NOT be dropped in AST mode:\n" + code);
			assertTrue(code.contains("nestUsed("),
					() -> "Private method referenced by nestmate must NOT be hidden in AST mode:\n" + code);
			assertTrue(code.contains("publicApiConst()"),
					() -> "Zero-caller public constant method must NOT be hidden in AST mode:\n" + code);
		} finally {
			ws.close();
		}
	}
}
