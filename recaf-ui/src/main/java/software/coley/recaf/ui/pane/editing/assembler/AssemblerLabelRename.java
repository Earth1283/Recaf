package software.coley.recaf.ui.pane.editing.assembler;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nullable;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTIdentifier;
import me.darknet.assembler.parser.TokenType;
import me.darknet.assembler.parser.Tokenizer;
import me.darknet.assembler.query.AssemblyQueries;
import me.darknet.assembler.query.LabelInfo;
import me.darknet.assembler.query.LabelQueryResult;
import me.darknet.assembler.query.resolution.LabelDeclarationResolution;
import me.darknet.assembler.query.resolution.LabelReferenceResolution;
import me.darknet.assembler.query.resolution.LabelResolution;
import me.darknet.assembler.target.TargetContext;
import org.fxmisc.richtext.CodeArea;
import software.coley.recaf.util.Lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves and applies a method-scoped label rename. */
final class AssemblerLabelRename {
	private AssemblerLabelRename() {}

	@Nullable
	static Edit resolve(String source, List<ASTElement> ast, TargetContext target, int offset) {
		if (!(AssemblyQueries.resolveAt(ast, offset, target) instanceof LabelResolution resolution))
			return null;
		LabelInfo label = switch (resolution) {
			case LabelDeclarationResolution declaration -> declaration.label();
			case LabelReferenceResolution reference -> reference.usage().label();
			default -> null;
		};
		if (label == null || label.duplicate())
			return null;

		LabelQueryResult labels = AssemblyQueries.labels(resolution.method(), target);
		var identifiers = new ArrayList<ASTIdentifier>();
		identifiers.add(label.declaration().identifier());
		labels.referencesTo(label).forEach(usage -> identifiers.add(usage.reference()));
		var spans = new ArrayList<Span>(identifiers.size());
		for (ASTIdentifier identifier : identifiers) {
			int start = identifier.range().start();
			String spelling = identifier.content();
			if (start < 0 || spelling.isEmpty() || !source.startsWith(spelling, start))
				return null;
			spans.add(new Span(start, spelling.length()));
		}
		int selected = resolution.identifier().range().start();
		if (spans.stream().noneMatch(span -> span.start() == selected))
			return null;
		return new Edit(source, resolution.identifier().content(), label.name(), labels,
				spans.stream().distinct().sorted(Comparator.comparingInt(Span::start)).toList(), selected);
	}

	record Edit(String source, String spelling, String name, LabelQueryResult labels, List<Span> spans, int selected) {
		@Nullable
		String error(String replacement) {
			var tokens = new Tokenizer().tokenize("<label>", replacement);
			if (!tokens.isSuccess() || tokens.requireValue().size() != 1)
				return "assembler.rename-label.invalid";
			var token = tokens.requireValue().getFirst();
			if (token.type() != TokenType.IDENTIFIER || !token.content().equals(replacement))
				return "assembler.rename-label.invalid";
			String newName = new ASTIdentifier(token).literal();
			if (!newName.equals(name) && (!labels.declarationsOf(newName).isEmpty() || !labels.usagesOf(newName).isEmpty()))
				return "assembler.rename-label.taken";
			return null;
		}

		void show(CodeArea area) {
			if (area.getScene() == null || !source.equals(area.getText()))
				return;

			var dialog = new Dialog<ButtonType>();
			dialog.initOwner(area.getScene().getWindow());
			dialog.setTitle(Lang.get("assembler.rename-label.title"));
			var pane = dialog.getDialogPane();
			pane.getStylesheets().setAll(area.getScene().getStylesheets());
			pane.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

			var input = new TextField(spelling);
			input.setAccessibleText(Lang.get("assembler.rename-label.name"));
			input.getStyleClass().add("mono-text");
			var caption = new Label(Lang.get("assembler.rename-label.name"));
			caption.setLabelFor(input);
			var feedback = new Label();
			feedback.getStyleClass().add(Styles.DANGER);
			feedback.setWrapText(true);
			feedback.managedProperty().bind(feedback.visibleProperty());
			var content = new VBox(6, caption, input, feedback);
			content.setPrefWidth(290);
			pane.setContent(content);

			var accept = pane.lookupButton(ButtonType.OK);
			accept.setDisable(true);
			feedback.setVisible(false);
			input.textProperty().addListener((observable, oldText, newText) -> {
				String error = newText.isEmpty() ? null : error(newText);
				feedback.setText(error == null ? "" : Lang.get(error));
				feedback.setVisible(error != null);
				accept.setDisable(newText.isEmpty() || newText.equals(spelling) || error != null);
			});
			dialog.setOnShown(event -> Platform.runLater(() -> {
				input.requestFocus();
				input.selectAll();
			}));
			dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> apply(area, input.getText()));
		}

		boolean apply(CodeArea area, String replacement) {
			if (!source.equals(area.getText()) || spelling.equals(replacement) || error(replacement) != null)
				return false;
			var changes = area.createMultiChange(spans.size());
			int caret = selected + replacement.length();
			for (Span span : spans) {
				changes.replaceText(span.start(), span.start() + span.length(), replacement);
				if (span.start() < selected)
					caret += replacement.length() - span.length();
			}
			area.getUndoManager().preventMerge();
			changes.commit();
			area.getUndoManager().preventMerge();
			area.moveTo(caret);
			return true;
		}
	}

	record Span(int start, int length) {}
}
