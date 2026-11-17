package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.ReadableWorkspaceService;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.skid.SkidInvokeDynamicResolver;
import software.coley.recaf.services.deobfuscation.transform.specific.skid.SkidXorInliningTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;
import software.coley.recaf.test.TestClassUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class SkidfuscatorDeobfuscationTest extends TransformerTestBase {
	@Test
	void inlinesUnknownIntAndLongXorOperandsAndPreservesEvaluationOrder() {
		compile("""
				static int count;
				private static int hidden(int a, int b) { return a ^ b; }
				private static long wide(long a, long b) { return a ^ b; }
				private static synchronized int locked(int a, int b) { return a ^ b; }
				public static int visible(int a, int b) { return a ^ b; }
				private static int different(int a, int b) { return a ^ a; }
				static int next() { return ++count; }
				static long run(int a, long b) { return hidden(a, 123) + wide(b, 456L); }
				static int sideEffects() { return hidden(next(), next()) * 10 + count; }
				static int retained(int a, int b) { return locked(a,b) + visible(a,b) + different(a,b); }
				static long entry() { return run(7, 13L); }
				""");
		Object before = invokeStatic(CLASS_NAME, "entry", CLASS_NAME);
		assertEquals(32, invokeStatic(CLASS_NAME, "sideEffects", CLASS_NAME));
		transformAndApply(List.of(SkidXorInliningTransformer.class));
		assertEquals(before, invokeStatic(CLASS_NAME, "entry", CLASS_NAME));
		assertEquals(32, invokeStatic(CLASS_NAME, "sideEffects", CLASS_NAME));
		ClassNode node = read(get(CLASS_NAME));
		MethodNode run = method(node, "run");
		assertTrue(java.util.Arrays.stream(run.instructions.toArray()).anyMatch(i -> i.getOpcode() == IXOR));
		assertTrue(java.util.Arrays.stream(run.instructions.toArray()).anyMatch(i -> i.getOpcode() == LXOR));
		assertEquals(3, java.util.Arrays.stream(method(node, "retained").instructions.toArray()).filter(MethodInsnNode.class::isInstance).count());
		assertNotNull(method(node, "hidden")); // Keep declarations for other references.
	}

	@Test
	void resolvesRenamedBootstrapStaticVirtualAndInterfaceTargetsWithEquivalentResults() {
		bootstrapFixture();
		addCalls();
		assertEquals(43, invokeStatic(CLASS_NAME, "staticCall", CLASS_NAME));
		assertEquals(3, invokeStatic(CLASS_NAME, "virtualCall", CLASS_NAME));
		assertEquals(0, invokeStatic(CLASS_NAME, "interfaceCall", CLASS_NAME));
		assertEquals(43, invokeStatic(CLASS_NAME, "privateCall", CLASS_NAME));
		JvmClassInfo original = get(CLASS_NAME);
		var result = assertDoesNotThrow(() -> newApplier().transformJvm(List.of(InvokeDynamicInliningTransformer.class)));
		assertTrue(result.getTransformerFailures().isEmpty(), result.getTransformerFailures().toString());
		assertSame(original, get(CLASS_NAME));
		assertEquals(1, result.getTransformedClasses().size());
		result.apply();
		ClassNode node = read(get(CLASS_NAME));
		for (String name : List.of("staticCall", "virtualCall", "interfaceCall", "privateCall"))
			assertFalse(java.util.Arrays.stream(method(node, name).instructions.toArray()).anyMatch(InvokeDynamicInsnNode.class::isInstance));
		assertTrue(java.util.Arrays.stream(method(node, "interfaceCall").instructions.toArray())
				.anyMatch(i -> i instanceof MethodInsnNode call && call.getOpcode() == INVOKEINTERFACE && call.itf));
		assertEquals(43, invokeStatic(CLASS_NAME, "staticCall", CLASS_NAME));
		assertEquals(3, invokeStatic(CLASS_NAME, "virtualCall", CLASS_NAME));
		assertEquals(0, invokeStatic(CLASS_NAME, "interfaceCall", CLASS_NAME));
		assertEquals(43, invokeStatic(CLASS_NAME, "privateCall", CLASS_NAME));
	}

	@Test
	void rejectsModifiedBootstrapControlFlowAndSideEffects() {
		bootstrapFixture();
		ClassNode node = read(get(CLASS_NAME));
		MethodNode bootstrap = method(node, "renamed");
		assertTrue(SkidInvokeDynamicResolver.matchesBootstrap(bootstrap));
		JumpInsnNode jump = (JumpInsnNode) java.util.Arrays.stream(bootstrap.instructions.toArray())
				.filter(JumpInsnNode.class::isInstance).findFirst().orElseThrow();
		jump.setOpcode(IF_ICMPEQ);
		assertFalse(SkidInvokeDynamicResolver.matchesBootstrap(bootstrap));
		jump.setOpcode(IF_ICMPNE);
		bootstrap.instructions.insert(new MethodInsnNode(INVOKESTATIC, "Unknown", "sideEffect", "()V", false));
		assertFalse(SkidInvokeDynamicResolver.matchesBootstrap(bootstrap));
	}

	@Test
	void leavesMalformedMetadataUnknownTargetsAndPrimitiveAdaptationsUnresolved() {
		bootstrapFixture();
		var workspace = workspaceManager.getCurrent();
		SkidInvokeDynamicResolver resolver = new SkidInvokeDynamicResolver();
		var context = new JvmTransformerContext(workspace, workspace.getPrimaryResource(), List.of());
		resolver.setup(context, workspace);
		ClassNode node = read(get(CLASS_NAME));
		MethodNode caller = method(node, "target");
		for (InvokeDynamicInsnNode site : List.of(
				indy("(I)I", INVOKESTATIC, CLASS_NAME, "missing", "(I)I"),
				indy("(I)I", INVOKESPECIAL, CLASS_NAME, "target", "(I)I"),
				indy("(J)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I"),
				indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "invalid"),
				indy("(I)I", INVOKESTATIC, CLASS_NAME, "<init>", "(I)I"),
				new InvokeDynamicInsnNode("x", "(I)I", handle(), "184", CLASS_NAME, "target", "(I)I")))
			assertNull(resolver.resolveWithoutFrame(context, workspace, node, caller, site));
		assertNotNull(resolver.resolveWithoutFrame(context, workspace, node, caller, indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I")));
		// A second preview/workspace using the shared resolver must not replace this run's metadata.
		var other = new BasicWorkspace(new WorkspaceResourceBuilder().build());
		try {
			resolver.setup(new JvmTransformerContext(other, other.getPrimaryResource(), List.of()), other);
			assertNotNull(resolver.resolveWithoutFrame(context, workspace, node, caller, indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I")));
		} finally { other.close(); }
	}

	@Test
	void preservesBootstrapClassInitializationEffects() {
		bootstrapFixture();
		var workspace = workspaceManager.getCurrent();
		mutate(node -> {
			MethodNode initializer = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
			initializer.visitInsn(RETURN); initializer.visitMaxs(0, 0); node.methods.add(initializer);
		});
		SkidInvokeDynamicResolver resolver = new SkidInvokeDynamicResolver();
		var context = new JvmTransformerContext(workspace, workspace.getPrimaryResource(), List.of());
		resolver.setup(context, workspace);
		ClassNode node = read(get(CLASS_NAME));
		assertNull(resolver.resolveWithoutFrame(context, workspace, node, method(node, "target"), indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I")));
	}

	@Test
	void readableCopyUsesTheNewPassesWithOriginalReferenceClassesAttached() throws Exception {
		bootstrapFixture(); addCalls();
		var workspace = workspaceManager.getCurrent();
		JvmClassInfo original = get(CLASS_NAME);
		var copy = recaf.get(ReadableWorkspaceService.class).build(workspace, workspace.getPrimaryResource(), 20_000, false, () -> false, _ -> {});
		try {
			assertSame(original, get(CLASS_NAME));
			ClassNode node = read(copy.workspace().getPrimaryResource().getJvmClassBundle().get(CLASS_NAME));
			for (String name : List.of("staticCall", "virtualCall", "interfaceCall", "privateCall"))
				assertFalse(java.util.Arrays.stream(method(node, name).instructions.toArray()).anyMatch(InvokeDynamicInsnNode.class::isInstance));
			assertTrue(copy.report().contains("Transformation warnings: 0"), copy.report());
		} finally { copy.workspace().close(); }
	}

	@Test
	void rejectsAmbiguousMultiReleaseBootstrapDefinitions() {
		bootstrapFixture();
		JvmClassInfo original = get(CLASS_NAME);
		var version = new BasicVersionedJvmClassBundle(17); version.initialPut(original);
		var primary = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(original))
				.withVersionedJvmClassBundles(new java.util.TreeMap<>(java.util.Map.of(17, version))).build();
		var view = new BasicWorkspace(primary);
		try {
			var context = new JvmTransformerContext(view, primary, List.of());
			var resolver = new SkidInvokeDynamicResolver(); resolver.setup(context, view);
			ClassNode node = read(original);
			assertNull(resolver.resolveWithoutFrame(context, view, node, method(node, "target"), indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I")));
		} finally { view.close(); }
	}

	private void bootstrapFixture() {
		// Linkage structure documented by Skidfuscator's official TestReferenceDispatcher fixture.
		compileFull(CLASS_NAME, """
				import java.lang.invoke.*;
				public class Example {
				    public static int target(int value) { return value + 1; }
				    private int secret(int value) { return value + 2; }
				    public static Object renamed(MethodHandles.Lookup lookup, String unused, MethodType site,
				            Object opcode, Object owner, Object name, Object desc) throws Exception {
				        Class<?> type = Class.forName((String) owner);
				        ClassLoader loader = type.getClassLoader();
				        MethodType targetType = MethodType.fromMethodDescriptorString((String) desc, loader);
				        return (Integer) opcode == 184
				                ? new MutableCallSite(lookup.findStatic(type, (String) name, targetType).asType(site))
				                : new MutableCallSite(lookup.findVirtual(type, (String) name, targetType).asType(site));
				    }
				}
				""");
	}

	private void addCalls() {
		mutate(node -> {
			MethodNode stat = new MethodNode(ACC_PUBLIC | ACC_STATIC, "staticCall", "()I", null, null);
			stat.visitIntInsn(BIPUSH, 42); stat.instructions.add(indy("(I)I", INVOKESTATIC, CLASS_NAME, "target", "(I)I"));
			stat.visitInsn(IRETURN); stat.visitMaxs(1, 0); node.methods.add(stat);
			MethodNode virtual = new MethodNode(ACC_PUBLIC | ACC_STATIC, "virtualCall", "()I", null, null);
			virtual.visitLdcInsn("abc"); virtual.instructions.add(indy("(Ljava/lang/Object;)I", INVOKEVIRTUAL, "java.lang.String", "length", "()I"));
			virtual.visitInsn(IRETURN); virtual.visitMaxs(1, 0); node.methods.add(virtual);
			MethodNode itf = new MethodNode(ACC_PUBLIC | ACC_STATIC, "interfaceCall", "()I", null, null);
			itf.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
			itf.instructions.add(indy("(Ljava/util/List;)I", INVOKEINTERFACE, "java.util.List", "size", "()I"));
			itf.visitInsn(IRETURN); itf.visitMaxs(1, 0); node.methods.add(itf);
			MethodNode priv = new MethodNode(ACC_PUBLIC | ACC_STATIC, "privateCall", "()I", null, null);
			priv.visitTypeInsn(NEW, CLASS_NAME); priv.visitInsn(DUP); priv.visitMethodInsn(INVOKESPECIAL, CLASS_NAME, "<init>", "()V", false);
			priv.visitIntInsn(BIPUSH, 41); priv.instructions.add(indy("(Ljava/lang/Object;I)I", INVOKESPECIAL, CLASS_NAME, "secret", "(I)I"));
			priv.visitInsn(IRETURN); priv.visitMaxs(2, 0); node.methods.add(priv);
		});
	}

	private void mutate(Consumer<ClassNode> mutation) {
		ClassNode node = read(get(CLASS_NAME)); mutation.accept(node);
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		workspaceManager.getCurrent().getPrimaryResource().getJvmClassBundle().put(new JvmClassInfoBuilder(writer.toByteArray()).build());
	}
	private static Handle handle() { return new Handle(H_INVOKESTATIC, CLASS_NAME, "renamed", SkidInvokeDynamicResolver.BOOTSTRAP_DESCRIPTOR, false); }
	private static InvokeDynamicInsnNode indy(String site, int opcode, String owner, String name, String desc) {
		return new InvokeDynamicInsnNode("hidden", site, handle(), opcode, owner, name, desc);
	}
	private static ClassNode read(JvmClassInfo info) { ClassNode node = new ClassNode(); info.getClassReader().accept(node, 0); return node; }
	private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
}
