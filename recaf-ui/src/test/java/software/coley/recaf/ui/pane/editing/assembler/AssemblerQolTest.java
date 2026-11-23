package software.coley.recaf.ui.pane.editing.assembler;

import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.parser.DeclarationParser;
import me.darknet.assembler.parser.Tokenizer;
import me.darknet.assembler.parser.processor.ASTProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssemblerQolTest {
	@Test
	void toggleLineCommentHandlesSingleAndMultiLineSelections() {
		String source = "    iconst_1\n    iconst_2\n    iadd";
		var commented = AssemblerEditingActions.toggleLineComment(source, 4, source.length() - 1);
		assertEquals("    // iconst_1\n    // iconst_2\n    // iadd", commented.text());

		var uncommented = AssemblerEditingActions.toggleLineComment(commented.text(), 0, commented.text().length());
		assertEquals(source, uncommented.text());

		// Mixed commented/uncommented should comment all lines uniformly.
		String mixed = "    // iconst_1\n    iconst_2";
		var allCommented = AssemblerEditingActions.toggleLineComment(mixed, 0, mixed.length());
		assertEquals("    // // iconst_1\n    // iconst_2", allCommented.text());
	}

	@Test
	void duplicateSelectionOrLine() {
		String source = "    iconst_1\n    ireturn";
		var lineDup = AssemblerEditingActions.duplicateSelectionOrLine(source, 6, 6);
		assertEquals("    iconst_1\n    iconst_1\n    ireturn", lineDup.text());

		var selDup = AssemblerEditingActions.duplicateSelectionOrLine("iconst_1", 0, 8);
		assertEquals("iconst_1iconst_1", selDup.text());
	}

	@Test
	void moveLinesUpAndDownClampsAtBoundaries() {
		String source = "line0\nline1\nline2";
		var movedDown = AssemblerEditingActions.moveLines(source, 0, 4, false);
		assertEquals("line1\nline0\nline2", movedDown.text());

		var movedUp = AssemblerEditingActions.moveLines(movedDown.text(), movedDown.anchor(), movedDown.caret(), true);
		assertEquals(source, movedUp.text());

		var clampedTop = AssemblerEditingActions.moveLines(source, 0, 0, true);
		assertEquals(source, clampedTop.text());

		var clampedBottom = AssemblerEditingActions.moveLines(source, source.length(), source.length(), false);
		assertEquals(source, clampedBottom.text());
	}

	@Test
	void replaceInstructionsWithNopSkipsLabelsDirectivesAndComments() {
		String source = """
				.method public static foo ()V {
				    code: {
				    L0:
				        // keep comment
				        iconst_1
				        pop // keep trailing comment
				        return
				    }
				}""";
		var edit = AssemblerEditingActions.replaceInstructionsWithNop(source, 0, source.length());
		assertTrue(edit.text().contains(".method public static foo ()V {"));
		assertTrue(edit.text().contains("L0:"));
		assertTrue(edit.text().contains("// keep comment"));
		assertFalse(edit.text().contains("iconst_1"));
		assertTrue(edit.text().contains("        nop // keep trailing comment"));
		assertFalse(edit.text().contains("        return"));
	}

	@Test
	void prepareSnippetStripsMethodBoilerplateAndMatchesIndent() {
		String target = """
				code: {
				A:
				    iconst_1
				}""";
		int iconstCaret = target.indexOf("iconst_1");
		String compiled = """
				.method public static generated ()V {
				    code: {
				    gA:
				        getstatic java/lang/System.out Ljava/io/PrintStream;
				        ldc "hi"
				        invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V
				    gB:
				        return
				    }
				}""";
		String prepared = AssemblerEditingActions.prepareSnippetForInsertion(target, iconstCaret, compiled);
		assertFalse(prepared.contains(".method "));
		assertTrue(prepared.contains("getstatic java/lang/System.out"), () -> prepared);
		assertTrue(prepared.lines().filter(l -> !l.isBlank()).allMatch(l -> l.startsWith("    ")), () -> prepared);
	}

	@Test
	void navigationResolvesLabelsTypesMethodsAndFields() {
		String asm = """
				.super demo/Base
				.class public demo/Sample {
				    .method public static run ()V {
				        code: {
				        A:
				            new demo/Widget
				            dup
				            invokespecial demo/Widget.<init> ()V
				            getstatic demo/Holder.count I
				            ifeq B
				        B:
				            return
				        }
				    }
				}""";
		var tokens = new Tokenizer().tokenize("<test>", asm);
		assertTrue(tokens.isSuccess());
		var decls = new DeclarationParser().parseDeclarations(tokens.requireValue());
		assertTrue(decls.isSuccess());
		var parsed = new ASTProcessor(JvmTargetContext.INSTANCE).processAST(decls.requireValue());
		assertTrue(parsed.isSuccess(), () -> "AST parse failed: " + parsed.errors());
		List<ASTElement> ast = parsed.requireValue();

		assertInstanceOf(AssemblerNavigation.TypeTarget.class,
				AssemblerNavigation.resolve(ast, JvmTargetContext.INSTANCE, asm.indexOf("demo/Base") + 2));
		assertInstanceOf(AssemblerNavigation.TypeTarget.class,
				AssemblerNavigation.resolve(ast, JvmTargetContext.INSTANCE, asm.indexOf("demo/Widget") + 2));
		assertInstanceOf(AssemblerNavigation.MemberTarget.class,
				AssemblerNavigation.resolve(ast, JvmTargetContext.INSTANCE, asm.indexOf("invokespecial") + 2));
		assertInstanceOf(AssemblerNavigation.MemberTarget.class,
				AssemblerNavigation.resolve(ast, JvmTargetContext.INSTANCE, asm.indexOf("getstatic") + 2));
		var labelTarget = AssemblerNavigation.resolve(ast, JvmTargetContext.INSTANCE, asm.indexOf("ifeq B") + 5);
		assertInstanceOf(AssemblerNavigation.LabelTarget.class, labelTarget);
		assertEquals("B", ((AssemblerNavigation.LabelTarget) labelTarget).labelName());
	}
}
