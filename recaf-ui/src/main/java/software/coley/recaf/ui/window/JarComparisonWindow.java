package software.coley.recaf.ui.window;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import jakarta.annotation.Nullable;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.analysis.compare.JarComparison;
import software.coley.recaf.services.analysis.compare.JarComparison.EntryChange;
import software.coley.recaf.services.analysis.compare.JarComparison.EntryStamp;
import software.coley.recaf.services.analysis.compare.JarComparison.Kind;
import software.coley.recaf.services.analysis.compare.JarComparison.MemberChange;
import software.coley.recaf.services.analysis.compare.JarComparison.Preview;
import software.coley.recaf.services.analysis.compare.JarComparison.Status;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.ThreadPoolFactory;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Read-only comparison of two JAR files, independent of the open workspace.
 */
public class JarComparisonWindow extends RecafStage {
	private static final Logger logger = Logging.get(JarComparisonWindow.class);
	private static final int MAX_PREVIEW_CHARS = 300_000;
	private static final int MAX_DIFF_LINES = 2_000;
	private final ExecutorService worker = ThreadPoolFactory.newSingleThreadExecutor("jar-comparison");
	private final TextField beforePath = new TextField();
	private final TextField afterPath = new TextField();
	private final CheckBox strict = new CheckBox();
	private final TreeView<Row> changes = new TreeView<>();
	private final Label summary = new Label();
	private final Label previewStatus = new Label();
	private final Editor beforeEditor = new Editor();
	private final Editor afterEditor = new Editor();
	private final Label beforeDetails = new Label();
	private final Label afterDetails = new Label();
	private Path baseline;
	private Path updated;
	private Task<List<EntryChange>> comparisonTask;
	private Task<DiffView> previewTask;
	private boolean syncingScroll;

	/** Opens an independent comparison window. */
	public JarComparisonWindow() {
		titleProperty().bind(Lang.getBinding("jarcompare.title"));
		hideOnEscape();
		setMinWidth(760);
		setMinHeight(480);

		GridPane inputs = new GridPane(8, 8);
		addInput(inputs, 0, "jarcompare.before", beforePath);
		addInput(inputs, 1, "jarcompare.after", afterPath);
		strict.setId("jar-comparison-strict");
		strict.textProperty().bind(Lang.getBinding("jarcompare.strict"));
		Tooltip strictHint = new Tooltip();
		strictHint.textProperty().bind(Lang.getBinding("jarcompare.strict.help"));
		strictHint.setWrapText(true);
		strictHint.setMaxWidth(320);
		strict.setTooltip(strictHint);
		strict.selectedProperty().addListener((ob, old, current) -> {
			if (baseline != null)
				compare();
		});
		Button compare = new Button();
		compare.setId("jar-comparison-run");
		compare.textProperty().bind(Lang.getBinding("jarcompare.run"));
		compare.setOnAction(e -> compare());
		compare.disableProperty().bind(Bindings.createBooleanBinding(
				() -> beforePath.getText().isBlank() || afterPath.getText().isBlank(),
				beforePath.textProperty(), afterPath.textProperty()));
		beforePath.setOnAction(e -> compare.fire());
		afterPath.setOnAction(e -> compare.fire());
		HBox options = new HBox(12, compare, strict, summary);
		options.setAlignment(Pos.CENTER_LEFT);
		VBox toolbar = new VBox(10, inputs, options);
		toolbar.setPadding(new Insets(10));

		changes.setId("jar-comparison-changes");
		changes.setShowRoot(false);
		changes.setCellFactory(view -> new TreeCell<>() {
			@Override
			protected void updateItem(Row row, boolean empty) {
				super.updateItem(row, empty);
				if (empty || row == null)
					setText(null);
				else
					setText(Lang.get(statusKey(row.status())) + "  " + row.label());
			}
		});
		changes.getSelectionModel().selectedItemProperty().addListener((ob, old, current) ->
				showPreview(current == null ? null : current.getValue()));
		changes.setMinWidth(170);

		beforeEditor.getCodeArea().estimatedScrollYProperty().addListener((ob, old, value) -> syncScroll(afterEditor, value));
		afterEditor.getCodeArea().estimatedScrollYProperty().addListener((ob, old, value) -> syncScroll(beforeEditor, value));
		SplitPane sides = new SplitPane(side("jarcompare.before", beforeDetails, beforeEditor),
				side("jarcompare.after", afterDetails, afterEditor));
		BorderPane preview = new BorderPane(sides);
		previewStatus.setPadding(new Insets(6, 10, 6, 10));
		preview.setBottom(previewStatus);
		SplitPane content = new SplitPane(changes, preview);
		content.setDividerPositions(0.28);

		setScene(new RecafScene(new BorderPane(content, toolbar, null, null, null), 1180, 720));
		setOnHidden(e -> {
			resetResults();
			worker.shutdownNow();
			beforeEditor.close();
			afterEditor.close();
		});
	}

