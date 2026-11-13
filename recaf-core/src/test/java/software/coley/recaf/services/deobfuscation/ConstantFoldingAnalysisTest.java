package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.util.ClassDefiner;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.objectweb.asm.Opcodes.*;

class ConstantFoldingAnalysisTest {
	@Test
	void reusesFramesWhenStackPassDoesNotChangeInstructions() throws Exception {
		check(method -> {
			method.visitVarInsn(ILOAD, 0);
			method.visitInsn(ICONST_2);
			method.visitInsn(IADD);
		}, 1, 9);
	}

	@Test
	void recomputesFramesAfterReplacingStackInstructions() throws Exception {
		check(method -> {
			method.visitVarInsn(ILOAD, 0);
			method.visitInsn(DUP);
			method.visitInsn(IADD);
		}, 2, 14);
	}

	@Test
	void recomputesFramesAfterInsertingStackInstructions() throws Exception {
		check(method -> {
			method.visitInsn(ICONST_1);
			method.visitInsn(ICONST_2);
			method.visitInsn(DUP_X1);
			method.visitInsn(IADD);
			method.visitInsn(IADD);
		}, 2, 5);
	}

	private static void check(Consumer<MethodVisitor> body, int expectedAnalyses, int expectedValue) throws Exception {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(V17, ACC_PUBLIC, "Example", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
		method.visitCode();
		body.accept(method);
		method.visitInsn(IRETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		JvmClassInfo info = new JvmClassInfoBuilder(writer.toByteArray()).build();
		BasicJvmClassBundle bundle = new BasicJvmClassBundle();
		bundle.initialPut(info);
		WorkspaceResource resource = new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build();
		Workspace workspace = new BasicWorkspace(resource);
		InheritanceGraph graph = new InheritanceGraph(workspace);
		InheritanceGraphService graphs = mock(InheritanceGraphService.class);
		when(graphs.getOrCreateInheritanceGraph(workspace)).thenReturn(graph);
		OpaqueConstantFoldingTransformer transformer = new OpaqueConstantFoldingTransformer(graphs);
		CountingContext context = new CountingContext(workspace, resource, transformer);
		transformer.setup(context, workspace);
		transformer.transform(context, workspace, resource, bundle, info);

		assertEquals(expectedAnalyses, context.analyses);
		byte[] output = context.getBytecode(bundle, info);
		Class<?> transformed = new ClassDefiner(Map.of("Example", output)).loadClass("Example");
		assertEquals(expectedValue, transformed.getMethod("run", int.class).invoke(null, 7));
	}

	private static class CountingContext extends JvmTransformerContext {
		private int analyses;

		private CountingContext(Workspace workspace, WorkspaceResource resource, OpaqueConstantFoldingTransformer transformer) {
			super(workspace, resource, transformer);
		}

		@Override
		public Frame<ReValue>[] analyze(InheritanceGraph graph, ClassNode cls, MethodNode method) throws TransformationException {
			analyses++;
			return super.analyze(graph, cls, method);
		}
	}
}
