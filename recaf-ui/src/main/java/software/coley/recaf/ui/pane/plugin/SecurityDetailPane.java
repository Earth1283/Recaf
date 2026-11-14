package software.coley.recaf.ui.pane.plugin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.Lang;

import java.util.function.Consumer;

/**
 * Explains the entry selected in the security triage: why the rule matters, what was found where, the evidence of
 * combined findings and the code itself, so that a finding can be judged without opening it.
 */
public class SecurityDetailPane extends SplitPane {
	private final VBox content = new VBox(10);
	private final SecurityCodePreview preview;
	private final Consumer<SecurityFinding> opener;

	/**
	 * @param preview
	 * 		Code preview shown below the explanation.
	 * @param opener
	 * 		Action that opens a finding in the editor.
	 */
	public SecurityDetailPane(@Nonnull SecurityCodePreview preview, @Nonnull Consumer<SecurityFinding> opener) {
		super();
		this.preview = preview;
		this.opener = opener;
		setOrientation(Orientation.VERTICAL);
		content.setPadding(new Insets(10));
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMinHeight(80);
		preview.setMinHeight(80);
		getItems().addAll(scroll, preview);
		setDividerPositions(0.38);
		showNothing();
	}

	/**
	 * Shows the hint for when nothing is selected.
	 */
	public void showNothing() {
		preview.clear();
		Label hint = new Label(Lang.get("mcplugin.security.detail.hint"));
		hint.getStyleClass().add(Styles.TEXT_SUBTLE);
		hint.setWrapText(true);
		content.getChildren().setAll(hint);
	}

	/**
	 * Shows a severity group.
	 *
	 * @param severity
	 * 		Severity of the group.
	 * @param count
	 * 		Number of findings listed in the group.
	 */
	public void showSeverity(@Nonnull Severity severity, int count) {
		preview.clear();
		content.getChildren().setAll(header(SecurityDisplay.name(severity), SecurityDisplay.icon(severity)), subtle(countText(count)));
	}

	/**
	 * Shows a rule group.
	 *
	 * @param rule
	 * 		Rule of the group.
	 * @param count
	 * 		Number of findings listed in the group.
	 */
	public void showRule(@Nonnull SecurityRule rule, int count) {
		preview.clear();
		content.getChildren().setAll(
				header(SecurityDisplay.title(rule), SecurityDisplay.icon(rule.severity())),
				severityLine(rule.severity()),
				subtle(countText(count)),
				explanation(rule));
	}

	/**
	 * Shows a finding or one piece of evidence of a combined finding. The code is not shown until
	 * {@link #showPreview(SecurityFinding)} is called, which callers delay while the selection is still moving.
	 *
	 * @param finding
	 * 		Finding to explain.
	 * @param parent
	 * 		Combined finding that the finding is evidence of, or {@code null} when it is not evidence.
	 */
	public void showFinding(@Nonnull SecurityFinding finding, @Nullable SecurityFinding parent) {
		content.getChildren().setAll(
				header(SecurityDisplay.title(finding.rule()), SecurityDisplay.icon(finding.severity())),
				severityLine(finding.severity()),
				explanation(finding.rule()));

		GridPane facts = new GridPane();
		facts.setHgap(12);
		facts.setVgap(4);
		ColumnConstraints names = new ColumnConstraints();
		ColumnConstraints values = new ColumnConstraints();
		values.setHgrow(Priority.ALWAYS);
		values.setFillWidth(true);
		facts.getColumnConstraints().addAll(names, values);
		int row = 0;
		CodeLocation location = finding.location();
		if (parent != null)
			row = fact(facts, row, "mcplugin.security.fact.part-of", SecurityDisplay.title(parent.rule()), null);
		if (location != null)
			row = fact(facts, row, "mcplugin.security.fact.where", location.display(), location.toString());
		if (finding.file() != null)
			row = fact(facts, row, "mcplugin.security.fact.file", finding.file(), null);
		if (finding.detail() != null && !finding.detail().isBlank())
			row = fact(facts, row, "mcplugin.security.fact.what", finding.detail(), null);
		if (finding.embeddedIn() != null)
			row = fact(facts, row, "mcplugin.security.fact.inside", finding.embeddedIn(), null);
		if (row > 0)
			content.getChildren().add(facts);
		if (finding.library()) {
			Label note = subtle(Lang.get("mcplugin.security.fact.library"));
			note.setGraphic(new FontIconView(CarbonIcons.INFORMATION));
			content.getChildren().add(note);
		}

		if (!finding.evidence().isEmpty()) {
			Label title = new Label(Lang.get("mcplugin.security.evidence"));
			title.getStyleClass().add(Styles.TEXT_BOLD);
			VBox list = new VBox(2);
			for (SecurityFinding part : finding.evidence())
				list.getChildren().add(evidenceLink(part));
			content.getChildren().addAll(title, list);
		}

		Button open = new Button(Lang.get(location == null ? "mcplugin.security.open-file" : "mcplugin.security.open"),
				new FontIconView(location == null ? CarbonIcons.DOCUMENT : CarbonIcons.CODE));
		open.setOnAction(e -> opener.accept(finding));
		Button copy = new Button(Lang.get("mcplugin.security.copy"), new FontIconView(CarbonIcons.COPY));
		copy.setOnAction(e -> ClipboardUtil.copyString(SecurityDisplay.summary(finding)));
		HBox buttons = new HBox(6, open, copy);
		buttons.setAlignment(Pos.CENTER_LEFT);
		content.getChildren().add(buttons);
	}