	private void addInput(GridPane inputs, int row, String key, TextField path) {
		Label label = new Label();
		label.textProperty().bind(Lang.getBinding(key));
		label.setLabelFor(path);
		path.setId(row == 0 ? "jar-comparison-before" : "jar-comparison-after");
		path.textProperty().addListener((ob, old, current) -> resetResults());
		GridPane.setHgrow(path, Priority.ALWAYS);
		Button browse = new Button();
		browse.textProperty().bind(Lang.getBinding("jarcompare.browse"));
		browse.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle(Lang.get(key));
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Lang.get("jarcompare.files"), "*.jar", "*.JAR"));
			File selected = chooser.showOpenDialog(this);
			if (selected != null)
				path.setText(selected.getAbsolutePath());
		});
		inputs.addRow(row, label, path, browse);
	}

	private static BorderPane side(String key, Label details, Editor editor) {
		Label title = new Label();
		title.textProperty().bind(Lang.getBinding(key));
		HBox header = new HBox(10, title, details);
		header.setPadding(new Insets(8));
		editor.getCodeArea().setEditable(false);
		editor.setId(key.equals("jarcompare.before") ? "jar-comparison-before-preview" : "jar-comparison-after-preview");
		return new BorderPane(editor, header, null, null, null);
	}

	private void compare() {
		resetResults();
		Path before;
		Path after;
		try {
			before = Path.of(beforePath.getText().strip());
			after = Path.of(afterPath.getText().strip());
		} catch (InvalidPathException ex) {
			showError(summary, "jarcompare.failed", ex);
			return;
		}
		baseline = before;
		updated = after;
		boolean strictMode = strict.isSelected();
		summary.setText(Lang.get("jarcompare.comparing"));
		Task<List<EntryChange>> task = new Task<>() {
			@Override
			protected List<EntryChange> call() throws Exception {
				return JarComparison.compare(before, after, strictMode);
			}
		};
		comparisonTask = task;
		task.setOnSucceeded(e -> {
			if (comparisonTask == task) {
				comparisonTask = null;
				showResults(task.getValue());
			}
		});
		task.setOnFailed(e -> {
			if (comparisonTask == task) {
				comparisonTask = null;
				showError(summary, "jarcompare.failed", task.getException());
			}
		});
		worker.execute(task);
	}

	private void showResults(List<EntryChange> entries) {
		if (entries.isEmpty()) {
			summary.setText(Lang.get("jarcompare.unchanged"));
			return;
		}
		TreeItem<Row> root = new TreeItem<>();
		int classes = 0;
		int methods = 0;
		for (EntryChange entry : entries) {
			TreeItem<Row> item = new TreeItem<>(new Row(entry, null));
			root.getChildren().add(item);
			if (entry.kind() == Kind.CLASS)
				classes++;
			for (MemberChange member : entry.members()) {
				if (member.kind() == Kind.METHOD)
					methods++;
			}
			if (!entry.members().isEmpty()) {
				// Populate member rows on expansion so large archives only create the nodes the user opens.
				TreeItem<Row> placeholder = new TreeItem<>();
				item.getChildren().add(placeholder);
				item.addEventHandler(TreeItem.branchExpandedEvent(), e -> {
					if (item.getChildren().contains(placeholder))
						item.getChildren().setAll(entry.members().stream().map(member -> new TreeItem<>(new Row(entry, member))).toList());
				});
			}
		}
		changes.setRoot(root);
		root.setExpanded(true);
		changes.getSelectionModel().selectFirst();
		summary.setText(Lang.get("jarcompare.summary").formatted(classes, methods, entries.size() - classes));
	}

	private void showPreview(@Nullable Row row) {
		cancelPreview();
		beforeEditor.setText("");
		afterEditor.setText("");
		beforeDetails.setText("");
		afterDetails.setText("");
		if (row == null) {
			previewStatus.setText("");
			return;
		}
		Path before = baseline;
		Path after = updated;
		previewStatus.setText(Lang.get("jarcompare.preview.loading"));
		Task<DiffView> task = new Task<>() {
			@Override
			protected DiffView call() throws Exception {
				return DiffView.between(JarComparison.preview(before, row.entry(), row.member(), true),
						JarComparison.preview(after, row.entry(), row.member(), false));
			}
		};
		previewTask = task;
		task.setOnSucceeded(e -> {
			if (previewTask == task) {
				previewTask = null;
				DiffView diff = task.getValue();
				render(beforeEditor, diff.before(), "preview-diff-remove");
				render(afterEditor, diff.after(), "preview-diff-add");
				if (row.entry().kind() == Kind.RESOURCE) {
					beforeDetails.setText(entryDetails(row.entry().before()));
					afterDetails.setText(entryDetails(row.entry().after()));
				}
				previewStatus.setText(diff.notice() == null ? row.label() : Lang.get(diff.notice()));
			}
		});
		task.setOnFailed(e -> {
			if (previewTask == task) {
				previewTask = null;
				showError(previewStatus, "jarcompare.preview.failed", task.getException());
			}
		});
		worker.execute(task);
	}

	private static void render(Editor editor, List<DiffLine> lines, String changeStyle) {
		StringBuilder text = new StringBuilder();
		StyleSpansBuilder<Collection<String>> styles = new StyleSpansBuilder<>();
		for (int i = 0; i < lines.size(); i++) {
			DiffLine line = lines.get(i);
			int start = text.length();
			text.append(line.text());
			if (i + 1 < lines.size())
				text.append('\n');
			styles.add(line.changed() ? List.of(changeStyle) : List.of(), text.length() - start);
		}
		editor.setText(text.toString());
		if (!text.isEmpty())
			editor.setStyleSpans(0, styles.create());
		editor.getCodeArea().moveTo(0);
		editor.getCodeArea().scrollYToPixel(0);
	}

	private void syncScroll(Editor target, Number value) {
		if (!syncingScroll && Double.isFinite(value.doubleValue())) {
			syncingScroll = true;
			try {
				target.getCodeArea().scrollYToPixel(value.doubleValue());
			} finally {
				syncingScroll = false;
			}
		}
	}

	private void resetResults() {
		if (comparisonTask != null) {
			comparisonTask.cancel();
			comparisonTask = null;
		}
		cancelPreview();
		changes.setRoot(null);
		baseline = null;
		updated = null;
		summary.setText("");
	}

	private void cancelPreview() {
		if (previewTask != null) {
			previewTask.cancel();
			previewTask = null;
		}
	}

	private static void showError(Label label, String key, Throwable error) {
		logger.warn("JAR comparison could not complete", error);
		label.setText(Lang.get(key).formatted(error.getMessage()));
	}

	private static String entryDetails(@Nullable EntryStamp stamp) {
		return stamp == null ? "" : Lang.get("jarcompare.preview.details").formatted(stamp.size(), stamp.crc());
	}

	private static String statusKey(Status status) {
		return switch (status) {
			case ADDED -> "jarcompare.added";
			case REMOVED -> "jarcompare.removed";
			case CHANGED -> "jarcompare.changed";
		};
	}

	private record Row(EntryChange entry, @Nullable MemberChange member) {
		private Status status() {
			return member == null ? entry.status() : member.status();
		}

		private String label() {
			if (member == null)
				return entry.path();
			return member.name() + (member.kind() == Kind.FIELD ? " " : "") + member.descriptor();
		}
	}

	private record DiffLine(String text, boolean changed) {
	}

	private record DiffView(List<DiffLine> before, List<DiffLine> after, @Nullable String notice) {
		private static DiffView between(@Nullable Preview before, @Nullable Preview after) {
			String left = previewText(before);
			String right = previewText(after);
			List<String> leftLines = split(left);
			List<String> rightLines = split(right);
			if (left.length() > MAX_PREVIEW_CHARS || right.length() > MAX_PREVIEW_CHARS)
				return new DiffView(unmarked(leftLines), unmarked(rightLines), "jarcompare.preview.truncated");
			if (leftLines.size() + rightLines.size() > MAX_DIFF_LINES)
				return new DiffView(unmarked(leftLines), unmarked(rightLines), "jarcompare.preview.limited");

			List<DiffLine> oldLines = new ArrayList<>();
			List<DiffLine> newLines = new ArrayList<>();
			int oldOffset = 0;
			int newOffset = 0;
			for (AbstractDelta<String> delta : DiffUtils.diff(leftLines, rightLines).getDeltas()) {
				int oldPosition = delta.getSource().getPosition();
				int newPosition = delta.getTarget().getPosition();
				oldLines.addAll(unmarked(leftLines.subList(oldOffset, oldPosition)));
				newLines.addAll(unmarked(rightLines.subList(newOffset, newPosition)));
				List<String> removed = delta.getSource().getLines();
				List<String> added = delta.getTarget().getLines();
				int count = Math.max(removed.size(), added.size());
				for (int i = 0; i < count; i++) {
					oldLines.add(i < removed.size() ? new DiffLine(removed.get(i), true) : new DiffLine("", false));
					newLines.add(i < added.size() ? new DiffLine(added.get(i), true) : new DiffLine("", false));
				}
				oldOffset = oldPosition + removed.size();
				newOffset = newPosition + added.size();
			}
			oldLines.addAll(unmarked(leftLines.subList(oldOffset, leftLines.size())));
			newLines.addAll(unmarked(rightLines.subList(newOffset, rightLines.size())));
			return new DiffView(oldLines, newLines, null);
		}

		private static List<String> split(String text) {
			return Arrays.asList(text.substring(0, Math.min(MAX_PREVIEW_CHARS, text.length())).split("\\R", -1));
		}

		private static String previewText(@Nullable Preview preview) {
			if (preview == null)
				return Lang.get("jarcompare.preview.absent");
			if (preview.text() != null)
				return preview.text();
			return Lang.get("jarcompare.preview.binary");
		}

		private static List<DiffLine> unmarked(List<String> lines) {
			return lines.stream().map(line -> new DiffLine(line, false)).toList();
		}
	}
}
