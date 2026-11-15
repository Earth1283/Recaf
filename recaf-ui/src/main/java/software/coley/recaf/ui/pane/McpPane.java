package software.coley.recaf.ui.pane;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpCall;
import software.coley.recaf.services.mcp.McpClientSetup;
import software.coley.recaf.services.mcp.McpServerConfig;
import software.coley.recaf.services.mcp.McpServerService;
import software.coley.recaf.services.mcp.McpTool;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.ObservableCheckBox;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static software.coley.recaf.util.Lang.getBinding;

/**
 * Pane for managing {@link McpServerService}: its endpoints, permissions, exposed tools, and what clients have done.
 *
 * @author Matt Coley
 * @see McpServerConfig
 */
@Dependent
public class McpPane extends BorderPane {
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int MAX_PORT = 65535;
	private final McpServerService service;
	private final McpServerConfig config;
	private final Label httpStatus = new Label();
	private final Label stdioStatus = new Label();
	private final Label failureNotice = new Label();
	private final TextField portField = new TextField();
	private final PasswordField tokenField = new PasswordField();
	private final TextField activeToken = new TextField();
	private final CheckBox httpBox = new CheckBox();
	private final CheckBox writeBox;
	private final CheckBox executeBox;
	private final Label permissionNote = new Label();
	private final List<ActionButton> httpSnippetButtons;
	private final TableView<McpTool> toolTable = new TableView<>();
	private final ObservableList<McpTool> allTools = FXCollections.observableArrayList();
	private final ObservableList<McpCall> calls = FXCollections.observableArrayList();
	private final Label toolCount = new Label();
	private final Label callCount = new Label();
	private boolean tokenRevealed;

	@Inject
	public McpPane(@Nonnull McpServerService service, @Nonnull McpServerConfig config) {
		this.service = service;
		this.config = config;

		writeBox = new ObservableCheckBox(config.getAllowWrite(), getBinding("mcp.perm.write"));
		executeBox = new ObservableCheckBox(config.getAllowExecute(), getBinding("mcp.perm.execute"));
		ActionButton copyClaudeHttp = new ActionButton(CarbonIcons.COPY, getBinding("mcp.snippet.claude-http"),
				() -> ClipboardUtil.copyString(McpClientSetup.claudeCodeHttp(service.getHttpPort(), tokenOrEmpty())));
		ActionButton copyJsonHttp = new ActionButton(CarbonIcons.COPY, getBinding("mcp.snippet.json-http"),
				() -> ClipboardUtil.copyString(McpClientSetup.jsonHttp(service.getHttpPort(), tokenOrEmpty())));
		httpSnippetButtons = List.of(copyClaudeHttp, copyJsonHttp);

		TabPane tabs = new TabPane(
				tab("mcp.tab.connection", CarbonIcons.PLUG, connectionTab(copyClaudeHttp, copyJsonHttp)),
				tab("mcp.tab.tools", CarbonIcons.CATALOG, toolsTab()),
				tab("mcp.tab.activity", CarbonIcons.ACTIVITY, activityTab()));
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		setCenter(tabs);

		allTools.setAll(service.getAllTools());
		calls.setAll(service.getRecentCalls());
		service.addStatusListener(() -> FxThreadUtil.run(this::refresh));
		service.addCallListener(call -> FxThreadUtil.run(() -> {
			calls.add(0, call);
			while (calls.size() > 200)
				calls.remove(calls.size() - 1);
			callCount.setText(String.valueOf(calls.size()));
		}));
		refresh();
	}

