package software.coley.recaf.ui.pane.editing.assembler;

import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.backend.dalvik.DalvikTargetContext;
import me.darknet.assembler.backend.dalvik.compile.DalvikCompiler;
import me.darknet.assembler.backend.dalvik.compile.DalvikCompilerOptions;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.backend.jvm.compile.JvmCompiler;
import me.darknet.assembler.backend.jvm.compile.JvmCompilerOptions;
import me.darknet.assembler.backend.jvm.compile.analysis.jvm.ValuedJvmAnalysisEngine;
import me.darknet.assembler.parser.DeclarationParser;
import me.darknet.assembler.parser.Tokenizer;
import me.darknet.assembler.parser.processor.ASTProcessor;
import me.darknet.assembler.processing.SemanticProcessor;
import me.darknet.assembler.target.TargetContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssemblerVariableValuesTest {
	@Test
	void resolvesJvmStoreLoadAndIncrementWithoutUsingTheCaret() {
		String source = """
				.super java/lang/Object
				.class public Example {
				    .method public static count (I)I {
				        parameters: { input },
				        code: {
				            iload input
				            pop
				            bipush 42
				            istore count
				            iinc count 1
				            iload count
				            ireturn
				        }
				    }
				}
				""";
		TargetContext target = JvmTargetContext.INSTANCE;
		List<ASTElement> ast = parse(source, target);
		var result = new JvmCompiler().compile(SemanticProcessor.process(ast, target).requireValue(),
				new JvmCompilerOptions().withEngineProvider(ValuedJvmAnalysisEngine::new)).requireValue();
		assertEquals("42", AssemblerVariableValues.resolve(ast, result, source.indexOf("istore count") + 7).value());
		assertEquals("43", AssemblerVariableValues.resolve(ast, result, source.indexOf("iinc count") + 5).value());
		assertEquals("43", AssemblerVariableValues.resolve(ast, result, source.indexOf("iload count") + 6).value());
		assertNull(AssemblerVariableValues.resolve(ast, result, source.indexOf("iload input") + 6));
		assertNull(AssemblerVariableValues.resolve(ast, result, source.indexOf("iload count")));
	}

	@Test
	void resolvesDalvikRegisterAtItsReadAndWrite() {
		String source = """
				.super Ljava/lang/Object;
				.class public Example {
				    .method public static count ()I {
				        registers: 1,
				        code: {
				            const local 42
				            return local
				        }
				    }
				}
				""";
		TargetContext target = DalvikTargetContext.INSTANCE;
		List<ASTElement> ast = parse(source, target);
		var result = new DalvikCompiler().compile(SemanticProcessor.process(ast, target).requireValue(),
				new DalvikCompilerOptions()).requireValue();
		assertEquals("42", AssemblerVariableValues.resolve(ast, result, source.indexOf("local")).value());
		assertEquals("42", AssemblerVariableValues.resolve(ast, result, source.lastIndexOf("local")).value());
		assertNull(AssemblerVariableValues.resolve(ast, result, source.indexOf("return")));
	}

	private static List<ASTElement> parse(String source, TargetContext target) {
		var tokens = new Tokenizer().tokenize("<test>", source).requireValue();
		var declarations = new DeclarationParser().parseDeclarations(tokens).requireValue();
		return new ASTProcessor(target).processAST(declarations).requireValue();
	}
}
