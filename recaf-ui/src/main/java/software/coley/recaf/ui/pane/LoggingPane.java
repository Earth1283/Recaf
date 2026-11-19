package software.coley.recaf.ui.pane;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
			.withZone(ZoneId.systemDefault());
	// Accessed on the FX thread alongside the editor paragraphs.
	private final List<LogCallInfo> infos = new ArrayList<>();
	private final Queue<LogCall> messageQueue = new ArrayDeque<>();
	private final ObservableValue<Boolean> showing = sceneProperty().flatMap(Scene::windowProperty)
			.flatMap(Window::showingProperty).orElse(false);
	private CodeArea codeArea;
	// Guarded by messageQueue, which also preserves log order across producer threads.
	private boolean displayed;
	private boolean flushScheduled;

	@Inject
	public LoggingPane(@Nonnull RecafDirectoriesConfig config, @Nonnull Instance<SearchBar> searchBar) {
		Path logPath = config.getCurrentLogPath();
		accept("Recaf", logPath == null ? Level.WARN : Level.INFO, logPath == null
				? "File logging is unavailable"
				: "Writing logs to " + StringUtil.pathToAbsoluteString(logPath));
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

	@PreDestroy
	private void removeLogConsumer() {
		Logging.removeLogConsumer(this);
	}

	private void flushMessages() {
		List<LogCall> messages;
		synchronized (messageQueue) {
			if (!displayed || messageQueue.isEmpty())
				return;
			messages = new ArrayList<>(messageQueue);
			messageQueue.clear();
		}
		StringBuilder text = new StringBuilder();
		for (LogCall call : messages) {
			if (!infos.isEmpty())
				text.append('\n');
			LogCallInfo info = call.info();
			String name = info.loggerName();
			String message = call.message().replace("\r\n", "\n").replace('\r', '\n');
			text.append(TIME_FORMAT.format(info.timestamp())).append(' ')
					.append(info.level()).append(" [")
					.append(name, name.lastIndexOf('.') + 1, name.length())
					.append("] ").append(message);
			infos.add(info);
			for (int index = message.indexOf('\n'); index >= 0; index = message.indexOf('\n', index + 1))
				infos.add(null); // Continuation paragraphs belong to the same log call.
		}
		codeArea.appendText(text.toString());
		codeArea.showParagraphAtBottom(codeArea.getParagraphs().size() - 1);
	}

	@Override
	public void accept(@Nonnull String loggerName, @Nonnull Level level, @Nullable String messageContent) {
		accept(loggerName, level, messageContent, null);
	}

	@Override
	public void accept(@Nonnull String loggerName, @Nonnull Level level, @Nullable String messageContent, @Nullable Throwable throwable) {
		if (messageContent == null && throwable == null)
			return;
		if (messageContent == null)
			messageContent = Objects.requireNonNullElse(throwable.getMessage(), throwable.getClass().getSimpleName());
		LogCallInfo info = new LogCallInfo(Instant.now(), level, loggerName, Thread.currentThread().getName(), throwable);
		synchronized (messageQueue) {
			messageQueue.add(new LogCall(messageContent, info));
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

	private record LogCall(@Nonnull String message, @Nonnull LogCallInfo info) {}

	private record LogCallInfo(
			@Nonnull Instant timestamp,
			@Nonnull Level level,
			@Nonnull String loggerName,
			@Nonnull String threadName,
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
			if (info == null)
				return;
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
			String details = info.loggerName() + "\nThread: " + info.threadName();
			if (info.throwable() != null)
				details += "\n\n" + StringUtil.traceToString(info.throwable());
			Tooltip tooltip = new Tooltip(details);
			tooltip.setShowDelay(Duration.ZERO);
			Tooltip.install(wrapper, tooltip);
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
