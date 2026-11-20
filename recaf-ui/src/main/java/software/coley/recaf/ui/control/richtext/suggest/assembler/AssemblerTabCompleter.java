package software.coley.recaf.ui.control.richtext.suggest.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.target.TargetContext;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.suggest.CompletionPopup;
import software.coley.recaf.ui.control.richtext.suggest.CompletionResolver;
import software.coley.recaf.ui.control.richtext.suggest.TabCompleter;
import software.coley.recaf.ui.control.richtext.suggest.TabCompletionConfig;
import software.coley.recaf.ui.control.richtext.suggest.ranking.CompletionRanker;
import software.coley.recaf.ui.control.richtext.suggest.typeindex.TypeIndexService;
import software.coley.recaf.ui.pane.editing.assembler.AssemblerPane;
import software.coley.recaf.util.Icons;
import software.coley.recaf.util.SVG;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static javafx.scene.input.KeyCombination.CONTROL_DOWN;

/**
 * Tab completion for JASM assembly language, with both dialects of {@link JvmTargetContext} and {@link me.darknet.assembler.backend.dalvik.DalvikTargetContext} supported.
 * Ties into the {@link AssemblerPane} to provide access to the current target context and AST.
 *
 * @author Matt Coley
 */
public class AssemblerTabCompleter implements TabCompleter<AssemblerCompletion>, CompletionResolver<AssemblerCompletion> {
	private static final KeyCodeCombination SHOW_COMPLETIONS = new KeyCodeCombination(KeyCode.SPACE, CONTROL_DOWN);
	private final Workspace workspace;
	private final InheritanceGraph inheritanceGraph;
	private final TypeIndexService typeIndexService;
	private final CellConfigurationService configurationService;
	private final TabCompletionConfig config;
	private final CompletionRanker<AssemblerCompletion> ranker;
	private final EventHandler<KeyEvent> explicitCompletionHandler = this::showCompletions;
	private String editedClassName;
	private AssemblerCompletionEngine engine;
	private CompletionPopup<AssemblerCompletion> completionPopup;
	private CodeArea area;
	private List<ASTElement> ast;
	private volatile AssemblerOpcodeAnalysis opcodeAnalysis;
	private AssemblerCompletionContext context = new AssemblerCompletionContext.Empty();

	/**
	 * @param workspace
	 * 		Workspace to pull type and member information from.
	 * @param inheritanceGraph
	 * 		Inheritance graph for member lookups.
	 * @param typeIndexService
	 * 		Type index service for workspace type lookups.
	 * @param configurationService
	 * 		Service to pull cell graphics from.
	 * @param config
	 * 		Completion popup configuration.
	 */
	public AssemblerTabCompleter(@Nonnull Workspace workspace,
	                             @Nonnull InheritanceGraph inheritanceGraph,
	                             @Nonnull TypeIndexService typeIndexService,
	                             @Nonnull CellConfigurationService configurationService,
	                             @Nonnull TabCompletionConfig config) {
		this.workspace = workspace;
		this.inheritanceGraph = inheritanceGraph;
		this.configurationService = configurationService;
		this.config = config;
		this.ranker = AssemblerCompletionRanking.create(config);
		this.typeIndexService = typeIndexService;
		this.engine = new AssemblerCompletionEngine(typeIndexService, workspace, inheritanceGraph, JvmTargetContext.INSTANCE);
	}

	/**
	 * @param ast
	 * 		AST to work off of.
	 */
	public void setAst(@Nonnull List<ASTElement> ast) {
		this.ast = ast;
	}

	/**
	 * Remove AST to work off of.
	 */
	public void clearAst() {
		ast = null;
	}

	/** Publishes the latest source and its matching bytecode analysis. */
	public void setAnalysis(@Nonnull String source, @Nonnull List<ASTElement> ast, @Nonnull ClassResult<?> result) {
		opcodeAnalysis = new AssemblerOpcodeAnalysis(source, ast, result);
	}

	/** Discards analysis when the edited class changes. */
	public void clearAnalysis() {
		opcodeAnalysis = null;
	}

	/**
	 * @param targetContext
	 * 		Instruction language used by the currently edited class.
	 */
	public void setTargetContext(@Nonnull TargetContext targetContext) {
		if (engine.targetContext() == targetContext)
			return;

		clearAnalysis();
		engine = new AssemblerCompletionEngine(typeIndexService, workspace, inheritanceGraph, targetContext);
		if (area != null)
			recomputeLineContext();
	}

	/**
	 * @param editedClass
	 * 		Class currently assembled in the pane, or {@code null} if there is none.
	 */
	public void setEditedClass(@Nullable ClassInfo editedClass) {
		editedClassName = editedClass == null ? null : editedClass.getName();
		if (area != null)
			recomputeLineContext();
	}

