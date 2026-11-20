package software.coley.recaf.ui.pane.editing.assembler;

import javafx.application.Platform;
import me.darknet.assembler.backend.dalvik.DalvikTargetContext;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.parser.DeclarationParser;
import me.darknet.assembler.parser.Tokenizer;
import me.darknet.assembler.parser.processor.ASTProcessor;
import me.darknet.assembler.target.TargetContext;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import software.coley.recaf.ui.BaseFxTest;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AssemblerLabelRenameTest extends BaseFxTest {
	private static final String JVM_SOURCE = """
			.class public Sample {
			    .method public static sample ()V {
			        exceptions: { { Start, End, Target, java/lang/Exception } },
			        code: {
			    Start:
			            goto Target
			            lookupswitch { default: Target, 1: Target }
			    End:
			    Target:
			            ldc "Target"
			            return
			        }
			    }
			    .method public static other ()V {
			        code: {
			    Target:
			            return
			        }
			    }
			}
			""";

	@Test
	void findsOnlyLabelTokensInTheSelectedMethod() {
		int offset = JVM_SOURCE.indexOf("goto Target") + 7;
		var edit = resolve(JVM_SOURCE, JvmTargetContext.INSTANCE, offset);
		assertNotNull(edit);
		assertEquals(List.of(JVM_SOURCE.indexOf("Target"), JVM_SOURCE.indexOf("Target", offset - 2),
						JVM_SOURCE.indexOf("default: Target") + "default: ".length(),
						JVM_SOURCE.indexOf("1: Target") + "1: ".length(), JVM_SOURCE.indexOf("Target:")),
				edit.spans().stream().map(AssemblerLabelRename.Span::start).toList());
		assertNull(edit.error("ExitHandler"));
		assertEquals("assembler.rename-label.taken", edit.error("Start"));
		assertEquals("assembler.rename-label.invalid", edit.error("bad name"));
		assertEquals("assembler.rename-label.invalid", edit.error("Other:"));
		assertNull(resolve(JVM_SOURCE, JvmTargetContext.INSTANCE, JVM_SOURCE.indexOf("\"Target\"") + 2));
	}

	@Test
	void includesDalvikSwitchTargets() {
		String source = """
				.method public static sample ()V {
				    registers: 1,
				    code: {
				        goto Label
				        packed-switch v0 { first: 0, targets: { Label } }
				        sparse-switch v0 { 1: Label }
				    Label:
				        return-void
				    }
				}
				""";
		var edit = resolve(source, DalvikTargetContext.INSTANCE, source.indexOf("Label:") + 2);
		assertNotNull(edit);
		assertEquals(4, edit.spans().size());
	}

	@Test
	void appliesAllReplacementsInOneUndoStep() throws Exception {
		try {
			Platform.startup(() -> Platform.setImplicitExit(false));
		} catch (IllegalStateException ignored) {
			// Another UI test may already have started JavaFX.
		} catch (UnsupportedOperationException unavailable) {
			assumeTrue(false, unavailable.getMessage());
		}
		var task = new FutureTask<Void>(() -> {
			var area = new CodeArea(JVM_SOURCE);
			try {
				area.getUndoManager().forgetHistory();
				var edit = resolve(JVM_SOURCE, JvmTargetContext.INSTANCE, JVM_SOURCE.indexOf("Target:") + 1);
				assertNotNull(edit);
				assertTrue(edit.apply(area, "ExitHandler"));
				assertEquals(JVM_SOURCE.replaceFirst("Target", "ExitHandler")
								.replace("goto Target", "goto ExitHandler")
								.replace("default: Target, 1: Target", "default: ExitHandler, 1: ExitHandler")
								.replaceFirst("Target:", "ExitHandler:"),
						area.getText());
				assertEquals(area.getText().indexOf("ExitHandler:") + "ExitHandler".length(), area.getCaretPosition());
				assertTrue(area.getUndoManager().undo());
				assertEquals(JVM_SOURCE, area.getText());
				assertFalse(area.getUndoManager().isUndoAvailable());
			} finally {
				area.dispose();
			}
			return null;
		});
		Platform.runLater(task);
		task.get(10, TimeUnit.SECONDS);
	}

	private static AssemblerLabelRename.Edit resolve(String source, TargetContext target, int offset) {
		var tokens = new Tokenizer().tokenize("<test>", source).requireValue();
		var declarations = new DeclarationParser().parseDeclarations(tokens).requireValue();
		return AssemblerLabelRename.resolve(source, new ASTProcessor(target).processAST(declarations).requireValue(), target, offset);
	}
}
