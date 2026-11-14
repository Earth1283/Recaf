package software.coley.recaf.ui.pane.crypto;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.crypto.DataInspector;
import software.coley.recaf.util.crypto.Recipe;

/**
 * Small window showing the result of a crypto action picked from a context menu.
 */
public class CryptoResultPopup extends RecafStage {
	/**
	 * @param result
	 * 		Result to show.
	 * @param title
	 * 		Action that produced it.
	 * @param openInTools
	 * 		Opens the input and steps in the full crypto tools window.
	 */
	public CryptoResultPopup(@Nonnull Recipe.Result result, @Nonnull String title, @Nonnull Runnable openInTools) {
		setTitle(title);
		hideOnEscape();

		TextArea area = new TextArea();
		area.setEditable(false);
		area.setWrapText(true);
		area.getStyleClass().add("mono-text");
		Label info = new Label();
		ComboBox<OutputFormat> format = new ComboBox<>();
		format.getItems().setAll(OutputFormat.values());

		byte[] output = result.output();
		if (output != null) {
			DataInspector.Score score = DataInspector.score(output);
			info.setText(Lang.format("crypto.info.bytes", output.length).get() + " · " + score.kind());
			format.valueProperty().addListener((ob, old, cur) -> area.setText(cur.format(output)));
			format.setValue(score.isText() ? OutputFormat.UTF8 : OutputFormat.HEX_DUMP);
		} else {
			info.setText(String.valueOf(result.error()));
			info.getStyleClass().add(Styles.DANGER);
			format.setDisable(true);
		}

		ActionButton copy = new ActionButton(CarbonIcons.COPY, Lang.getBinding("crypto.output.copy"), () -> ClipboardUtil.copyString(area.getText()));
		copy.setDisable(output == null);
		ActionButton open = new ActionButton(CarbonIcons.LAUNCH, Lang.getBinding("crypto.menu.open"), () -> {
			openInTools.run();
			close();
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox bottom = new HBox(8, info, spacer, format, copy, open);
		bottom.setAlignment(Pos.CENTER_LEFT);
		VBox root = new VBox(8, area, bottom);
		root.setPadding(new Insets(10));
		VBox.setVgrow(area, Priority.ALWAYS);
		setScene(new RecafScene(root, 720, 420));
	}
}