	@Override
	public boolean requestCompletion(@Nonnull KeyEvent event) {
		boolean popupOpen = isCompletionActive();
		if (completionPopup == null || completionPopup.hasTextSelection()
				|| (!popupOpen && !isPlainTab(event)))
			return false;

		recomputeLineContext(popupOpen);
		if (popupOpen)
			return !(context instanceof AssemblerCompletionContext.Empty)
					&& completeFromContext(context.partialMatchedText(), completionPopup::doComplete);

		// A complete mnemonic already has the spelling the user chose.
		if (context instanceof AssemblerCompletionContext.OpcodeToken opcode
				&& engine.targetContext().instructions().get(opcode.partialMatchedText()) != null)
			return false;
		var items = rankCompletions(context, area.getText(), area.getCaretPosition());
		if (items.size() != 1)
			return showCompletionItems(items);
		completionPopup.updateItems(items);
		return completeFromContext(context.partialMatchedText(), completionPopup::doComplete);
	}

	private static boolean isPlainTab(KeyEvent event) {
		return event.getCode() == KeyCode.TAB && !event.isShiftDown() && !event.isControlDown()
				&& !event.isAltDown() && !event.isMetaDown();
	}

	@Override
	public boolean isCompletionActive() {
		return completionPopup != null && completionPopup.isShowing();
	}

	@Nonnull
	@Override
	public List<AssemblerCompletion> computeCurrentCompletions() {
		if (area == null)
			return List.of();
		recomputeLineContext();
		return rankCompletions(context, area.getText(), area.getCaretPosition());
	}

	@Nonnull
	@Override
	public List<AssemblerCompletion> resolve(@Nonnull String source, int caret) {
		return rankCompletions(engine.resolve(source, caret, ast, editedClassName), source, caret);
	}

	private List<AssemblerCompletion> rankCompletions(AssemblerCompletionContext context, String source, int caret) {
		var ranked = ranker.rank(context.complete().stream()
				.filter(completion -> completion.text().length() <= config.getMaxCompletionLength())
				.toList());
		var analysis = opcodeAnalysis;
		if (!(context instanceof AssemblerCompletionContext.OpcodeToken) || analysis == null || ranked.size() < 2)
			return ranked;
		var opcodeRank = analysis.ranking(source, caret);
		return ranked.stream().sorted(Comparator.comparingInt(completion -> opcodeRank.applyAsInt(completion.text()))).toList();
	}

	@Override
	public void onFineTextUpdate(@Nonnull PlainTextChange change) {
		recomputeLineContext();
	}

	@Override
	public void onRoughTextUpdate(@Nonnull List<PlainTextChange> changes) {
		// no-op
	}

	@Override
	public boolean isSpecialCompletableCharacter(char character) {
		// Support completing:
		// - '/' which is used to separate packages in class names
		// - '[' which is used in array descriptors
		// - ';' which is used to mark the end of object type descriptors
		// - '-' and '_' which appear in opcode names and identifiers
		// - '(' and ')' which delimit method descriptors
		return switch (character) {
			case '/', '[', ';', '-', '_', '(', ')' -> true;
			default -> false;
		};
	}

	@Override
	public void install(@Nonnull Editor editor) {
		if (completionPopup != null)
			uninstall(editor);
		area = editor.getCodeArea();
		completionPopup = new AssemblerCompletionPopup(config);
		completionPopup.install(area, this);
		area.addEventFilter(KeyEvent.KEY_PRESSED, explicitCompletionHandler);
	}

	@Override
	public void uninstall(@Nonnull Editor editor) {
		if (area != null)
			area.removeEventFilter(KeyEvent.KEY_PRESSED, explicitCompletionHandler);
		if (completionPopup != null) {
			completionPopup.hide();
			completionPopup.uninstall();
			completionPopup = null;
		}
		area = null;
		context = new AssemblerCompletionContext.Empty();
	}

	private void showCompletions(KeyEvent event) {
		if (!SHOW_COMPLETIONS.match(event) || completionPopup.hasTextSelection())
			return;
		recomputeLineContext(true);
		if (showCompletionItems(rankCompletions(context, area.getText(), area.getCaretPosition())))
			event.consume();
	}

	private boolean showCompletionItems(List<AssemblerCompletion> items) {
		if (items.isEmpty() || !completionPopup.updateCaretBounds())
			return false;
		completionPopup.updateItems(items);
		completionPopup.show();
		return true;
	}

	/**
	 * Recompute the context of the current line to determine what kind of completions to offer.
	 */
	private void recomputeLineContext() {
		recomputeLineContext(false);
	}

	private void recomputeLineContext(boolean explicit) {
		if (area == null) {
			context = new AssemblerCompletionContext.Empty();
			return;
		}
		context = engine.resolve(area.getText(), area.getCaretPosition(), ast, editedClassName, explicit);
	}

	/**
	 * @param context
	 * 		Partially matched text from the completion context.
	 * @param completionHandler
	 * 		Handler to perform the completion.
	 *
	 * @return Result of the handler, which is {@code true} if a completion was performed.
	 */
	private static boolean completeFromContext(@Nonnull String context, @Nonnull Predicate<String> completionHandler) {
		return completionHandler.test(context.trim());
	}

