package software.coley.recaf.services.decompile;

import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.cfr.SinkFactoryImpl;
import software.coley.recaf.services.decompile.fallback.MethodBytecodeReport;
import software.coley.recaf.services.decompile.vineflower.VineflowerConfig;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestClassUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class DecompilerFailureTest {
	@Test
	void inspectionUsesTheSameInstructionIndicesAcrossViews() {
		JvmClassInfo info = TestClassUtils.createClass("demo/Regions", node -> {
			MethodNode method = new MethodNode(ACC_STATIC, "select", "(I)I", null, null);
			Label start = new Label();
			Label branch = new Label();
			Label end = new Label();
			Label handler = new Label();
			method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
			method.visitTryCatchBlock(start, end, handler, null);
			method.visitLabel(start);
			method.visitVarInsn(ILOAD, 0);
			method.visitTableSwitchInsn(1, 1, end, branch);
			method.visitLabel(branch);
			method.visitInsn(ICONST_1);
			method.visitInsn(IRETURN);
			method.visitLabel(end);
			method.visitInsn(ICONST_0);
			method.visitInsn(IRETURN);
			method.visitLabel(handler);
			method.visitInsn(POP);
			method.visitInsn(ICONST_M1);
			method.visitInsn(IRETURN);
			method.visitMaxs(1, 1);
			node.methods.add(method);
		});
		MethodBytecodeReport report = MethodBytecodeReport.inspect(info, info.getDeclaredMethod("select", "(I)I"));
		assertTrue(report.instructions().contains("@1  TABLESWITCH"), report.instructions());
		assertTrue(report.instructions().contains("1: @2"), report.instructions());
		assertTrue(report.controlFlow().contains("case 1 → @2"), report.controlFlow());
		assertTrue(report.controlFlow().contains("default → @4"), report.controlFlow());
		assertEquals("[@0, @4) → @6  java/lang/RuntimeException\n[@0, @4) → @6  catch-all\n", report.exceptionRegions());
		assertTrue(report.controlFlow().contains(report.exceptionRegions()));
		assertTrue(report.diagnostics().contains("9 of 9 instructions are reachable"), report.diagnostics());
	}

	@Test
	void invalidStackStillHasInstructionsAndControlFlow() {
		JvmClassInfo info = invalidStackClass();
		MethodBytecodeReport report = MethodBytecodeReport.inspect(info, info.getDeclaredMethod("broken", "()I"));
		assertTrue(report.instructions().contains("@0  IRETURN"));
		assertTrue(report.controlFlow().contains("Return"));
		assertTrue(report.diagnostics().contains("Frame analysis failed at @0"), report.diagnostics());
	}

	@Test
	void vineflowerCarriesMethodFailureAlongsideSource() {
		JvmClassInfo broken = invalidStackClass();
		JvmClassInfo normal = TestClassUtils.createEmptyClass("demo/Normal");
		var workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(broken, normal));
		var vineflower = new VineflowerDecompiler(new VineflowerConfig());
		DecompileResult result = vineflower.decompile(workspace, broken);
		assertEquals(DecompileResult.ResultType.SUCCESS, result.getType());
		assertTrue(result.getText().contains("int broken()"));
		var method = new DecompileDiagnostic.Method(broken.getName(), "broken", "()I");
		assertTrue(result.getDiagnostics().stream().anyMatch(diagnostic -> method.equals(diagnostic.method())));
		assertEquals(result.getDiagnostics(), result.withText("filtered").getDiagnostics());
		assertTrue(vineflower.decompile(workspace, normal).getDiagnostics().isEmpty());
	}

	@Test
	void cfrClassFailureRetainsTheReportedException() {
		var sink = new SinkFactoryImpl();
		var type = OutputSinkFactory.SinkType.EXCEPTION;
		var format = OutputSinkFactory.SinkClass.EXCEPTION_MESSAGE;
		assertEquals(List.of(format), sink.getSupportedSinks(type, List.of(format, OutputSinkFactory.SinkClass.STRING)));
		Exception failure = new IllegalStateException("test failure");
		sink.<SinkReturns.ExceptionMessage>getSink(type, format).write(new SinkReturns.ExceptionMessage() {
			@Override
			public String getPath() { return "demo/Broken"; }

			@Override
			public String getMessage() { return failure.getMessage(); }

			@Override
			public Exception getThrownException() { return failure; }
		});
		assertSame(failure, sink.getException());
	}

	private static JvmClassInfo invalidStackClass() {
		return TestClassUtils.createClass("demo/Broken", node -> {
			MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "broken", "()I", null, null);
			method.visitInsn(IRETURN);
			method.visitMaxs(1, 0);
			node.methods.add(method);
		});
	}
}
