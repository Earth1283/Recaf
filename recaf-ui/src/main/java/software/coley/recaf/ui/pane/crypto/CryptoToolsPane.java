package software.coley.recaf.ui.pane.crypto;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.BoundTab;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.ErrorDialogs;
import software.coley.recaf.util.FileChooserBuilder;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.crypto.ByteFormat;
import software.coley.recaf.util.crypto.CipherBruteForcer;
import software.coley.recaf.util.crypto.Codec;
import software.coley.recaf.util.crypto.CryptoException;
import software.coley.recaf.util.crypto.CryptoStep;
import software.coley.recaf.util.crypto.DataInspector;
import software.coley.recaf.util.crypto.HashAlgorithm;
import software.coley.recaf.util.crypto.Recipe;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Crypto tools: an input, a chain of decoding/cipher/hash steps run live on it, a brute force search over cipher
 * configurations, a table of hashes, and the output in any representation.
 */
@Dependent
public class CryptoToolsPane extends BorderPane {
	private static final Logger logger = Logging.get(CryptoToolsPane.class);
	/** Inputs above this size are kept as bytes instead of being shown as editable text. */
	private static final int MAX_TEXT_INPUT = 256 * 1024;
	private static final int MAX_BRUTE_KEYS = 2000;
	private final WorkspaceManager workspaceManager;
	private final ExecutorService executor = ThreadPoolFactory.newSingleThreadExecutor("crypto-tools");
	private final ExecutorService bruteExecutor = ThreadPoolFactory.newSingleThreadExecutor("crypto-brute-force");
	private final PauseTransition runDelay = new PauseTransition(Duration.millis(150));
	private final ObservableList<StepModel> steps = FXCollections.observableArrayList();
	private final ListView<StepModel> stepList = new ListView<>(steps);
	private final ScrollPane stepEditor = new ScrollPane();
	private final TabPane tools = new TabPane();
	private final Tab recipeTab;
	private final Tab bruteTab;
	private final Tab hashTab;

	private final TextArea inputArea = new TextArea();
	private final ComboBox<ByteFormat> inputFormat = new ComboBox<>();
	private final Label inputSource = new Label();
	private final Label inputInfo = new Label();
	private byte[] binaryInput;

	private final TextArea outputArea = new TextArea();
	private final ComboBox<OutputFormat> outputFormat = new ComboBox<>();
	private final Label outputInfo = new Label();
	private byte[] lastOutput;
	private int runGeneration;

	private final TableView<HashRow> hashTable = new TableView<>();
	private final BytesField hmacKey = new BytesField(Lang.get("crypto.prompt.hmac-key"), ByteFormat.UTF8, false);

	private final TextArea bruteKeys = new TextArea();
	private final ComboBox<ByteFormat> bruteKeyFormat = new ComboBox<>();
	private final BytesField bruteIv = new BytesField(Lang.get("crypto.prompt.optional"), ByteFormat.HEX, false);
	private final BytesField bruteSalt = new BytesField(Lang.get("crypto.prompt.optional"), ByteFormat.HEX, false);
	private final Spinner<Integer> bruteIterations = new Spinner<>(0, Integer.MAX_VALUE, 0);
	private final CheckBox bruteExhaustive = new CheckBox();
	private final CheckBox bruteDecodeInput = new CheckBox();
	private final CheckBox bruteXor = new CheckBox();
	private final CheckBox bruteWorkspaceKeys = new CheckBox();
	private final ProgressBar bruteProgress = new ProgressBar(0);
	private final Label bruteStatus = new Label();
	private final TableView<CipherBruteForcer.Candidate> bruteResults = new TableView<>();
	private final ActionButton bruteRun;
	private final ActionButton bruteCancel;
	private AtomicBoolean bruteCancelled;

	@Inject
	public CryptoToolsPane(@Nonnull WorkspaceManager workspaceManager) {
		this.workspaceManager = workspaceManager;
		getStyleClass().add("crypto-tools");

		recipeTab = new BoundTab(Lang.getBinding("crypto.tab.recipe"), CarbonIcons.FLOW, createRecipeTab());
		bruteRun = new ActionButton(CarbonIcons.PLAY, Lang.getBinding("crypto.brute.run"), this::runBruteForce);
		bruteCancel = new ActionButton(CarbonIcons.STOP, Lang.getBinding("crypto.brute.cancel"), this::cancelBruteForce);
		bruteTab = new BoundTab(Lang.getBinding("crypto.tab.brute"), CarbonIcons.CHIP, createBruteTab());
		hashTab = new BoundTab(Lang.getBinding("crypto.tab.hashes"), CarbonIcons.FINGERPRINT_RECOGNITION, createHashTab());
		tools.getTabs().addAll(recipeTab, bruteTab, hashTab);
		tools.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tools.getSelectionModel().selectedItemProperty().addListener((ob, old, cur) -> {
			if (cur == hashTab)
				refreshHashes();
		});

		SplitPane split = new SplitPane(createInputSection(), tools, createOutputSection());
		split.setOrientation(Orientation.VERTICAL);
		split.setDividerPositions(0.2, 0.72);
		setCenter(split);

		runDelay.setOnFinished(e -> runRecipe());
		setInputText(Lang.get("crypto.input.manual"), "", ByteFormat.UTF8);
	}

