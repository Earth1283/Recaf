package software.coley.recaf.ui.control.popup;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.observables.ObservableObject;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompileDiagnostic;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.config.RecentFilesConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.ObservableComboBox;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.FileChooserBuilder;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Popup for initiating decompilation of all classes, saved to a specified location.
 *
 * @author Matt Coley
 */
@Dependent
public class DecompileAllPopup extends RecafStage {
	private static final Logger logger = Logging.get(DecompileAllPopup.class);
	private final ObjectProperty<Path> pathProperty = new SimpleObjectProperty<>();
	private final ObservableObject<JvmDecompiler> decompilerProperty;
	private final BooleanProperty inProgressProperty = new SimpleBooleanProperty();
	private Predicate<String> namePredicate = name -> true;
	private ClassBundle<?> targetBundle;

	@Inject
	public DecompileAllPopup(@Nonnull DecompilerManager decompilerManager,
	                         @Nonnull RecentFilesConfig recentFilesConfig,
	                         @Nonnull DecompilerPaneConfig decompilerPaneConfig,
	                         @Nonnull WorkspaceManager workspaceManager) {
		Workspace workspace = workspaceManager.getCurrent();
		String defaultName = buildName(workspace);

		targetBundle = workspace.getPrimaryResource().getJvmClassBundle();
		decompilerProperty = new ObservableObject<>(decompilerManager.getTargetJvmDecompiler());
		pathProperty.setValue(Paths.get(recentFilesConfig.getLastWorkspaceExportDirectory().getValue()).resolve(defaultName));

		Label decompilerLabel = new BoundLabel(Lang.getBinding("java.decompiler"));
		Label pathLabel = new BoundLabel(Lang.getBinding("menu.file.decompileall.path"));
		ObservableComboBox<JvmDecompiler> decompilerCombo = new ObservableComboBox<>(decompilerProperty, decompilerManager.getJvmDecompilers());
		ProgressBar progress = new ProgressBar(0);
		Label status = new Label();
		status.setWrapText(true);
		Button pathButton = new ActionButton(CarbonIcons.EDIT, pathProperty.map(Path::toString), () -> {
			FileChooser chooser = new FileChooserBuilder()
					.setInitialFileName(defaultName)
					.setInitialDirectory(recentFilesConfig.getLastWorkspaceExportDirectory())
					.setFileExtensionFilter("Archives", "*.zip", "*.jar")
					.setTitle(Lang.get("dialog.file.open"))
					.build();
			File file = chooser.showSaveDialog(getScene().getWindow());
			if (file != null) {
				String parent = file.getParent();
				if (parent != null) recentFilesConfig.getLastWorkspaceExportDirectory().setValue(parent);
				pathProperty.set(file.toPath());
			}
		});
		Button decompileButton = new ActionButton(CarbonIcons.SAVE_SERIES, Lang.getBinding("menu.file.decompileall"), () -> {
			try {
				inProgressProperty.setValue(true);
				progress.setProgress(0);
				status.setText("");

				// Determine which classes to decompile.
				//
				// We'll also map any class models in the bundle to JVM models (which will convert android classes if needed)
				// since we only support decompilation of JVM classes.
				List<JvmClassInfo> targetClasses = new ArrayList<>();
				for (ClassInfo cls : targetBundle.valuesAsCopy()) {
					// Skip inner classes
					if (cls.isInnerClass())
						continue;

					// Skip special case classes like 'module-info' and 'package-info'
					String name = cls.getName();
					if (cls.getSuperName() == null || name.equals("module-info") || name.endsWith("package-info"))
						continue;

					// Pass to name predicate for final say
					if (!namePredicate.test(name))
						continue;

					// Require the class to be a JVM class, converting if needed.
					// If conversion fails, skip the class.
					try {
						targetClasses.add(cls.asJvmClass());
					} catch (Throwable t) {
						logger.warn("Failed to convert Android class '{}' to JVM bytecode for decompilation", name, t);
					}
				}

				int targetCount = targetClasses.size();
				if (targetCount == 0) {
					status.setText(Lang.get("menu.file.decompileall.empty"));
					inProgressProperty.setValue(false);
					return;
				}

				Path path = pathProperty.get();
				ArchiveSink archive = new ArchiveSink(path);
				Duration timeout = Duration.ofSeconds(decompilerPaneConfig.getTimeoutSeconds().getValue());
				decompilerManager.decompileBatch(decompilerProperty.getValue(), workspace, targetClasses, timeout, (info, result) -> {
					int oldPercent = 100 * archive.processed / targetCount;
					try {
						archive.accept(info, result);
					} catch (IOException ex) {
						throw new UncheckedIOException(ex);
					}
					int percent = 100 * ++archive.processed / targetCount;
					if (percent != oldPercent)
						FxThreadUtil.run(() -> progress.setProgress(percent / 100.0));
				}).whenCompleteAsync((ignored, error) -> {
					try (archive) {
						if (error != null)
							throw new IllegalStateException("Could not process decompilation results", error);
						archive.publish();
					} catch (Exception ex) {
						logger.error("Failed to export decompiled classes to '{}'", path, ex);
						FxThreadUtil.run(() -> {
							status.setText(Lang.get("menu.file.decompileall.error"));
							inProgressProperty.setValue(false);
						});
						return;
					}

					FxThreadUtil.run(() -> {
						inProgressProperty.setValue(false);
						if (archive.written == 0)
							status.setText(Lang.get("menu.file.decompileall.nooutput"));
						else if (archive.written < targetCount || archive.withDiagnostics != 0)
							status.setText(String.format(Lang.get("menu.file.decompileall.partial"),
									archive.written, targetCount, archive.withDiagnostics));
						else
							hide();
					});
				});
			} catch (Throwable t) {
				logger.error("Failed to schedule all classes for decompilation", t);
				status.setText(Lang.get("menu.file.decompileall.error"));
				inProgressProperty.setValue(false);
			}
		});
		decompileButton.disableProperty().bind(pathProperty.isNull().or(inProgressProperty));
		pathButton.disableProperty().bind(inProgressProperty);
		decompilerCombo.disableProperty().bind(inProgressProperty);

		// Layout
		GridPane layout = new GridPane(8, 8);
		GridPane.setFillWidth(progress, true);
		GridPane.setFillWidth(decompilerCombo, true);
		GridPane.setFillWidth(decompileButton, true);
		GridPane.setFillWidth(pathButton, true);
		pathButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompileButton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompilerCombo.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		progress.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		decompilerLabel.setMinWidth(85); // Ensure the labels do not get squished when a large path is selected
		layout.add(decompilerLabel, 0, 0);
		layout.add(decompilerCombo, 1, 0);
		layout.add(pathLabel, 0, 1);
		layout.add(pathButton, 1, 1);
		layout.add(decompileButton, 1, 2);
		layout.add(progress, 0, 3, 2, 1);
		layout.add(status, 0, 4, 2, 1);
		layout.setPadding(new Insets(10));
		layout.setAlignment(Pos.TOP_CENTER);

		setMinWidth(450);
		setMinHeight(200);
		setTitle(Lang.get("menu.file.decompileall"));
		setScene(new RecafScene(layout, 400, 150));
	}

