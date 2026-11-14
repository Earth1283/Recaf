package software.coley.recaf.ui.pane.crypto;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.util.ErrorDialogs;
import software.coley.recaf.util.FileChooserBuilder;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.crypto.ByteFormat;
import software.coley.recaf.util.crypto.CryptoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Input for a byte value <i>(key, IV, salt, associated data)</i> that can be typed in any {@link ByteFormat}
 * or loaded from a file.
 */
public class BytesField extends HBox {
	/** Key files above this size are almost certainly the wrong file. */
	private static final long MAX_FILE_SIZE = 1024 * 1024;
	private final TextInputControl text;
	private final ComboBox<ByteFormat> format = new ComboBox<>();
	private final Label fileLabel = new Label();
	private final ObjectProperty<byte[]> fileBytes = new SimpleObjectProperty<>();
	private final List<Runnable> listeners = new ArrayList<>();

	/**
	 * @param prompt
	 * 		Placeholder text.
	 * @param defaultFormat
	 * 		Initial format.
	 * @param multiline
	 * 		{@code true} for a text area, used for PEM keys.
	 */
	public BytesField(@Nonnull String prompt, @Nonnull ByteFormat defaultFormat, boolean multiline) {
		super(4);
		setAlignment(multiline ? Pos.TOP_LEFT : Pos.CENTER_LEFT);
		if (multiline) {
			TextArea area = new TextArea();
			area.setPrefRowCount(4);
			area.setWrapText(true);
			text = area;
		} else {
			text = new TextField();
		}
		text.setPromptText(prompt);
		text.getStyleClass().add("mono-text");
		format.getItems().setAll(ByteFormat.values());
		format.setValue(defaultFormat);
		format.setMinWidth(110);

		fileLabel.getStyleClass().add(Styles.TEXT_MUTED);
		fileLabel.setMaxWidth(Double.MAX_VALUE);
		StackPane valueStack = new StackPane(text, fileLabel);
		StackPane.setAlignment(fileLabel, Pos.CENTER_LEFT);
		fileLabel.visibleProperty().bind(fileBytes.isNotNull());
		text.visibleProperty().bind(fileBytes.isNull());
		format.disableProperty().bind(fileBytes.isNotNull());

		ActionButton load = new ActionButton(CarbonIcons.DOCUMENT_IMPORT, this::loadFile);
		load.setTooltip(new Tooltip(Lang.get("crypto.bytes.load-file")));
		load.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
		ActionButton clearFile = new ActionButton(CarbonIcons.CLOSE, () -> fileBytes.set(null));
		clearFile.setTooltip(new Tooltip(Lang.get("crypto.bytes.clear-file")));
		clearFile.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
		clearFile.visibleProperty().bind(fileBytes.isNotNull());
		clearFile.managedProperty().bind(clearFile.visibleProperty());

		HBox.setHgrow(valueStack, Priority.ALWAYS);
		getChildren().addAll(valueStack, format, load, clearFile);

		text.textProperty().addListener((ob, old, cur) -> fireChanged());
		format.valueProperty().addListener((ob, old, cur) -> fireChanged());
		fileBytes.addListener((ob, old, cur) -> fireChanged());
	}

	/**
	 * @param listener
	 * 		Called whenever the value changes.
	 */
	public void addListener(@Nonnull Runnable listener) {
		listeners.add(listener);
	}

	private void fireChanged() {
		listeners.forEach(Runnable::run);
	}

	/**
	 * @return {@code true} when no value is entered.
	 */
	public boolean isBlank() {
		return fileBytes.get() == null && text.getText().isEmpty();
	}

	/**
	 * @return Entered bytes, empty when {@link #isBlank() blank}.
	 *
	 * @throws CryptoException
	 * 		When the text is not valid in the selected format.
	 */
	@Nonnull
	public byte[] getBytes() throws CryptoException {
		byte[] file = fileBytes.get();
		if (file != null)
			return file.clone();
		return format.getValue().parse(text.getText());
	}

	/**
	 * @return Entered bytes, or {@code null} when {@link #isBlank() blank}.
	 *
	 * @throws CryptoException
	 * 		When the text is not valid in the selected format.
	 */
	@Nullable
	public byte[] getBytesOrNull() throws CryptoException {
		return isBlank() ? null : getBytes();
	}

	/**
	 * @param value
	 * 		Bytes to show, or {@code null} to clear.
	 */
	public void setBytes(@Nullable byte[] value) {
		fileBytes.set(null);
		if (value == null) {
			text.setText("");
			return;
		}
		ByteFormat current = format.getValue();
		if ((current == ByteFormat.UTF8 || current == ByteFormat.LATIN1) && !isPrintable(value))
			format.setValue(current = ByteFormat.HEX);
		text.setText(current.format(value));
	}

	/**
	 * @return Text value as typed.
	 */
	@Nonnull
	public String getText() {
		return text.getText();
	}

	/**
	 * @param value
	 * 		Text to show.
	 * @param newFormat
	 * 		Format of the text.
	 */
	public void setText(@Nonnull String value, @Nonnull ByteFormat newFormat) {
		fileBytes.set(null);
		format.setValue(newFormat);
		text.setText(value);
	}

	/**
	 * @return Selected format.
	 */
	@Nonnull
	public ByteFormat getFormat() {
		return format.getValue();
	}

	private void loadFile() {
		File file = new FileChooserBuilder()
				.setTitle(Lang.get("crypto.bytes.load-file"))
				.open(getScene() == null ? null : getScene().getWindow());
		if (file == null)
			return;
		try {
			if (Files.size(file.toPath()) > MAX_FILE_SIZE)
				throw new IOException(Lang.get("crypto.bytes.file-too-large"));
			byte[] data = Files.readAllBytes(file.toPath());
			fileLabel.setText(file.getName() + " (" + data.length + " B)");
			fileBytes.set(data);
		} catch (IOException ex) {
			ErrorDialogs.show(Lang.get("crypto.bytes.load-file"), file.getName(), String.valueOf(ex.getMessage()), ex);
		}
	}

	private static boolean isPrintable(@Nonnull byte[] data) {
		for (byte b : data) {
			int c = b & 0xFF;
			if (c < 0x20 || c >= 0x7F)
				return false;
		}
		return true;
	}
}