	@Nonnull
	private Node connectionTab(@Nonnull ActionButton copyClaudeHttp, @Nonnull ActionButton copyJsonHttp) {
		// Status
		stdioStatus.setWrapText(true);
		httpStatus.setWrapText(true);
		failureNotice.setWrapText(true);
		failureNotice.getStyleClass().add(Styles.DANGER);
		failureNotice.textProperty().bind(getBinding("mcp.status.failed"));
		VBox status = section("mcp.section.status",
				row("mcp.status.http", httpStatus),
				row("mcp.status.stdio", stdioStatus),
				failureNotice);

		// HTTP
		httpBox.textProperty().bind(getBinding("mcp.http.enable"));
		httpBox.setOnAction(e -> {
			if (httpBox.isSelected()) {
				// The config listener starts the server when the value changes, otherwise a failed start is retried.
				if (config.getHttpEnabled().getValue())
					service.restartHttp();
				else
					config.getHttpEnabled().setValue(true);
			} else {
				config.getHttpEnabled().setValue(false);
				service.stopHttp();
			}
			refresh();
		});
		portField.setPrefColumnCount(6);
		portField.setTextFormatter(new TextFormatter<>(change ->
				change.getControlNewText().matches("\\d{0,5}") ? change : null));
		portField.setOnAction(e -> applyPort());
		ActionButton applyPort = new ActionButton(CarbonIcons.CHECKMARK, getBinding("mcp.http.apply"), this::applyPort);
		HBox portRow = row("mcp.http.port", portField, applyPort);

		tokenField.setPromptText(Lang.get("mcp.http.token.prompt"));
		tokenField.setPrefColumnCount(26);
		tokenField.setText(config.getHttpToken().getValue());
		tokenField.setOnAction(e -> applyToken(tokenField.getText()));
		ActionButton saveToken = new ActionButton(CarbonIcons.CHECKMARK, getBinding("mcp.http.apply"),
				() -> applyToken(tokenField.getText()));
		ActionButton generate = new ActionButton(CarbonIcons.RESET, getBinding("mcp.http.token.generate"), () -> {
			byte[] bytes = new byte[24];
			new SecureRandom().nextBytes(bytes);
			String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			tokenField.setText(token);
			applyToken(token);
		});
		HBox tokenRow = row("mcp.http.token", tokenField, saveToken, generate);
		Label tokenHint = subtle("mcp.http.token.hint");

		activeToken.setEditable(false);
		activeToken.setPrefColumnCount(26);
		ActionButton reveal = new ActionButton(CarbonIcons.VIEW, getBinding("mcp.http.token.show"), () -> {
			tokenRevealed = !tokenRevealed;
			refresh();
		});
		ActionButton copyToken = new ActionButton(CarbonIcons.COPY, getBinding("mcp.http.token.copy"),
				() -> ClipboardUtil.copyString(tokenOrEmpty()));
		HBox activeRow = row("mcp.http.token.active", activeToken, reveal, copyToken);
		VBox http = section("mcp.section.http", httpBox, portRow, tokenRow, tokenHint, activeRow);

		// Permissions
		Label writeHint = subtle("mcp.perm.write.hint");
		Label executeWarning = new Label();
		executeWarning.setGraphic(new FontIconView(CarbonIcons.WARNING_ALT));
		executeWarning.setWrapText(true);
		executeWarning.getStyleClass().add(Styles.WARNING);
		executeWarning.textProperty().bind(getBinding("mcp.perm.execute.warning"));
		permissionNote.setWrapText(true);
		permissionNote.getStyleClass().add(Styles.TEXT_SUBTLE);
		VBox permissions = section("mcp.section.permissions", subtle("mcp.perm.read"), writeBox, writeHint,
				executeBox, executeWarning, permissionNote);

		// Client snippets
		ActionButton copyClaudeStdio = new ActionButton(CarbonIcons.COPY, getBinding("mcp.snippet.claude-stdio"),
				() -> ClipboardUtil.copyString(McpClientSetup.claudeCodeStdio(McpClientSetup.javaExecutable(),
						McpClientSetup.recafJar())));
		ActionButton copyJsonStdio = new ActionButton(CarbonIcons.COPY, getBinding("mcp.snippet.json-stdio"),
				() -> ClipboardUtil.copyString(McpClientSetup.jsonStdio(McpClientSetup.javaExecutable(),
						McpClientSetup.recafJar())));
		FlowPane snippets = new FlowPane(8, 8, copyClaudeHttp, copyJsonHttp, copyClaudeStdio, copyJsonStdio);
		VBox clients = section("mcp.section.clients", subtle("mcp.snippet.hint"), snippets);

		VBox content = new VBox(18, status, http, permissions, clients);
		content.setPadding(new Insets(15));
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		return scroll;
	}