	/**
	 * Replaces the input with text, as from a string literal.
	 *
	 * @param sourceName
	 * 		Where the text came from.
	 * @param text
	 * 		Input text.
	 */
	public void setInput(@Nonnull String sourceName, @Nonnull String text) {
		boolean binaryChars = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c > 0xFF) {
				binaryChars = false;
				break;
			}
			if (c >= 0x80)
				binaryChars = true;
		}
		setInputText(sourceName, text, binaryChars ? ByteFormat.LATIN1 : ByteFormat.UTF8);
	}

	/**
	 * Replaces the input with bytes, as from a file. Readable text is shown as editable text; other data as hex,
	 * or kept as raw bytes when large.
	 *
	 * @param sourceName
	 * 		Where the bytes came from.
	 * @param data
	 * 		Input bytes.
	 */
	public void setInput(@Nonnull String sourceName, @Nonnull byte[] data) {
		String utf8 = DataInspector.strictUtf8(data);
		if (utf8 != null && data.length <= MAX_TEXT_INPUT && DataInspector.score(data).isText()) {
			setInputText(sourceName, utf8, ByteFormat.UTF8);
		} else if (data.length <= MAX_TEXT_INPUT / 2) {
			setInputText(sourceName, ByteFormat.HEX.format(data), ByteFormat.HEX);
		} else {
			binaryInput = data.clone();
			inputSource.setText(sourceName);
			inputArea.setEditable(false);
			inputFormat.setDisable(true);
			inputArea.setText(Lang.format("crypto.input.binary", data.length).get() + "\n\n"
					+ OutputFormat.HEX_DUMP.format(Arrays.copyOf(data, Math.min(data.length, 4096))));
			scheduleRun();
		}
	}

	private void setInputText(@Nonnull String sourceName, @Nonnull String text, @Nonnull ByteFormat format) {
		binaryInput = null;
		inputArea.setEditable(true);
		inputFormat.setDisable(false);
		inputSource.setText(sourceName);
		inputFormat.setValue(format);
		inputArea.setText(text);
		scheduleRun();
	}

	/**
	 * @param newSteps
	 * 		Steps to replace the recipe with.
	 */
	public void setSteps(@Nonnull List<CryptoStep> newSteps) {
		List<StepModel> models = new ArrayList<>(newSteps.size());
		for (CryptoStep step : newSteps)
			models.add(watch(StepModel.of(step)));
		steps.setAll(models);
		if (!models.isEmpty())
			stepList.getSelectionModel().select(models.getLast());
		tools.getSelectionModel().select(recipeTab);
		scheduleRun();
	}

	/**
	 * @return Enabled, valid steps of the recipe.
	 */
	@Nonnull
	public List<CryptoStep> getSteps() {
		List<CryptoStep> built = new ArrayList<>();
		for (StepModel model : steps) {
			if (!model.enabledProperty().get())
				continue;
			try {
				built.add(model.build());
			} catch (CryptoException | RuntimeException ignored) {
				// Invalid steps are flagged in the list and skipped here.
			}
		}
		return built;
	}

	/**
	 * Selects the brute force tab.
	 */
	public void showBruteForce() {
		tools.getSelectionModel().select(bruteTab);
	}

	/**
	 * Stops background work.
	 */
	public void close() {
		cancelBruteForce();
		runDelay.stop();
		executor.shutdownNow();
		bruteExecutor.shutdownNow();
	}

	@Nonnull
	private Node createInputSection() {
		BoundLabel title = new BoundLabel(Lang.getBinding("crypto.input"));
		title.getStyleClass().add(Styles.TEXT_BOLD);
		inputSource.getStyleClass().add(Styles.TEXT_MUTED);

		inputFormat.getItems().setAll(ByteFormat.values());
		inputFormat.setValue(ByteFormat.UTF8);
		inputFormat.valueProperty().addListener((ob, old, cur) -> scheduleRun());
		inputArea.textProperty().addListener((ob, old, cur) -> {
			if (binaryInput == null)
				scheduleRun();
		});
		inputArea.setWrapText(true);
		inputArea.getStyleClass().add("mono-text");
		inputArea.setPromptText(Lang.get("crypto.input.prompt"));

		ActionButton openFile = flatButton(CarbonIcons.DOCUMENT_IMPORT, "crypto.input.file", this::openInputFile);
		ActionButton pickString = flatButton(CarbonIcons.QUOTES, "crypto.input.workspace-string", this::pickWorkspaceString);
		ActionButton clear = flatButton(CarbonIcons.TRASH_CAN, "crypto.input.clear",
				() -> setInputText(Lang.get("crypto.input.manual"), "", inputFormat.getValue() == null ? ByteFormat.UTF8 : inputFormat.getValue()));

		HBox toolbar = toolbar(title, inputSource, spacer(), new Label(Lang.get("crypto.input.format")), inputFormat,
				openFile, pickString, clear);
		inputInfo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
		inputInfo.setPadding(new Insets(2, 8, 4, 8));
		VBox.setVgrow(inputArea, Priority.ALWAYS);
		return new VBox(toolbar, inputArea, inputInfo);
	}

	@Nonnull
	private Node createOutputSection() {
		BoundLabel title = new BoundLabel(Lang.getBinding("crypto.output"));
		title.getStyleClass().add(Styles.TEXT_BOLD);
		outputInfo.setMinWidth(0);

		outputFormat.getItems().setAll(OutputFormat.values());
		outputFormat.setValue(OutputFormat.UTF8);
		outputFormat.valueProperty().addListener((ob, old, cur) -> renderOutput());
		outputArea.setEditable(false);
		outputArea.setWrapText(true);
		outputArea.getStyleClass().add("mono-text");

		ActionButton copy = flatButton(CarbonIcons.COPY, "crypto.output.copy", () -> ClipboardUtil.copyString(outputArea.getText()));
		ActionButton save = flatButton(CarbonIcons.SAVE, "crypto.output.save", this::saveOutput);
		ActionButton useAsInput = flatButton(CarbonIcons.ARROW_UP, "crypto.output.use-as-input", () -> {
			if (lastOutput != null) {
				byte[] output = lastOutput;
				steps.clear();
				setInput(Lang.get("crypto.input.previous-output"), output);
			}
		});
		HBox toolbar = toolbar(title, outputInfo, spacer(), new Label(Lang.get("crypto.output.view")), outputFormat,
				copy, save, useAsInput);
		VBox.setVgrow(outputArea, Priority.ALWAYS);
		return new VBox(toolbar, outputArea);
	}

	@Nonnull
	private Node createRecipeTab() {
		MenuButton add = new MenuButton(Lang.get("crypto.recipe.add"), new FontIconView(CarbonIcons.ADD));
		for (StepModel.Kind kind : StepModel.Kind.values()) {
			MenuItem item = new MenuItem(Lang.get(kind.langKey()));
			item.setOnAction(e -> addStep(kind.create()));
			add.getItems().add(item);
		}
		add.getStyleClass().add(Styles.FLAT);

		ActionButton remove = flatButton(CarbonIcons.TRASH_CAN, "crypto.recipe.remove", () -> {
			StepModel selected = stepList.getSelectionModel().getSelectedItem();
			if (selected != null) {
				steps.remove(selected);
				scheduleRun();
			}
		});
		ActionButton up = flatButton(CarbonIcons.ARROW_UP, "crypto.recipe.up", () -> moveSelected(-1));
		ActionButton down = flatButton(CarbonIcons.ARROW_DOWN, "crypto.recipe.down", () -> moveSelected(1));
		ActionButton clear = flatButton(CarbonIcons.CLEAN, "crypto.recipe.clear", () -> {
			steps.clear();
			scheduleRun();
		});
		remove.disableProperty().bind(stepList.getSelectionModel().selectedItemProperty().isNull());
		up.disableProperty().bind(stepList.getSelectionModel().selectedItemProperty().isNull());
		down.disableProperty().bind(stepList.getSelectionModel().selectedItemProperty().isNull());

		stepList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
		stepList.setCellFactory(list -> new StepCell());
		stepList.setPlaceholder(new BoundLabel(Lang.getBinding("crypto.recipe.empty")));
		stepList.getSelectionModel().selectedItemProperty().addListener((ob, old, cur) -> showEditor(cur));
		stepList.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DELETE)
				remove.fire();
		});

		HBox toolbar = new HBox(4, add, remove, up, down, clear);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.setPadding(new Insets(4));
		VBox left = new VBox(toolbar, stepList);
		VBox.setVgrow(stepList, Priority.ALWAYS);

		stepEditor.setFitToWidth(true);
		showEditor(null);
		SplitPane split = new SplitPane(left, stepEditor);
		split.setDividerPositions(0.38);
		return split;
	}

	@Nonnull
	private Node createBruteTab() {
		bruteKeyFormat.getItems().setAll(ByteFormat.values());
		bruteKeyFormat.setValue(ByteFormat.UTF8);
		bruteKeys.setPromptText(Lang.get("crypto.brute.keys.prompt"));
		bruteKeys.setPrefRowCount(3);
		bruteKeys.getStyleClass().add("mono-text");
		bruteIterations.setEditable(true);
		bruteExhaustive.setText(Lang.get("crypto.brute.exhaustive"));
		bruteDecodeInput.setText(Lang.get("crypto.brute.decode-input"));
		bruteDecodeInput.setSelected(true);
		bruteXor.setText(Lang.get("crypto.brute.xor"));
		bruteXor.setSelected(true);
		bruteWorkspaceKeys.setText(Lang.get("crypto.brute.workspace-keys"));
		bruteExhaustive.setTooltip(new Tooltip(Lang.get("crypto.brute.exhaustive.tooltip")));
		bruteWorkspaceKeys.setTooltip(new Tooltip(Lang.get("crypto.brute.workspace-keys.tooltip")));

		VBox form = new VBox(8);
		form.setPadding(new Insets(10));
		HBox keysRow = StepModel.row(form, "crypto.brute.keys", bruteKeys);
		keysRow.setAlignment(Pos.TOP_LEFT);
		StepModel.row(form, "crypto.brute.key-format", bruteKeyFormat);
		StepModel.row(form, "crypto.field.iv", bruteIv);
		StepModel.row(form, "crypto.field.salt", bruteSalt);
		StepModel.row(form, "crypto.brute.iterations", bruteIterations);
		VBox checks = new VBox(6, bruteDecodeInput, bruteXor, bruteWorkspaceKeys, bruteExhaustive);
		StepModel.row(form, "crypto.brute.options", checks);

		bruteCancel.setDisable(true);
		bruteProgress.setMaxWidth(Double.MAX_VALUE);
		bruteProgress.setVisible(false);
		bruteStatus.getStyleClass().add(Styles.TEXT_MUTED);
		HBox actions = new HBox(8, bruteRun, bruteCancel, bruteProgress);
		actions.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(bruteProgress, Priority.ALWAYS);
		form.getChildren().addAll(actions, bruteStatus);

		TableColumn<CipherBruteForcer.Candidate, String> scoreColumn = new TableColumn<>(Lang.get("crypto.brute.column.score"));
		scoreColumn.setCellValueFactory(p -> new SimpleStringProperty(String.format(Locale.ROOT, "%.0f%%", p.getValue().score() * 100)));
		scoreColumn.setPrefWidth(60);
		TableColumn<CipherBruteForcer.Candidate, String> kindColumn = new TableColumn<>(Lang.get("crypto.brute.column.kind"));
		kindColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().kind()));
		kindColumn.setPrefWidth(110);
		TableColumn<CipherBruteForcer.Candidate, String> configColumn = new TableColumn<>(Lang.get("crypto.brute.column.config"));
		configColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().describe()));
		configColumn.setPrefWidth(380);
		TableColumn<CipherBruteForcer.Candidate, String> previewColumn = new TableColumn<>(Lang.get("crypto.brute.column.preview"));
		previewColumn.setCellValueFactory(p -> new SimpleStringProperty(DataInspector.preview(p.getValue().output(), 120)));
		previewColumn.setPrefWidth(300);
		bruteResults.getColumns().addAll(List.of(scoreColumn, kindColumn, configColumn, previewColumn));
		bruteResults.setPlaceholder(new BoundLabel(Lang.getBinding("crypto.brute.empty")));
		bruteResults.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE);
		bruteResults.setRowFactory(t -> {
			TableRow<CipherBruteForcer.Candidate> row = new TableRow<>();
			row.setOnMouseClicked(e -> {
				if (!row.isEmpty() && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
					setSteps(row.getItem().steps());
			});
			MenuItem use = new MenuItem(Lang.get("crypto.brute.use"), new FontIconView(CarbonIcons.FLOW));
			use.setOnAction(e -> setSteps(row.getItem().steps()));
			MenuItem copy = new MenuItem(Lang.get("crypto.brute.copy-output"), new FontIconView(CarbonIcons.COPY));
			copy.setOnAction(e -> ClipboardUtil.copyString(new String(row.getItem().output(), StandardCharsets.UTF_8)));
			MenuItem copyConfig = new MenuItem(Lang.get("crypto.brute.copy-config"), new FontIconView(CarbonIcons.COPY));
			copyConfig.setOnAction(e -> ClipboardUtil.copyString(row.getItem().describe()));
			row.contextMenuProperty().bind(row.emptyProperty().map(empty -> empty ? null : new ContextMenu(use, copy, copyConfig)));
			return row;
		});
		Label hint = new BoundLabel(Lang.getBinding("crypto.brute.hint"));
		hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
		hint.setPadding(new Insets(2, 6, 2, 6));
		VBox results = new VBox(hint, bruteResults);
		VBox.setVgrow(bruteResults, Priority.ALWAYS);

		ScrollPane formScroll = new ScrollPane(form);
		formScroll.setFitToWidth(true);
		SplitPane split = new SplitPane(formScroll, results);
		split.setDividerPositions(0.4);
		return split;
	}

	@Nonnull
	private Node createHashTab() {
		TableColumn<HashRow, String> nameColumn = new TableColumn<>(Lang.get("crypto.hash.column.algorithm"));
		nameColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().name()));
		nameColumn.setPrefWidth(160);
		TableColumn<HashRow, String> valueColumn = new TableColumn<>(Lang.get("crypto.hash.column.value"));
		valueColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().value()));
		valueColumn.setPrefWidth(700);
		hashTable.getColumns().addAll(List.of(nameColumn, valueColumn));
		hashTable.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE, "mono-text");
		hashTable.setRowFactory(t -> {
			TableRow<HashRow> row = new TableRow<>();
			row.setOnMouseClicked(e -> {
				if (!row.isEmpty() && e.getClickCount() == 2)
					ClipboardUtil.copyString(row.getItem().value());
			});
			return row;
		});
		hmacKey.addListener(this::refreshHashes);
		Label hint = new BoundLabel(Lang.getBinding("crypto.hash.hint"));
		hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
		VBox top = new VBox(6);
		top.setPadding(new Insets(6));
		StepModel.row(top, "crypto.prompt.hmac-key", hmacKey);
		top.getChildren().add(hint);
		VBox box = new VBox(top, hashTable);
		VBox.setVgrow(hashTable, Priority.ALWAYS);
		return box;
	}

	private void addStep(@Nonnull StepModel model) {
		watch(model);
		StepModel selected = stepList.getSelectionModel().getSelectedItem();
		int index = selected == null ? steps.size() : steps.indexOf(selected) + 1;
		steps.add(index, model);
		stepList.getSelectionModel().select(model);
		scheduleRun();
	}

	@Nonnull
	private StepModel watch(@Nonnull StepModel model) {
		model.addListener(() -> {
			stepList.refresh();
			scheduleRun();
		});
		model.enabledProperty().addListener((ob, old, cur) -> scheduleRun());
		return model;
	}

	private void moveSelected(int delta) {
		int index = stepList.getSelectionModel().getSelectedIndex();
		int target = index + delta;
		if (index < 0 || target < 0 || target >= steps.size())
			return;
		StepModel model = steps.remove(index);
		steps.add(target, model);
		stepList.getSelectionModel().select(target);
		scheduleRun();
	}

	private void showEditor(@Nullable StepModel model) {
		if (model == null) {
			Label placeholder = new BoundLabel(Lang.getBinding("crypto.recipe.select"));
			placeholder.getStyleClass().add(Styles.TEXT_MUTED);
			StackPane stack = new StackPane(placeholder);
			stack.setPadding(new Insets(20));
			stepEditor.setContent(stack);
		} else {
			stepEditor.setContent(model.editor());
		}
	}

	/**
	 * @return Current input bytes.
	 *
	 * @throws CryptoException
	 * 		When the input text is not valid in the selected format.
	 */
	@Nonnull
	private byte[] currentInput() throws CryptoException {
		if (binaryInput != null)
			return binaryInput;
		ByteFormat format = inputFormat.getValue();
		return (format == null ? ByteFormat.UTF8 : format).parse(inputArea.getText());
	}

	private void scheduleRun() {
		runDelay.playFromStart();
	}

	private void runRecipe() {
		byte[] input;
		try {
			input = currentInput();
			inputInfo.setText(describeInput(input));
			inputInfo.getStyleClass().remove(Styles.DANGER);
		} catch (CryptoException ex) {
			inputInfo.setText(ex.getMessage());
			if (!inputInfo.getStyleClass().contains(Styles.DANGER))
				inputInfo.getStyleClass().add(Styles.DANGER);
			showOutputError(Lang.get("crypto.error.input"));
			return;
		}

		List<StepModel> active = new ArrayList<>();
		List<CryptoStep> built = new ArrayList<>();
		for (StepModel model : steps) {
			model.errorProperty().set(null);
			if (!model.enabledProperty().get())
				continue;
			try {
				built.add(model.build());
				active.add(model);
			} catch (CryptoException | RuntimeException ex) {
				model.errorProperty().set(ex.getMessage());
				stepList.refresh();
				showOutputError(Lang.get("crypto.error.step") + ": " + ex.getMessage());
				return;
			}
		}

		int generation = ++runGeneration;
		CompletableFuture.supplyAsync(() -> Recipe.run(built, input), executor).whenCompleteAsync((result, error) -> {
			if (generation != runGeneration)
				return;
			if (error != null) {
				logger.error("Crypto recipe failed", error);
				showOutputError(String.valueOf(error.getMessage()));
				return;
			}
			for (int i = 0; i < result.steps().size(); i++)
				active.get(i).errorProperty().set(result.steps().get(i).error());
			stepList.refresh();
			if (result.isSuccess()) {
				lastOutput = result.output();
				renderOutput();
				if (tools.getSelectionModel().getSelectedItem() == hashTab)
					refreshHashes();
			} else {
				showOutputError(result.error());
			}
		}, FxThreadUtil.executor());
	}

	@Nonnull
	private static String describeInput(@Nonnull byte[] input) {
		StringBuilder sb = new StringBuilder();
		sb.append(Lang.format("crypto.info.bytes", input.length).get());
		if (input.length > 0) {
			sb.append(" · ").append(Lang.get("crypto.info.entropy")).append(' ')
					.append(String.format(Locale.ROOT, "%.2f", DataInspector.entropy(input)));
			String signature = DataInspector.detectSignature(input);
			if (signature != null)
				sb.append(" · ").append(signature);
			List<Codec> guesses = DataInspector.guessEncodings(input);
			if (!guesses.isEmpty())
				sb.append(" · ").append(Lang.get("crypto.info.looks-like")).append(' ')
						.append(guesses.stream().map(Codec::displayName).collect(Collectors.joining(", ")));
		}
		return sb.toString();
	}

	private void renderOutput() {
		byte[] output = lastOutput;
		if (output == null)
			return;
		OutputFormat format = outputFormat.getValue();
		outputArea.setText(format.format(output));
		DataInspector.Score score = DataInspector.score(output);
		String info = Lang.format("crypto.info.bytes", output.length).get() + " · " + score.kind();
		if (output.length > OutputFormat.MAX_DISPLAY_BYTES)
			info += " · " + Lang.get("crypto.info.truncated");
		outputInfo.setText(info);
		outputInfo.getStyleClass().remove(Styles.DANGER);
		outputInfo.setTooltip(null);
	}

	private void showOutputError(@Nullable String message) {
		lastOutput = null;
		outputArea.setText("");
		outputInfo.setText(message == null ? Lang.get("crypto.error.step") : message);
		outputInfo.setTooltip(message == null ? null : new Tooltip(message));
		if (!outputInfo.getStyleClass().contains(Styles.DANGER))
			outputInfo.getStyleClass().add(Styles.DANGER);
	}

	private void refreshHashes() {
		byte[] input = lastOutput;
		if (input == null)
			try {
				input = currentInput();
			} catch (CryptoException ex) {
				hashTable.getItems().clear();
				return;
			}
		byte[] key;
		try {
			key = hmacKey.getBytesOrNull();
		} catch (CryptoException ex) {
			key = null;
		}
		byte[] data = input;
		byte[] macKey = key;
		CompletableFuture.supplyAsync(() -> {
			List<HashRow> rows = new ArrayList<>();
			for (HashAlgorithm algorithm : HashAlgorithm.values()) {
				if (macKey != null && algorithm.isChecksum())
					continue;
				try {
					byte[] hash = macKey == null ? algorithm.hash(data) : algorithm.hmac(macKey, data);
					rows.add(new HashRow((macKey == null ? "" : "HMAC-") + algorithm.displayName(), HexFormat.of().formatHex(hash)));
				} catch (CryptoException ex) {
					rows.add(new HashRow("HMAC-" + algorithm.displayName(), ex.getMessage()));
				}
			}
			return rows;
		}, executor).thenAcceptAsync(rows -> hashTable.getItems().setAll(rows), FxThreadUtil.executor());
	}

	private void runBruteForce() {
		byte[] input = lastOutputOrInput();
		if (input == null || input.length == 0) {
			bruteStatus.setText(Lang.get("crypto.brute.no-input"));
			return;
		}
		List<byte[]> keys = new ArrayList<>();
		ByteFormat keyFormat = bruteKeyFormat.getValue();
		try {
			for (String line : bruteKeys.getText().split("\\R")) {
				if (!line.isEmpty())
					keys.add(keyFormat.parse(line));
			}
		} catch (CryptoException ex) {
			bruteStatus.setText(Lang.get("crypto.brute.bad-key") + ": " + ex.getMessage());
			return;
		}
		if (bruteWorkspaceKeys.isSelected()) {
			Workspace workspace = workspaceManager.getCurrent();
			if (workspace != null)
				for (String s : WorkspaceStrings.collect(workspace))
					if (s.length() >= 4 && s.length() <= 64 && keys.size() < MAX_BRUTE_KEYS)
						keys.add(s.getBytes(StandardCharsets.UTF_8));
		}
		if (keys.isEmpty() && !bruteXor.isSelected()) {
			bruteStatus.setText(Lang.get("crypto.brute.no-keys"));
			return;
		}
		byte[] iv;
		byte[] salt;
		try {
			iv = bruteIv.getBytesOrNull();
			salt = bruteSalt.getBytesOrNull();
		} catch (CryptoException ex) {
			bruteStatus.setText(ex.getMessage());
			return;
		}
		CipherBruteForcer.Options options = new CipherBruteForcer.Options(input, keys, iv, salt,
				bruteIterations.getValue(), bruteExhaustive.isSelected(), bruteDecodeInput.isSelected(),
				bruteXor.isSelected(), 200);

		AtomicBoolean cancelled = new AtomicBoolean();
		bruteCancelled = cancelled;
		bruteRun.setDisable(true);
		bruteCancel.setDisable(false);
		bruteProgress.setVisible(true);
		bruteProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
		bruteResults.getItems().clear();
		bruteStatus.setText(Lang.format("crypto.brute.running", keys.size()).get());
		long start = System.currentTimeMillis();
		CompletableFuture.supplyAsync(() -> CipherBruteForcer.run(options, (done, total) -> FxThreadUtil.run(() -> {
			if (!cancelled.get())
				bruteProgress.setProgress((double) done / total);
		}), cancelled::get), bruteExecutor).whenCompleteAsync((results, error) -> {
			if (bruteCancelled == cancelled) {
				bruteRun.setDisable(false);
				bruteCancel.setDisable(true);
				bruteProgress.setVisible(false);
			}
			if (error != null) {
				logger.error("Crypto brute force failed", error);
				bruteStatus.setText(String.valueOf(error.getMessage()));
				return;
			}
			bruteResults.getItems().setAll(results);
			long elapsed = System.currentTimeMillis() - start;
			bruteStatus.setText(cancelled.get() ? Lang.get("crypto.brute.cancelled") :
					Lang.format("crypto.brute.done", results.size(), elapsed).get());
		}, FxThreadUtil.executor());
	}

	@Nullable
	private byte[] lastOutputOrInput() {
		if (!getSteps().isEmpty() && lastOutput != null)
			return lastOutput;
		try {
			return currentInput();
		} catch (CryptoException ex) {
			return null;
		}
	}

	private void cancelBruteForce() {
		if (bruteCancelled != null)
			bruteCancelled.set(true);
	}

	private void openInputFile() {
		File file = new FileChooserBuilder()
				.setTitle(Lang.get("crypto.input.file"))
				.open(getScene() == null ? null : getScene().getWindow());
		if (file == null)
			return;
		try {
			if (Files.size(file.toPath()) > 256L * 1024 * 1024)
				throw new IOException(Lang.get("crypto.input.too-large"));
			setInput(file.getName(), Files.readAllBytes(file.toPath()));
		} catch (IOException ex) {
			ErrorDialogs.show(Lang.get("crypto.input.file"), file.getName(), String.valueOf(ex.getMessage()), ex);
		}
	}

	private void saveOutput() {
		byte[] output = lastOutput;
		if (output == null)
			return;
		File file = new FileChooserBuilder()
				.setTitle(Lang.get("crypto.output.save"))
				.setInitialFileName("output.bin")
				.save(getScene() == null ? null : getScene().getWindow());
		if (file == null)
			return;
		try {
			Files.write(file.toPath(), output);
		} catch (IOException ex) {
			ErrorDialogs.show(Lang.get("crypto.output.save"), file.getName(), String.valueOf(ex.getMessage()), ex);
		}
	}

	private void pickWorkspaceString() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null)
			return;
		TextField filter = new TextField();
		filter.setPromptText(Lang.get("crypto.strings.filter"));
		ListView<String> list = new ListView<>();
		list.getStyleClass().add("mono-text");
		list.setPlaceholder(new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS));
		Label count = new Label();
		count.getStyleClass().add(Styles.TEXT_MUTED);
		List<String> all = new ArrayList<>();
		Runnable applyFilter = () -> {
			String needle = filter.getText().toLowerCase(Locale.ROOT);
			List<String> matches = all.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(needle)).limit(5000).toList();
			list.getItems().setAll(matches);
			count.setText(Lang.format("crypto.strings.count", matches.size(), all.size()).get());
		};
		filter.textProperty().addListener((ob, old, cur) -> applyFilter.run());
		list.setCellFactory(l -> new ListCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : DataInspector.preview(item.getBytes(StandardCharsets.UTF_8), 200));
			}
		});

		Stage stage = new RecafStage();
		stage.setTitle(Lang.get("crypto.input.workspace-string"));
		Runnable choose = () -> {
			String selected = list.getSelectionModel().getSelectedItem();
			if (selected != null) {
				setInput(Lang.get("crypto.input.workspace-string"), selected);
				stage.close();
			}
		};
		list.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2)
				choose.run();
		});
		list.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ENTER)
				choose.run();
		});
		filter.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DOWN)
				list.requestFocus();
			else if (e.getCode() == KeyCode.ENTER && !list.getItems().isEmpty()) {
				list.getSelectionModel().selectFirst();
				choose.run();
			}
		});
		ActionButton use = new ActionButton(CarbonIcons.CHECKMARK, Lang.getBinding("crypto.strings.use"), choose);
		HBox bottom = new HBox(8, count, spacer(), use);
		bottom.setAlignment(Pos.CENTER_LEFT);
		VBox root = new VBox(8, filter, list, bottom);
		root.setPadding(new Insets(10));
		VBox.setVgrow(list, Priority.ALWAYS);
		stage.setScene(new RecafScene(root, 640, 520));
		if (getScene() != null)
			stage.initOwner(getScene().getWindow());
		stage.show();
		filter.requestFocus();

		CompletableFuture.supplyAsync(() -> WorkspaceStrings.collect(workspace), executor)
				.thenAcceptAsync(strings -> {
					all.addAll(strings);
					list.setPlaceholder(new BoundLabel(Lang.getBinding("crypto.strings.none")));
					applyFilter.run();
				}, FxThreadUtil.executor());
	}

	@Nonnull
	private static ActionButton flatButton(@Nonnull Ikon icon, @Nonnull String tooltipKey, @Nonnull Runnable action) {
		ActionButton button = new ActionButton(icon, action);
		button.setTooltip(new Tooltip(Lang.get(tooltipKey)));
		button.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
		return button;
	}

	@Nonnull
	private static HBox toolbar(@Nonnull Node... children) {
		HBox toolbar = new HBox(8, children);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.setPadding(new Insets(4, 8, 4, 8));
		toolbar.getStyleClass().add("config-toolbar");
		return toolbar;
	}

	@Nonnull
	private static Region spacer() {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return spacer;
	}

	/**
	 * Row of the hash table.
	 */
	private record HashRow(@Nonnull String name, @Nonnull String value) {}

	/**
	 * Recipe list cell: enable toggle, step number and description, and an error marker.
	 */
	private class StepCell extends ListCell<StepModel> {
		private final CheckBox enabled = new CheckBox();
		private final Label text = new Label();
		private final FontIconView errorIcon = new FontIconView(CarbonIcons.WARNING_ALT_FILLED);
		private final HBox graphic = new HBox(6, enabled, text, errorIcon);
		private StepModel bound;

		private StepCell() {
			graphic.setAlignment(Pos.CENTER_LEFT);
			errorIcon.getStyleClass().add(Styles.DANGER);
			text.setMinWidth(0);
			HBox.setHgrow(text, Priority.ALWAYS);
		}

		@Override
		protected void updateItem(StepModel item, boolean empty) {
			super.updateItem(item, empty);
			if (bound != null) {
				enabled.selectedProperty().unbindBidirectional(bound.enabledProperty());
				bound = null;
			}
			if (empty || item == null) {
				setGraphic(null);
				setText(null);
				setTooltip(null);
				return;
			}
			bound = item;
			enabled.selectedProperty().bindBidirectional(item.enabledProperty());
			text.setText((getIndex() + 1) + ". " + item.titleProperty().get());
			String error = item.errorProperty().get();
			errorIcon.setVisible(error != null);
			errorIcon.setManaged(error != null);
			setTooltip(new Tooltip(error != null ? error : item.titleProperty().get()));
			setGraphic(graphic);
			setText(null);
		}
	}
}
