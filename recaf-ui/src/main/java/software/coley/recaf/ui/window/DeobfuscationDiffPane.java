package software.coley.recaf.ui.window;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.inject.Instance;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.Batch;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.util.threading.ThreadUtil;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searchable unified diff. Diff computation is deferred until the tab is opened.
 */
final class DeobfuscationDiffPane extends BorderPane {
	private final Editor editor = new Editor();
	private final Label summary = new Label();
	private final ExecutorService executor = ThreadPoolFactory.newSingleThreadExecutor("deobfuscation-diff");
	private final Batch batch = ThreadUtil.batch(executor);
	private final AtomicInteger generation = new AtomicInteger();
	private boolean active;
	private boolean closed;
	private String before;
	private String after;
	private DeobfuscationDiff cached;

	DeobfuscationDiffPane(@Nonnull Instance<SearchBar> searchBars) {
		editor.getCodeArea().setEditable(false);
		editor.setId("deobfuscation-diff-editor");
		searchBars.get().install(editor);
		summary.setPadding(new Insets(8));
		setTop(summary);
		setCenter(editor);
		clear("deobf.preview.noselection");
	}

	void setActive(boolean active) {
		if (closed)
			return;
		this.active = active;
		if (active && cached == null && before != null && after != null)
			compute();
	}

	void clear(@Nonnull String messageKey) {
		if (closed)
			return;
		generation.incrementAndGet();
		batch.clear();
		before = null;
		after = null;
		cached = null;
		summary.textProperty().bind(Lang.getBinding(messageKey));
		editor.setText("");
	}

	void setInputs(@Nonnull String before, @Nonnull String after) {
		if (closed)
			return;
		if (before.equals(this.before) && after.equals(this.after))
			return;
		this.before = before;
		this.after = after;
		cached = null;
		generation.incrementAndGet();
		if (active)
			compute();
	}

	private synchronized void compute() {
		int version = generation.get();
		String original = before;
		String modified = after;
		summary.textProperty().bind(Lang.getBinding("deobf.diff.loading"));
		batch.add(() -> {
			synchronized (DeobfuscationDiffPane.this) {
				if (version != generation.get())
					return;
				batch.clear();
			}
			DeobfuscationDiff diff;
			try {
				diff = DeobfuscationDiff.between(original, modified);
			} catch (RuntimeException error) {
				Platform.runLater(() -> {
					if (!closed && version == generation.get()) {
						summary.textProperty().bind(Lang.getBinding("deobf.diff.unavailable"));
						editor.setText(error.toString());
					}
				});
				return;
			}
			Platform.runLater(() -> {
				if (closed || version != generation.get())
					return;
				cached = diff;
				if (diff.lines().isEmpty()) {
					summary.textProperty().bind(Lang.getBinding("deobf.diff.unchanged"));
					editor.setText("");
					return;
				}
				summary.textProperty().unbind();
				summary.setText("+" + diff.added() + " / -" + diff.removed());
				editor.setText(String.join("\n", diff.lines()));
				StyleSpansBuilder<Collection<String>> styles = new StyleSpansBuilder<>();
				for (int i = 0; i < diff.lines().size(); i++) {
					String line = diff.lines().get(i);
					String style = i < 2 || line.startsWith("@@") ? "preview-diff-header" :
							line.startsWith("+") ? "preview-diff-add" :
							line.startsWith("-") ? "preview-diff-remove" : "preview-diff-context";
					styles.add(List.of(style), line.length() + (i < diff.lines().size() - 1 ? 1 : 0));
				}
				editor.setStyleSpans(0, styles.create());
			});
		});
		batch.executeNewest();
	}

	void close() {
		closed = true;
		generation.incrementAndGet();
		batch.clear();
		executor.shutdownNow();
		editor.close();
	}
}
