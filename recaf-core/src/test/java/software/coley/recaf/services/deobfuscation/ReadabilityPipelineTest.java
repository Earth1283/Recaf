package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.bytecode.ReadableWorkspaceService;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticStringBootstrapResolver;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.workspace.model.Workspace;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;
import static org.mockito.Mockito.*;

class ReadabilityPipelineTest extends TransformerTestBase {
	private static final String NAME = "sample/Ψ";
	private static final String BSM = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/Object;";
	private static final String TARGET = "(I)Ljava/lang/String;";

	@Test
	void provesFixedTargetAndPreservesBehavior() throws Exception {
		putFixture(_ -> {});
		assertEquals("permission.read", invokeStatic(NAME, "run", NAME));
		Workspace workspace = workspaceManager.getCurrent();
		var resource = workspace.getPrimaryResource();
		var bundle = resource.getJvmClassBundle();
		JvmClassInfo original = get(NAME);
		var transformer = new InvokeDynamicInliningTransformer(recaf.get(InheritanceGraphService.class), List.of(new StaticStringBootstrapResolver()));
		var context = new JvmTransformerContext(workspace, resource, List.of(transformer));
		transformer.setup(context, workspace);
		transformer.transform(context, workspace, resource, bundle, original);
		ClassNode changed = context.getNode(bundle, original);
		MethodNode run = changed.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
		assertFalse(Arrays.stream(run.instructions.toArray()).anyMatch(InvokeDynamicInsnNode.class::isInstance));
		assertTrue(Arrays.stream(run.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("a")));
		transformAndApply(List.of(InvokeDynamicInliningTransformer.class));
		assertEquals("permission.read", invokeStatic(NAME, "run", NAME));
	}

