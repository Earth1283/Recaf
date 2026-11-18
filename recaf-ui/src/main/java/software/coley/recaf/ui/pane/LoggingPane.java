package software.coley.recaf.ui.pane;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Shape;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.event.Level;
import software.coley.recaf.analytics.logging.LogConsumer;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.linegraphics.LineContainer;
import software.coley.recaf.ui.control.richtext.linegraphics.LineGraphicFactory;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.StringUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * Pane for displaying logger calls.
 *
 * @author Matt Coley
 */
@Dependent
public class LoggingPane extends BorderPane implements LogConsumer<String> {
	private final List<LogCallInfo> infos = Collections.synchronizedList(new ArrayList<>());
	private final Queue<String> messageQueue = new ArrayDeque<>();
	private final ObservableValue<Boolean> showing = sceneProperty().flatMap(Scene::windowProperty)
			.flatMap(Window::showingProperty).orElse(false);
	private CodeArea codeArea;
	// Guarded by messageQueue, which also preserves log order across producer threads.
	private boolean displayed;
	private boolean flushScheduled;

	@Inject
	public LoggingPane(@Nonnull RecafDirectoriesConfig config, @Nonnull Instance<SearchBar> searchBar) {
		accept("", Level.TRACE, "Current log will write to: " + StringUtil.pathToAbsoluteString(config.getCurrentLogPath()));
		Logging.addLogConsumer(this);
		showing.subscribe(visible -> {
			if (visible && codeArea == null) {
				Editor editor = new Editor();
				codeArea = editor.getCodeArea();
				codeArea.setEditable(false);
				searchBar.get().install(editor);
				editor.getRootLineGraphicFactory().addLineGraphicFactory(new LoggingLineFactory());
				setCenter(editor);
			}
			synchronized (messageQueue) {
				displayed = visible;
			}
			if (visible)
				flushMessages();
		});
	}

	private void flushMessages() {
		String text;
		synchronized (messageQueue) {
			if (!displayed || messageQueue.isEmpty())
				return;
			text = String.join("\n", messageQueue);
			messageQueue.clear();
		}
		codeArea.appendText(codeArea.getLength() == 0 ? text : "\n" + text);
		codeArea.showParagraphAtBottom(codeArea.getParagraphs().size() - 1);
	}

	@Override
	public void accept(@Nonnull String loggerName, @Nonnull Level level, @Nullable String messageContent) {
		accept(loggerName, level, messageContent, null);
	}

	@Override
	public void accept(@Nonnull String loggerName, @Nonnull Level level, @Nullable String messageContent, @Nullable Throwable throwable) {
		if (messageContent == null) {
			if (throwable == null)
				return;
			messageContent = Objects.requireNonNullElse(throwable.getMessage(), throwable.getClass().getSimpleName());
		}
		synchronized (messageQueue) {
			infos.add(new LogCallInfo(level, throwable));
			messageQueue.add(messageContent);
			if (!displayed || flushScheduled)
				return;
			flushScheduled = true;
		}
		FxThreadUtil.delayedRun(500, () -> {
			synchronized (messageQueue) {
				flushScheduled = false;
			}
			flushMessages();
		});
	}

	private record LogCallInfo(
			@Nonnull Level level,
			@Nullable Throwable throwable) {}

	private class LoggingLineFactory implements LineGraphicFactory {
		private static final Insets PADDING = new Insets(0, 10, 0, 0);
		private static final double SIZE = 4;
		private static final double[] TRIANGLE = {
				SIZE, 0, // Size used for circles is radius, so for triangles we want to double positions based on it.
				SIZE * 2, SIZE * 2,
				0, SIZE * 2
		};

		@Override
		public int priority() {
			return -1;
		}

		@Override
		public void apply(@Nonnull LineContainer container, int paragraph) {
			if (paragraph >= infos.size())
				return;
			LogCallInfo info = infos.get(paragraph);
			Shape shape;
			switch (info.level) {
				case ERROR -> {
					if (info.throwable == null)
						shape = new Circle(SIZE, Color.RED);
					else {
						shape = new Polygon(TRIANGLE);
						shape.setFill(Color.RED);
					}
				}
				case WARN -> shape = new Circle(SIZE, Color.YELLOW);
				case INFO -> shape = new Circle(SIZE, Color.LIGHTBLUE);
				case DEBUG -> shape = new Circle(SIZE, Color.CORNFLOWERBLUE);
				case TRACE -> shape = new Circle(SIZE, Color.DODGERBLUE);
				default -> throw new IllegalArgumentException("Unsupported logging level");
			}
			shape.setOpacity(0.65);

			// Wrap and provide right-side padding to give the indicator space between it and the line no.
			HBox wrapper = new HBox(shape);
			wrapper.setAlignment(Pos.CENTER);
			wrapper.setPadding(PADDING);
			wrapper.setCursor(Cursor.HAND);
			if (info.throwable != null) {
				Tooltip tooltip = new Tooltip(StringUtil.traceToString(info.throwable));
				tooltip.setShowDelay(Duration.ZERO);
				Tooltip.install(wrapper, tooltip);
			}
			container.addHorizontal(wrapper);
		}

		@Override
		public void install(@Nonnull Editor editor) {
			// no-op
		}

		@Override
		public void uninstall(@Nonnull Editor editor) {
			// no-op
		}
	}

}