	@Nonnull
	private static String buildName(@Nonnull Workspace workspace) {
		String prefix = "";
		if (workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)
			prefix = StringUtil.removeExtension(StringUtil.shortenPath(fileResource.getFileInfo().getName())) + "-";
		return prefix + "decompiled.zip";
	}

	/**
	 * @param targetBundle
	 * 		Bundle to target for decompilation.
	 */
	public void setTargetBundle(@Nonnull ClassBundle<?> targetBundle) {
		this.targetBundle = targetBundle;
	}

	/**
	 * @param namePredicate
	 * 		Name predicate for whitelisting names.
	 */
	public void setNamePredicate(@Nonnull Predicate<String> namePredicate) {
		this.namePredicate = namePredicate;
	}

	/**
	 * Stages a streaming source archive beside its destination. The batch coordinator is the sole writer.
	 */
	static final class ArchiveSink implements AutoCloseable {
		private final Path path;
		private final Set<String> entries = new HashSet<>();
		private final StringBuilder report = new StringBuilder();
		private Path staged;
		private ZipOutputStream zip;
		private int processed;
		private int written;
		private int withDiagnostics;

		ArchiveSink(@Nonnull Path path) {
			this.path = path.toAbsolutePath().normalize();
		}

		void accept(@Nonnull JvmClassInfo info, @Nonnull DecompileResult result) throws IOException {
			String name = info.getName();
			if (result.getType() != DecompileResult.ResultType.SUCCESS || result.getText() == null) {
				Throwable error = result.getException();
				recordFailure(name, error == null ? "No source returned" : error.toString());
				return;
			}

			if (!isSafeClassName(name)) {
				recordFailure(name, "Invalid archive entry name");
				return;
			}
			String entryName = name + ".java";
			if (!entries.add(entryName)) {
				recordFailure(name, "Duplicate archive entry");
				return;
			}

			write(entryName, result.getText());
			written++;
			if (!result.getDiagnostics().isEmpty()) {
				withDiagnostics++;
				report.append(name).append(" was exported with diagnostics:\n");
				for (DecompileDiagnostic diagnostic : result.getDiagnostics()) {
					report.append("  ");
					DecompileDiagnostic.Method method = diagnostic.method();
					if (method != null)
						report.append(method.name()).append(method.descriptor()).append(": ");
					report.append(diagnostic.message()).append('\n');
				}
			}
		}

		private static boolean isSafeClassName(@Nonnull String name) {
			if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf(0) >= 0)
				return false;
			for (String component : name.split("/", -1))
				if (component.isEmpty() || component.equals(".") || component.equals(".."))
					return false;
			return true;
		}

		private void recordFailure(@Nonnull String name, @Nonnull String reason) {
			report.append(name).append(" was skipped: ").append(reason).append('\n');
			logger.warn("Skipping '{}' in '{}': {}", name, path, reason);
		}

		private void write(@Nonnull String entryName, @Nonnull String text) throws IOException {
			if (zip == null) {
				staged = Files.createTempFile(path.getParent(), ".recaf-decompile-", ".zip");
				zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(staged)));
			}
			zip.putNextEntry(new ZipEntry(entryName));
			zip.write(text.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		void publish() throws IOException {
			if (zip == null)
				return;
			if (!report.isEmpty())
				write("recaf-decompile-report.txt", report.toString());
			zip.close();
			zip = null;
			try {
				Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		@Override
		public void close() throws IOException {
			try {
				if (zip != null)
					zip.close();
			} finally {
				if (staged != null)
					Files.deleteIfExists(staged);
			}
		}
	}
}