	@Test
	void leavesSideEffectsAdaptiveTypesAndExtraBootstrapArgumentsUnresolved() {
		putClass("sample/Other", node -> {
			MethodNode target = new MethodNode(ACC_PUBLIC | ACC_STATIC, "a", TARGET, null, null);
			target.visitLdcInsn("other"); target.visitInsn(ARETURN); target.visitMaxs(1, 1); node.methods.add(target);
		});
		for (Consumer<MethodNode> mutation : List.<Consumer<MethodNode>>of(
				method -> { method.instructions.insert(new FieldInsnNode(PUTSTATIC, NAME, "sideEffect", "I")); method.instructions.insert(new InsnNode(ICONST_1)); },
				method -> method.instructions.insert(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "gc", "()V")),
				method -> { method.instructions.insert(new VarInsnNode(ASTORE, 2)); method.instructions.insert(new LdcInsnNode(Type.getMethodType(TARGET))); },
				method -> { for (AbstractInsnNode insn : method.instructions) if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type) ldc.cst = Type.getObjectType("sample/Other"); })) {
			putFixture(mutation);
			assertUnresolved(new Object[0]);
		}
		putFixture(_ -> {});
		assertUnresolved(new Object[] {"unexpected bootstrap argument"});
	}

	@Test
	void buildsIndependentReadableCopyWithCollisionFreeAliasesAndOriginReport() throws Exception {
		putFixture(_ -> {});
		putClass("sample/ReadableClass_0001", null);
		Workspace source = workspaceManager.getCurrent();
		byte[] original = get(NAME).getBytecode().clone();
		var result = recaf.get(ReadableWorkspaceService.class).build(source, source.getPrimaryResource(), 10000, true, () -> false, _ -> {});
		assertArrayEquals(original, get(NAME).getBytecode());
		assertEquals(1, result.dynamicStringsBefore());
		assertEquals(0, result.dynamicStringsAfter());
		assertEquals("sample/ReadableClass_0002", result.classAliases().get(NAME));
		assertTrue(result.report().contains("Transformation warnings: 0"), result.report());
		var primary = result.workspace().getPrimaryResource();
		assertNotNull(primary.getFileBundle().get("RECAF-ALIASES.tsv"));
		assertNotNull(primary.getFileBundle().get("RECAF-READABILITY.txt"));
		JvmClassInfo readable = primary.getJvmClassBundle().get("sample/ReadableClass_0002");
		assertNotNull(readable.getDeclaredMethod("x", "()V"), "Virtual API names must be preserved");
		assertTrue(decompile(readable).contains("permission.read"));
		ClassNode readableNode = new ClassNode(); readable.getClassReader().accept(readableNode, 0);
		MethodNode readableRun = readableNode.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
		assertTrue(Arrays.stream(readableRun.instructions.toArray()).anyMatch(insn -> insn instanceof LdcInsnNode ldc && "permission.read".equals(ldc.cst)),
				"The supported decoder path must become plaintext despite the unsupported unused branch");
		source.close();
		assertEquals(2, primary.getJvmClassBundle().size());
		assertNotNull(result.workspace().getSupportingResources().getFirst().getJvmClassBundle().get(NAME));
		result.workspace().close();
	}

	@Test
	void reportsModifiedClassesThatCouldNotBeWritten() throws Exception {
		putFixture(_ -> {});
		Workspace source = workspaceManager.getCurrent();
		TransformationApplierService factory = mock(TransformationApplierService.class);
		TransformationApplier applier = mock(TransformationApplier.class);
		var copiedWorkspace = new java.util.concurrent.atomic.AtomicReference<Workspace>();
		when(factory.newApplier(any())).thenAnswer(call -> { copiedWorkspace.set(call.getArgument(0)); return applier; });
		when(applier.transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class))).thenAnswer(_ -> {
			Workspace workspace = copiedWorkspace.get();
			var resource = workspace.getPrimaryResource();
			var bundle = resource.getJvmClassBundle();
			var path = PathNodes.classPath(workspace, resource, bundle, bundle.get(NAME));
			JvmTransformResult result = mock(JvmTransformResult.class);
			when(result.getModifiedClassesPerTransformer()).thenReturn(Map.of(OpaqueConstantFoldingTransformer.class, List.of(path)));
			when(result.getTransformerFailures()).thenReturn(Map.of());
			when(result.getTransformedClasses()).thenReturn(Map.of());
			return result;
		});
		var result = new ReadableWorkspaceService(factory).build(source, source.getPrimaryResource(), 1000, false, () -> false, _ -> {});
		assertTrue(result.report().contains("Transformation warnings: 3"), result.report());
		assertTrue(result.report().contains("Modified bytecode could not be emitted; previous bytecode retained."));
		assertArrayEquals(get(NAME).getBytecode(), result.workspace().getPrimaryResource().getJvmClassBundle().get(NAME).getBytecode());
		result.workspace().close();
	}

	@Test
	void cancelledBuildDoesNotTouchSource() {
		putFixture(_ -> {});
		Workspace source = workspaceManager.getCurrent();
		JvmClassInfo original = get(NAME);
		assertThrows(CancellationException.class, () -> recaf.get(ReadableWorkspaceService.class)
				.build(source, source.getPrimaryResource(), 10000, true, () -> true, _ -> {}));
		assertSame(original, get(NAME));
	}

	private void assertUnresolved(Object[] arguments) {
		Workspace workspace = workspaceManager.getCurrent();
		var resolver = new StaticStringBootstrapResolver();
		var context = new JvmTransformerContext(workspace, workspace.getPrimaryResource(), List.of());
		resolver.setup(context, workspace);
		ClassNode node = context.getNode(workspace.getPrimaryResource().getJvmClassBundle(), get(NAME));
		MethodNode run = node.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
		var call = new InvokeDynamicInsnNode("value", TARGET, new Handle(H_INVOKESTATIC, NAME, "b", BSM, false), arguments);
		assertNull(resolver.resolveWithoutFrame(context, workspace, node, run, call));
	}

	private void putFixture(Consumer<MethodNode> mutation) {
		putClass(NAME, node -> {
			node.visitField(ACC_PUBLIC | ACC_STATIC, "sideEffect", "I", null, null).visitEnd();
			node.visitField(ACC_PRIVATE | ACC_STATIC, "z", "[Ljava/lang/String;", null, null).visitEnd();
			MethodNode target = new MethodNode(ACC_PRIVATE | ACC_STATIC, "a", TARGET, null, null);
			LabelNode unsupported = new LabelNode();
			target.instructions.add(new VarInsnNode(ILOAD, 0)); target.instructions.add(new IntInsnNode(BIPUSH, 7));
			target.instructions.add(new JumpInsnNode(IF_ICMPNE, unsupported));
			target.visitLdcInsn("permission.read"); target.visitInsn(ARETURN);
			target.instructions.add(unsupported); target.visitFrame(F_SAME, 0, null, 0, null); target.visitLdcInsn("PATH");
			target.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false);
			target.visitInsn(ARETURN); target.visitMaxs(2, 1); node.methods.add(target);
			MethodNode virtual = new MethodNode(ACC_PUBLIC, "x", "()V", null, null);
			virtual.visitInsn(RETURN); virtual.visitMaxs(0, 1); node.methods.add(virtual);
			MethodNode bootstrap = new MethodNode(ACC_PUBLIC | ACC_STATIC, "b", BSM, null, null);
			bootstrap.visitTypeInsn(NEW, "java/lang/invoke/MutableCallSite"); bootstrap.visitInsn(DUP);
			bootstrap.visitVarInsn(ALOAD, 0); bootstrap.visitLdcInsn(Type.getObjectType(NAME)); bootstrap.visitLdcInsn("a");
			bootstrap.visitLdcInsn(TARGET); bootstrap.visitLdcInsn(Type.getObjectType(NAME));
			bootstrap.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
			bootstrap.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodType", "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false);
			bootstrap.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
			bootstrap.visitVarInsn(ALOAD, 2);
			bootstrap.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
			bootstrap.visitMethodInsn(INVOKESPECIAL, "java/lang/invoke/MutableCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
			bootstrap.visitInsn(ARETURN); bootstrap.visitMaxs(8, 3); mutation.accept(bootstrap); node.methods.add(bootstrap);
			MethodNode run = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "()Ljava/lang/String;", null, null);
			run.visitIntInsn(BIPUSH, 7);
			run.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "reverse", "(I)I", false);
			run.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "reverse", "(I)I", false);
			run.visitInvokeDynamicInsn("value", TARGET, new Handle(H_INVOKESTATIC, NAME, "b", BSM, false));
			run.visitInsn(ARETURN); run.visitMaxs(1, 0); node.methods.add(run);
		});
	}
}
