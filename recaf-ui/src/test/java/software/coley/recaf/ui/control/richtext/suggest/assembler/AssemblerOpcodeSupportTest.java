package software.coley.recaf.ui.control.richtext.suggest.assembler;

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
import me.darknet.assembler.query.AssemblyUtils;
import me.darknet.assembler.target.TargetContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssemblerOpcodeSupportTest {
	@Test
	void documentsOperandsAndEffectsInTheActiveDialect() {
		var jvm = AssemblerOpcodeHints.get(JvmTargetContext.INSTANCE, "iadd");
		assertNotNull(jvm);
		assertEquals("iadd", jvm.syntax());
		assertTrue(jvm.effect().contains("int, int → int"));
		assertNull(AssemblerOpcodeHints.get(DalvikTargetContext.INSTANCE, "iadd"));

		var dalvik = AssemblerOpcodeHints.get(DalvikTargetContext.INSTANCE, "move-object");
		assertNotNull(dalvik);
		assertTrue(dalvik.syntax().contains("destination register, source register"));
		assertTrue(dalvik.effect().contains("Read sources, write destination"));
	}

	@Test
	void showsHintsInPartiallyEditedCodeAndSkipsLabels() {
		String source = """
				.method sample ()V {
				    code: {
				    nop:
				        iadd
				        unfinished
				    }
				}
				""";
		var target = JvmTargetContext.INSTANCE;
		assertNotNull(AssemblerOpcodeHints.at(target, source, source.indexOf("iadd") + 1));
		assertNull(AssemblerOpcodeHints.at(target, source, source.indexOf("nop")));
		assertNull(AssemblerOpcodeHints.at(target, source, source.indexOf("unfinished")));
	}

	@Test
	void displaysTheMemberDescriptorInJavaTypeNotation() {
		String source = """
				.method sample ()V {
				    code: {
				        invokestatic java/lang/String.valueOf (I)Ljava/lang/String;
				        return
				    }
				}
				""";
		var target = JvmTargetContext.INSTANCE;
		var instruction = AssemblyUtils.findInstructionAt(parse(source, target), source.indexOf("invokestatic") + 1);
		assertNotNull(instruction);
		assertEquals("java.lang.String.valueOf(int) String", AssemblerOpcodeHints.memberSignature(target, instruction));
	}

	@Test
	void ranksJvmOpcodesUsingTheStackWhileTheMnemonicIsIncomplete() {
		String source = """
				.super java/lang/Object
				.class public Sample {
				    .method public static sample ()J {
				        code: {
				            lconst_0
				            lconst_1
				            ladd
				            lreturn
				        }
				    }
				}
				""";
		var target = JvmTargetContext.INSTANCE;
		var ast = parse(source, target);
		var result = new JvmCompiler().compile(SemanticProcessor.process(ast, target).requireValue(),
				new JvmCompilerOptions().withEngineProvider(ValuedJvmAnalysisEngine::new)).requireValue();
		var analysis = new AssemblerOpcodeAnalysis(source, ast, result);
		String edited = source.replace("ladd", "l");
		var rank = analysis.ranking(edited, edited.indexOf("l\n") + 1);
		assertTrue(rank.applyAsInt("ladd") < rank.applyAsInt("iadd"));
		assertTrue(rank.applyAsInt("ladd") < rank.applyAsInt("lshl"));

		String inserted = source.replace("            ladd", "            l\n            ladd");
		var insertedRank = analysis.ranking(inserted, inserted.indexOf("l\n") + 1);
		assertTrue(insertedRank.applyAsInt("ladd") < insertedRank.applyAsInt("iadd"));

		String changed = edited.replace("lconst_1", "iconst_1");
		var staleRank = analysis.ranking(changed, changed.indexOf("l\n") + 1);
		assertEquals(0, staleRank.applyAsInt("ladd"));
	}

	@Test
	void ranksDalvikMovesUsingTheAvailableRegisterTypes() {
		String source = """
				.super Ljava/lang/Object;
				.class public Sample {
				    .method public static sample ()J {
				        registers: 2,
				        code: {
				            const-wide v0 42L
				            return-wide v0
				        }
				    }
				}
				""";
		var target = DalvikTargetContext.INSTANCE;
		var ast = parse(source, target);
		var result = new DalvikCompiler().compile(SemanticProcessor.process(ast, target).requireValue(),
				new DalvikCompilerOptions()).requireValue();
		String edited = source.replace("return-wide v0", "move");
		var rank = new AssemblerOpcodeAnalysis(source, ast, result).ranking(edited, edited.indexOf("move") + 4);
		assertTrue(rank.applyAsInt("move-wide") < rank.applyAsInt("move-object"));
		assertTrue(rank.applyAsInt("return-wide") < rank.applyAsInt("return-object"));
	}

	private static List<ASTElement> parse(String source, TargetContext target) {
		var tokens = new Tokenizer().tokenize("<test>", source).requireValue();
		var declarations = new DeclarationParser().parseDeclarations(tokens).requireValue();
		return new ASTProcessor(target).processAST(declarations).requireValue();
	}
}
