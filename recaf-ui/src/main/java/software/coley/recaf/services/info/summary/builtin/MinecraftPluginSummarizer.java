package software.coley.recaf.services.info.summary.builtin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.api.MinecraftPluginApiService;
import software.coley.recaf.services.analysis.plugin.api.PluginApiAttachResult;
import software.coley.recaf.services.analysis.plugin.api.PluginApiRequest;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.info.summary.ResourceSummarizer;
import software.coley.recaf.services.info.summary.SummaryConsumer;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.pane.plugin.PluginNavigation;
import software.coley.recaf.ui.pane.plugin.PluginViewControls;
import software.coley.recaf.ui.pane.plugin.SecurityDisplay;
import software.coley.recaf.util.Animations;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.Batch;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Summarizer that shows information from Minecraft plugin manifests, and offers to attach the API they were written
 * against to the workspace.
 *
 * @see MinecraftPluginAnalysisService
 * @see MinecraftPluginApiService
 */
@ApplicationScoped
public class MinecraftPluginSummarizer implements ResourceSummarizer {
	private static final Logger logger = Logging.get(MinecraftPluginSummarizer.class);
	private final MinecraftPluginAnalysisService analysisService;
	private final MinecraftPluginApiService apiService;
	private final CellConfigurationService cellConfigurationService;
	private final PluginNavigation pluginNavigation;
	private final PluginSecurityService securityService;
	private final PluginViewService viewService;
	private final Actions actions;

	@Inject
	public MinecraftPluginSummarizer(@Nonnull MinecraftPluginAnalysisService analysisService,
	                                 @Nonnull MinecraftPluginApiService apiService,
	                                 @Nonnull CellConfigurationService cellConfigurationService,
	                                 @Nonnull PluginNavigation pluginNavigation,
	                                 @Nonnull PluginSecurityService securityService,
	                                 @Nonnull PluginViewService viewService,
	                                 @Nonnull Actions actions) {
		this.analysisService = analysisService;
		this.apiService = apiService;
		this.cellConfigurationService = cellConfigurationService;
		this.pluginNavigation = pluginNavigation;
		this.securityService = securityService;
		this.viewService = viewService;
		this.actions = actions;
	}

	@Override
	public boolean summarize(@Nonnull Workspace workspace,
	                         @Nonnull WorkspaceResource resource,
	                         @Nonnull SummaryConsumer consumer) {
		var manifests = analysisService.findManifestsRecursive(workspace, resource);
		if (manifests.isEmpty())
			return false;

		Batch batch = FxThreadUtil.batch();
		batch.add(() -> {
			Label title = new BoundLabel(Lang.getBinding("service.analysis.minecraft-plugin"));
			title.getStyleClass().addAll(Styles.TITLE_4);
			consumer.appendSummary(title);
		});

		// The plugin tools work on the primary resource, where mappings are applied.
		boolean primary = resource == workspace.getPrimaryResource();
		if (primary)
			batch.add(() -> consumer.appendSummary(createSecurityLabel(workspace)));

		for (var located : manifests) {
			MinecraftPluginManifest manifest = located.manifest();
			WorkspaceResource manifestResource = located.resource();
			batch.add(() -> {
				row(consumer, "service.analysis.minecraft-plugin.platform", new Label(manifest.platform().displayName()));
				if (located.forced()) {
					Button clear = new Button();
					clear.textProperty().bind(Lang.getBinding("service.analysis.minecraft-plugin.forced.clear"));
					clear.setOnAction(e -> analysisService.setPlatformOverride(workspace, null));
					row(consumer, "service.analysis.minecraft-plugin.forced",
							new Label(Lang.get("service.analysis.minecraft-plugin.forced.value")));
					consumer.appendSummary(clear);
				}
				if (manifest.name() != null)
					row(consumer, "service.analysis.minecraft-plugin.name", new Label(manifest.name()));
				if (manifest.version() != null)
					row(consumer, "service.analysis.minecraft-plugin.version", new Label(manifest.version()));
				if (manifest.apiVersion() != null)
					row(consumer, "service.analysis.minecraft-plugin.api-version", new Label(manifest.apiVersion()));
				classRow(consumer, workspace, manifestResource, "service.analysis.minecraft-plugin.main", manifest.mainClass());
				classRow(consumer, workspace, manifestResource, "service.analysis.minecraft-plugin.bootstrapper", manifest.bootstrapperClass());
				classRow(consumer, workspace, manifestResource, "service.analysis.minecraft-plugin.loader", manifest.loaderClass());
				if (!manifest.dependencies().isEmpty())
					row(consumer, "service.analysis.minecraft-plugin.depends", wrappedLabel(String.join(", ", manifest.dependencies())));
				if (!manifest.softDependencies().isEmpty())
					row(consumer, "service.analysis.minecraft-plugin.soft-depends", wrappedLabel(String.join(", ", manifest.softDependencies())));
			});
		}

		for (PluginApiRequest request : apiService.detectRequests(workspace))
			batch.add(() -> consumer.appendSummary(createAttachControl(workspace, request)));

		if (primary) {
			batch.add(() -> consumer.appendSummary(createToolButtons()));
			batch.add(() -> {
				Label title = new BoundLabel(Lang.getBinding("mcplugin.view.title"));
				title.getStyleClass().add(Styles.TEXT_BOLD);
				title.setTooltip(new Tooltip(Lang.get("mcplugin.view.tip")));
				consumer.appendSummary(title);
				consumer.appendSummary(new PluginViewControls(viewService, workspace));
			});
		}

		batch.execute();
		return true;
	}