	@Nonnull
	private Node toolsTab() {
		FilteredList<McpTool> filtered = new FilteredList<>(allTools);
		TextField filter = new TextField();
		filter.promptTextProperty().bind(getBinding("mcp.tools.filter"));
		filter.textProperty().addListener((ob, old, cur) -> {
			String needle = cur == null ? "" : cur.toLowerCase(Locale.ROOT);
			filtered.setPredicate(tool -> needle.isEmpty() || tool.name().contains(needle) ||
					tool.description().toLowerCase(Locale.ROOT).contains(needle));
		});

		TableColumn<McpTool, String> enabled = new TableColumn<>();
		enabled.textProperty().bind(getBinding("mcp.tools.enabled"));
		enabled.setCellValueFactory(c -> new ReadOnlyStringWrapper(
				service.getAllowedAccess().contains(c.getValue().access()) ? "✓" : "—"));
		enabled.setMaxWidth(90);
		enabled.setMinWidth(70);
		TableColumn<McpTool, String> name = new TableColumn<>();
		name.textProperty().bind(getBinding("mcp.tools.name"));
		name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
		name.setPrefWidth(220);
		TableColumn<McpTool, McpAccess> tier = new TableColumn<>();
		tier.textProperty().bind(getBinding("mcp.tools.tier"));
		tier.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().access()));
		tier.setMaxWidth(110);
		tier.setMinWidth(90);
		TableColumn<McpTool, String> description = new TableColumn<>();
		description.textProperty().bind(getBinding("mcp.tools.description"));
		description.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().description()));
		description.setPrefWidth(520);
		toolTable.getColumns().addAll(List.of(enabled, name, tier, description));
		toolTable.setItems(filtered);
		toolTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		toolTable.placeholderProperty().set(new Label());

		HBox top = new HBox(10, filter, toolCount);
		top.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(filter, Priority.ALWAYS);
		top.setPadding(new Insets(10));
		BorderPane pane = new BorderPane(toolTable);
		pane.setTop(top);
		return pane;
	}

	@Nonnull
	private Node activityTab() {
		TableColumn<McpCall, String> time = new TableColumn<>();
		time.textProperty().bind(getBinding("mcp.activity.time"));
		time.setCellValueFactory(c -> new ReadOnlyStringWrapper(TIME_FORMAT.format(c.getValue().time())));
		time.setMaxWidth(100);
		time.setMinWidth(80);
		TableColumn<McpCall, String> tool = new TableColumn<>();
		tool.textProperty().bind(getBinding("mcp.activity.tool"));
		tool.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tool()));
		tool.setPrefWidth(200);
		TableColumn<McpCall, String> result = new TableColumn<>();
		result.textProperty().bind(getBinding("mcp.activity.result"));
		result.setCellValueFactory(c -> new ReadOnlyStringWrapper(Lang.get(c.getValue().success()
				? "mcp.activity.ok" : "mcp.activity.error")));
		result.setMaxWidth(100);
		result.setMinWidth(80);
		TableColumn<McpCall, String> duration = new TableColumn<>();
		duration.textProperty().bind(getBinding("mcp.activity.duration"));
		duration.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().millis() + " ms"));
		duration.setMaxWidth(100);
		duration.setMinWidth(80);
		TableColumn<McpCall, String> arguments = new TableColumn<>();
		arguments.textProperty().bind(getBinding("mcp.activity.arguments"));
		arguments.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().summary()));
		arguments.setPrefWidth(480);
		TableView<McpCall> table = new TableView<>(calls);
		table.getColumns().addAll(List.of(time, tool, result, duration, arguments));
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		Label empty = new Label();
		empty.textProperty().bind(getBinding("mcp.activity.empty"));
		table.setPlaceholder(empty);

		ActionButton clear = new ActionButton(CarbonIcons.TRASH_CAN, getBinding("mcp.activity.clear"),
				service::clearRecentCalls);
		HBox top = new HBox(10, clear, callCount);
		top.setAlignment(Pos.CENTER_LEFT);
		top.setPadding(new Insets(10));
		BorderPane pane = new BorderPane(table);
		pane.setTop(top);
		return pane;
	}

	private void applyPort() {
		String text = portField.getText().trim();
		int port;
		try {
			port = Integer.parseInt(text);
		} catch (NumberFormatException ex) {
			refresh();
			return;
		}
		port = Math.max(0, Math.min(MAX_PORT, port));
		config.getHttpPort().setValue(port);
		if (config.getHttpEnabled().getValue())
			service.restartHttp();
		refresh();
	}

	private void applyToken(@Nonnull String token) {
		config.getHttpToken().setValue(token.trim());
		if (config.getHttpEnabled().getValue())
			service.restartHttp();
		refresh();
	}

	/**
	 * Updates everything that reflects the service's live state.
	 */
	private void refresh() {
		boolean running = service.isHttpRunning();
		if (running) {
			httpStatus.setText(Lang.get("mcp.status.listening") + "  " + McpClientSetup.url(service.getHttpPort()) +
					(config.getHttpEnabled().getValue() ? "" : "  (" + Lang.get("mcp.status.this-session") + ")"));
			httpStatus.getStyleClass().remove(Styles.TEXT_SUBTLE);
			if (!httpStatus.getStyleClass().contains(Styles.SUCCESS))
				httpStatus.getStyleClass().add(Styles.SUCCESS);
		} else {
			httpStatus.setText(Lang.get("mcp.status.stopped"));
			httpStatus.getStyleClass().remove(Styles.SUCCESS);
			if (!httpStatus.getStyleClass().contains(Styles.TEXT_SUBTLE))
				httpStatus.getStyleClass().add(Styles.TEXT_SUBTLE);
		}
		httpBox.setSelected(running);
		failureNotice.setVisible(config.getHttpEnabled().getValue() && !running);
		failureNotice.setManaged(failureNotice.isVisible());
		stdioStatus.setText(Lang.get(service.isStdioRunning() ? "mcp.status.stdio.active" : "mcp.status.stdio.inactive"));

		if (!portField.isFocused())
			portField.setText(String.valueOf(config.getHttpPort().getValue()));
		String token = service.getHttpToken();
		activeToken.setText(token == null ? "" : tokenRevealed ? token : "•".repeat(Math.min(24, token.length())));
		httpSnippetButtons.forEach(button -> button.setDisable(!running));

		writeBox.setDisable(service.isWriteLockedByLaunch());
		executeBox.setDisable(service.isExecuteForcedByLaunch());
		String note = "";
		if (service.isWriteLockedByLaunch())
			note += Lang.get("mcp.perm.locked-readonly") + "\n";
		if (service.isExecuteForcedByLaunch())
			note += Lang.get("mcp.perm.locked-execute");
		permissionNote.setText(note.trim());
		permissionNote.setVisible(!note.isBlank());
		permissionNote.setManaged(permissionNote.isVisible());

		Set<McpAccess> allowed = service.getAllowedAccess();
		allTools.setAll(service.getAllTools());
		toolTable.refresh();
		toolCount.setText(Lang.get("mcp.tools.exposed").formatted(
				allTools.stream().filter(t -> allowed.contains(t.access())).count(), allTools.size()));
		calls.setAll(service.getRecentCalls());
		callCount.setText(String.valueOf(calls.size()));
	}

	@Nonnull
	private String tokenOrEmpty() {
		String token = service.getHttpToken();
		return token == null ? "" : token;
	}

	@Nonnull
	private static Tab tab(@Nonnull String key, @Nonnull CarbonIcons icon, @Nonnull Node content) {
		Tab tab = new Tab();
		tab.textProperty().bind(getBinding(key));
		tab.setGraphic(new FontIconView(icon));
		tab.setContent(content);
		return tab;
	}

	@Nonnull
	private static VBox section(@Nonnull String titleKey, @Nonnull Node... children) {
		Label title = new Label();
		title.textProperty().bind(getBinding(titleKey));
		title.getStyleClass().add(Styles.TITLE_4);
		VBox box = new VBox(8, title);
		box.getChildren().addAll(children);
		return box;
	}

	@Nonnull
	private static HBox row(@Nonnull String labelKey, @Nonnull Node... nodes) {
		Label label = new Label();
		label.textProperty().bind(getBinding(labelKey));
		label.setMinWidth(130);
		HBox box = new HBox(8, label);
		box.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().addAll(nodes);
		return box;
	}

	@Nonnull
	private static Label subtle(@Nonnull String key) {
		Label label = new Label();
		label.textProperty().bind(getBinding(key));
		label.setWrapText(true);
		label.getStyleClass().add(Styles.TEXT_SUBTLE);
		return label;
	}
}