	/**
	 * @param finding
	 * 		Finding to show the code of in the preview below the explanation.
	 */
	public void showPreview(@Nullable SecurityFinding finding) {
		preview.show(finding);
	}

	/**
	 * Releases the code preview.
	 */
	public void release() {
		preview.release();
	}

	@Nonnull
	private Node evidenceLink(@Nonnull SecurityFinding part) {
		StringBuilder text = new StringBuilder(SecurityDisplay.title(part.rule()));
		text.append(": ").append(part.location() == null ? part.file() : part.location().display());
		if (part.detail() != null && !part.detail().isBlank())
			text.append("  ").append(part.detail());
		Hyperlink link = new Hyperlink(text.toString());
		link.setWrapText(true);
		link.setGraphic(SecurityDisplay.icon(part.severity()));
		link.setTooltip(new Tooltip(part.location() == null ? part.file() : part.location().toString()));
		link.setOnAction(e -> opener.accept(part));
		return link;
	}

	private int fact(@Nonnull GridPane grid, int row, @Nonnull String key, @Nonnull String value, @Nullable String tip) {
		Label name = new Label(Lang.get(key));
		name.getStyleClass().addAll(Styles.TEXT_SUBTLE);
		name.setMinWidth(Label.USE_PREF_SIZE);
		Label text = new Label(value);
		text.setWrapText(true);
		text.setMinHeight(Label.USE_PREF_SIZE);
		if (tip != null)
			text.setTooltip(new Tooltip(tip));
		GridPane.setValignment(name, javafx.geometry.VPos.TOP);
		grid.add(name, 0, row);
		grid.add(text, 1, row);
		return row + 1;
	}

	@Nonnull
	private static Label header(@Nonnull String text, @Nonnull Node icon) {
		Label label = new Label(text, icon);
		label.getStyleClass().add(Styles.TITLE_4);
		label.setWrapText(true);
		label.setGraphicTextGap(8);
		return label;
	}

	@Nonnull
	private static Label severityLine(@Nonnull Severity severity) {
		return subtle(SecurityDisplay.name(severity));
	}

	@Nonnull
	private static Label explanation(@Nonnull SecurityRule rule) {
		Label label = new Label(SecurityDisplay.tip(rule));
		label.setWrapText(true);
		label.setMinHeight(Label.USE_PREF_SIZE);
		return label;
	}

	@Nonnull
	private static String countText(int count) {
		return count == 1 ? Lang.get("mcplugin.security.detail.count.one") : String.format(Lang.get("mcplugin.security.detail.count"), count);
	}

	@Nonnull
	private static Label subtle(@Nonnull String text) {
		Label label = new Label(text);
		label.getStyleClass().add(Styles.TEXT_SUBTLE);
		label.setWrapText(true);
		label.setMinHeight(Label.USE_PREF_SIZE);
		return label;
	}
}
