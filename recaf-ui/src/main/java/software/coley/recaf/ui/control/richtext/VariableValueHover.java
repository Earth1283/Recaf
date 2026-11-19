package software.coley.recaf.ui.control.richtext;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.reactfx.Subscription;
import software.coley.recaf.util.EscapeUtil;

import java.time.Duration;
import java.util.function.IntFunction;

/** Displays a value resolved at the character under the pointer. */
public final class VariableValueHover implements EditorComponent {
	private final IntFunction<Preview> resolver;
	private final Popup popup = new Popup();
	private final Label declaration = new Label();
	private final Label value = new Label();
	private final EventHandler<MouseOverTextEvent> showHandler = this::show;
	private final EventHandler<MouseOverTextEvent> endHandler = event -> hide();
	private final EventHandler<MouseEvent> pressHandler = event -> hide();
	private final EventHandler<ScrollEvent> scrollHandler = event -> hide();
	private final EventHandler<KeyEvent> keyHandler = event -> hide();
	private Subscription editorSubscription = Subscription.EMPTY;
	private Duration previousDelay;

	public VariableValueHover(@Nonnull IntFunction<Preview> resolver) {
		this.resolver = resolver;
		declaration.getStyleClass().add("variable-value-declaration");
		value.getStyleClass().add("variable-value-content");
		VBox content = new VBox(4, declaration, value);
		content.setMaxWidth(400);
		content.setAccessibleRole(AccessibleRole.TOOLTIP);
		content.getStyleClass().add("variable-value-hover");
		declaration.setWrapText(true);
		value.setWrapText(true);
		popup.getContent().add(content);
		popup.setAutoHide(true);
		popup.setConsumeAutoHidingEvents(false);
		popup.setHideOnEscape(true);
	}

	@Override
	public void install(@Nonnull Editor editor) {
		CodeArea area = editor.getCodeArea();
		previousDelay = area.mouseOverTextDelayProperty().get();
		area.mouseOverTextDelayProperty().set(Duration.ofMillis(450));
		area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, showHandler);
		area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, endHandler);
		area.addEventFilter(MouseEvent.MOUSE_PRESSED, pressHandler);
		area.addEventFilter(ScrollEvent.ANY, scrollHandler);
		area.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
		editorSubscription = Subscription.multi(
				area.plainTextChanges().subscribe(change -> hide()),
				area.viewportDirtyEvents().subscribe(change -> hide()));
	}

	@Override
	public void uninstall(@Nonnull Editor editor) {
		hide();
		editorSubscription.unsubscribe();
		CodeArea area = editor.getCodeArea();
		area.removeEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, showHandler);
		area.removeEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, endHandler);
		area.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressHandler);
		area.removeEventFilter(ScrollEvent.ANY, scrollHandler);
		area.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
		area.mouseOverTextDelayProperty().set(previousDelay);
	}

	private void show(@Nonnull MouseOverTextEvent event) {
		CodeArea area = (CodeArea) event.getSource();
		int offset = event.getCharacterIndex();
		if (offset < 0 || offset >= area.getLength() || area.getScene() == null ||
				!area.isHover() || area.getSelection().getLength() != 0)
			return;

		Preview preview = resolver.apply(offset);
		if (preview == null)
			return;

		Bounds bounds = area.getCharacterBoundsOnScreen(offset, offset + 1).orElse(null);
		if (bounds == null)
			return;

		declaration.setText(preview.type() == null ? preview.name() : preview.name() + "  ·  " + preview.type());
		value.setText(preview.value());
		popup.show(area, bounds.getMinX(), bounds.getMaxY() + 5);
	}

	/** Dismisses the preview when the editor's context changes. */
	public void hide() {
		popup.hide();
	}

	/** Formats text values with quotes and visible whitespace. */
	@Nonnull
	public static String quote(@Nonnull String text, char quote) {
		int limit = 240;
		boolean shortened = text.length() > limit;
		if (shortened) {
			if (Character.isSurrogatePair(text.charAt(limit - 1), text.charAt(limit)))
				limit--;
			text = text.substring(0, limit);
		}
		String escaped = EscapeUtil.escapeStandardAndUnicodeWhitespace(text);
		if (quote == '\'')
			escaped = escaped.replace("'", "\\'");
		return quote + escaped + quote + (shortened ? "…" : "");
	}

	/** The name, optional type, and display value for a single variable. */
	public record Preview(@Nonnull String name, @Nullable String type, @Nonnull String value) {}
}