	/**
	 * Completion popup for assembler content.
	 */
	private class AssemblerCompletionPopup extends CompletionPopup<AssemblerCompletion> {
		private AssemblerCompletionPopup(@Nonnull TabCompletionConfig config) {
			super(config, STANDARD_CELL_SIZE, AssemblerCompletion::text, completion -> switch (completion) {
				case AssemblerCompletion.Opcode opcode -> {
					String text = opcode.text();
					if (text.startsWith("invoke"))
						yield Icons.getIconView(Icons.METHOD);
					if (text.startsWith("get") || text.startsWith("put"))
						yield Icons.getIconView(Icons.FIELD);
					if (text.endsWith("load"))
						yield SVG.ofIconFile(SVG.REF_READ);
					if (text.endsWith("store") || text.equals("iinc"))
						yield SVG.ofIconFile(SVG.REF_WRITE);
					if (text.contains("const") || text.equals("ldc"))
						yield Icons.getIconView(Icons.PRIMITIVE);
					if (text.startsWith("if") || text.endsWith("switch"))
						yield new FontIconView(CarbonIcons.FLOW, Color.STEELBLUE);
					if (text.endsWith("add"))
						yield new FontIconView(CarbonIcons.ADD, Color.LIMEGREEN);
					if (text.endsWith("sub") || text.endsWith("neg"))
						yield new FontIconView(CarbonIcons.SUBTRACT, Color.RED);
					if (text.startsWith("dup"))
						yield new FontIconView(CarbonIcons.COPY, Color.DARKGRAY);
					if (text.endsWith("return"))
						yield new FontIconView(CarbonIcons.TEXT_NEW_LINE, Color.STEELBLUE);
					if (text.equals("new") || text.equals("checkcast") || text.equals("instanceof"))
						yield Icons.getIconView(Icons.CLASS);
					if (text.indexOf('2') == 1) // x2y conversions
						yield SVG.ofIconFile(SVG.TYPE_CONVERSION);
					if (text.contains("cmp"))
						yield new FontIconView(CarbonIcons.COMPARE, Color.DARKGRAY);
					if (text.contains("array"))
						yield Icons.getIconView(Icons.ARRAY);
					if (text.equals("athrow"))
						yield SVG.ofIconFile(SVG.EXCEPTION_BREAKPOINT);
					yield Icons.getIconView(Icons.INTERNAL);
				}
				case AssemblerCompletion.Type type -> configurationService.graphicOf(type.path());
				case AssemblerCompletion.Member member -> configurationService.graphicOf(member.path());
				case AssemblerCompletion.Operand operand -> switch (operand.kind()) {
					case REGISTER -> Icons.getIconView(Icons.PRIMITIVE);
					case LABEL -> new FontIconView(CarbonIcons.FLOW, Color.STEELBLUE);
					case LOCAL -> Icons.getIconView(Icons.INTERNAL);
					case STRUCTURED_CONSTANT -> Icons.getIconView(Icons.INTERNAL);
				};
			});
		}

		@Override
		@Nullable
		protected String detail(@Nonnull AssemblerCompletion item) {
			if (!(item instanceof AssemblerCompletion.Opcode opcode))
				return null;
			var hint = AssemblerOpcodeHints.get(engine.targetContext(), opcode.text());
			return hint == null ? null : hint.description();
		}

		@Override
		protected boolean complete(@Nonnull String partialText, @Nonnull AssemblerCompletion selected,
		                           @Nonnull String fullText) {
			if (selected instanceof AssemblerCompletion.Opcode) {
				int caret = area.getCaretPosition();
				int start = caret - partialText.length();
				if (start < 0 || !area.getText(start, caret).equals(partialText) || !fullText.startsWith(partialText))
					return false;
				String line = area.getParagraph(area.getCurrentParagraph()).getText();
				int column = area.getCaretColumn();
				int endColumn = column;
				while (endColumn < line.length() && AssemblerOpcodeHints.isMnemonicCharacter(line.charAt(endColumn)))
					endColumn++;
				area.replaceText(start, caret + endColumn - column, fullText);
				area.moveTo(start + fullText.length());
				return true;
			}
			if (selected instanceof AssemblerCompletion.Type type && type.replacePartialText())
				return completeByReplacingPartialText(partialText, fullText);
			return super.complete(partialText, selected, fullText);
		}

		@Override
		public boolean doComplete(@Nonnull String partialText) {
			AssemblerCompletion selected = getSelected();
			boolean completed = super.doComplete(partialText);
			if (completed && selected != null)
				ranker.recordSelection(selected);
			return completed;
		}

		@Override
		public void completeCurrentSelection() {
			completeFromContext(context.partialMatchedText(), this::doComplete);
		}
	}
}