	@Override
	public int getPriority() {
		return PRIORITY_MINECRAFT_PLUGIN;
	}

	/**
	 * @param text
	 * 		Value that may be long.
	 *
	 * @return Label that wraps the value onto multiple lines instead of cutting it off, with the full text as a tooltip.
	 */
	@Nonnull
	private static Label wrappedLabel(@Nonnull String text) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setTooltip(new Tooltip(text));
		return label;
	}

	private void row(@Nonnull SummaryConsumer consumer, @Nonnull String translationKey, @Nonnull Label value) {
		Label key = new BoundLabel(Lang.getBinding(translationKey));
		key.getStyleClass().add(Styles.TEXT_BOLD);
		consumer.appendSummary(key, value);
	}

	private void classRow(@Nonnull SummaryConsumer consumer, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull String translationKey, String internalName) {
		if (internalName == null)
			return;

		// Link to the class when it exists, otherwise just show the name since the manifest may be wrong.
		ClassPathNode path = findClass(workspace, resource, internalName);
		if (path == null) {
			row(consumer, translationKey, new Label(internalName.replace('/', '.')));
			return;
		}
		Label label = new Label(cellConfigurationService.textOf(path), cellConfigurationService.graphicOf(path));
		label.setCursor(Cursor.HAND);
		label.setOnMouseEntered(e -> label.getStyleClass().add(Styles.TEXT_UNDERLINED));
		label.setOnMouseExited(e -> label.getStyleClass().remove(Styles.TEXT_UNDERLINED));
		label.setOnMouseClicked(e -> {
			try {
				actions.gotoDeclaration(path);
			} catch (IncompletePathException ex) {
				logger.error("Cannot navigate incomplete path to '{}'", internalName, ex);
			}
		});
		row(consumer, translationKey, label);
	}

	private static ClassPathNode findClass(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                       @Nonnull String internalName) {
		for (JvmClassBundle bundle : resource.jvmClassBundleStream().toList()) {
			JvmClassInfo info = bundle.get(internalName);
			if (info != null)
				return PathNodes.classPath(workspace, resource, bundle, info);
		}
		return null;
	}

	@Nonnull
	private HBox createToolButtons() {
		Button navigator = new Button(Lang.get("menu.analysis.plugin-navigator"), new FontIconView(CarbonIcons.CATEGORIES));
		navigator.setTooltip(new Tooltip(Lang.get("mcplugin.navigator.tip")));
		navigator.setOnAction(e -> pluginNavigation.openNavigator(null));
		Button names = new Button(Lang.get("menu.mappings.plugin-names"), new FontIconView(CarbonIcons.MAGIC_WAND));
		names.setTooltip(new Tooltip(Lang.get("mcplugin.names.tip")));
		names.setOnAction(e -> pluginNavigation.openNameRecovery());
		Button security = new Button(Lang.get("menu.analysis.plugin-security"), new FontIconView(CarbonIcons.SECURITY));
		security.setTooltip(new Tooltip(Lang.get("mcplugin.security.tip")));
		security.setOnAction(e -> pluginNavigation.openSecurityTriage());
		Button settings = new Button(null, new FontIconView(CarbonIcons.SETTINGS));
		settings.setTooltip(new Tooltip(Lang.get("mcplugin.settings.tip")));
		settings.setOnAction(e -> pluginNavigation.openSettings(null));
		HBox box = new HBox(6, navigator, names, security, settings);
		box.setPadding(new Insets(6, 0, 0, 0));
		return box;
	}

	/**
	 * @return Title-sized label showing the result of the security triage once the background scan finishes.
	 * Opens the triage.
	 */
	@Nonnull
	private Label createSecurityLabel(@Nonnull Workspace workspace) {
		Label label = new Label(Lang.get("mcplugin.security.scanning"));
		label.getStyleClass().add(Styles.TITLE_4);
		label.setWrapText(true);
		label.setCursor(Cursor.HAND);
		label.setOnMouseEntered(e -> label.getStyleClass().add(Styles.TEXT_UNDERLINED));
		label.setOnMouseExited(e -> label.getStyleClass().remove(Styles.TEXT_UNDERLINED));
		label.setOnMouseClicked(e -> pluginNavigation.openSecurityTriage());
		label.setTooltip(new Tooltip(Lang.get("mcplugin.security.tip")));
		securityService.scanAsync(workspace).whenCompleteAsync((report, error) -> {
			if (error != null) {
				logger.error("Failed to scan plugin for security triage", error);
				label.setText(Lang.get("mcplugin.security.failed"));
				return;
			}
			Severity highest = SecurityDisplay.headline(report);
			label.setText(SecurityDisplay.verdict(report));
			label.setGraphic(highest == null ? new FontIconView(CarbonIcons.CHECKMARK) : SecurityDisplay.icon(highest));
		}, FxThreadUtil.executor());
		return label;
	}

	@Nonnull
	private VBox createAttachControl(@Nonnull Workspace workspace, @Nonnull PluginApiRequest request) {
		Label status = new Label();
		status.setWrapText(true);
		AtomicBoolean running = new AtomicBoolean();

		// The action runs off the UI thread, since it downloads files.
		Button button = new ActionButton(Lang.format("service.analysis.minecraft-plugin.attach", request.describe()), () -> {
			if (!running.compareAndSet(false, true))
				return;
			FxThreadUtil.run(() -> status.setText(Lang.get("service.analysis.minecraft-plugin.attach.working")));
			try {
				PluginApiAttachResult result = apiService.attach(workspace, request,
						message -> FxThreadUtil.run(() -> status.setText(message)));
				StringBuilder text = new StringBuilder(String.format(Lang.get("service.analysis.minecraft-plugin.attach.done"),
						result.attached().size(), result.alreadyPresent().size(), result.skipped().size()));
				for (var skipped : result.skipped())
					text.append('\n').append(String.format(Lang.get("service.analysis.minecraft-plugin.attach.skipped"),
							skipped.coordinate().artifactId(), skipped.reason()));
				FxThreadUtil.run(() -> status.setText(text.toString()));
			} catch (IOException | RuntimeException ex) {
				// Always say something, otherwise the status would stay on "working" forever.
				logger.error("Could not attach {}", request.describe(), ex);
				String reason = ex.getMessage() != null ? ex.getMessage() : ex.toString();
				FxThreadUtil.run(() -> status.setText(String.format(Lang.get("service.analysis.minecraft-plugin.attach.failed"), reason)));
			} finally {
				running.set(false);
			}
		}).async();
		button.setTooltip(new Tooltip(Lang.get("service.analysis.minecraft-plugin.attach.tip")));

		VBox box = new VBox(6, button, status);
		box.setPadding(new Insets(6, 0, 0, 0));
		return box;
	}
}
