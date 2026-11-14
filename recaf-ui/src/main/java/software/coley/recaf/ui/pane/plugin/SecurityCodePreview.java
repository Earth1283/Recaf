package software.coley.recaf.ui.pane.plugin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.inject.Instance;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.ui.config.KeybindingConfig;
import software.coley.recaf.ui.control.richtext.source.JavaContextActionSupport;
import software.coley.recaf.ui.pane.editing.jvm.JvmDecompilerPane;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;

import java.util.List;

/**
 * Read-only decompiled code of the class a finding is about, with the finding's member selected and, when it can be
 * found in the source, the one line the finding is about.
 * <p>
 * Holds one decompiler pane at a time. A pane is made for every class shown, since its parsed source only
 * describes the class it was made for.
 */
public class SecurityCodePreview extends StackPane {
	private static final Logger logger = Logging.get(SecurityCodePreview.class);
	/** Editors take focus shortly after being created, which must not pull it away from the list being browsed. */
	private static final int RESTORE_FOCUS_DELAY_MS = 100;
	private final Label placeholder = new Label(Lang.get("mcplugin.security.preview.none"));
	private final Instance<JvmDecompilerPane> decompilerProvider;
	private final PluginNavigation navigation;
	private JvmDecompilerPane pane;
	private Target target;

	/**
	 * @param decompilerProvider
	 * 		Provider of the decompiler panes shown.
	 * @param navigation
	 * 		Navigation to resolve locations with.
	 * @param keys
	 * 		Keybindings, to keep the shortcuts that change the workspace from reaching the previewed code.
	 */
	public SecurityCodePreview(@Nonnull Instance<JvmDecompilerPane> decompilerProvider,
	                           @Nonnull PluginNavigation navigation,
	                           @Nonnull KeybindingConfig keys) {
		this.decompilerProvider = decompilerProvider;
		this.navigation = navigation;
		placeholder.getStyleClass().add(Styles.TEXT_SUBTLE);
		placeholder.setWrapText(true);
		setAlignment(Pos.CENTER);
		getChildren().add(placeholder);
		addEventFilter(KeyEvent.KEY_PRESSED, e -> {
			if (keys.getSave().match(e) || keys.getUndo().match(e))
				e.consume();
		});
	}

	/**
	 * Shows the code a finding is about, or nothing if it has none or it no longer exists.
	 *
	 * @param finding
	 * 		Finding to show the code of.
	 */
	public void show(@Nullable SecurityFinding finding) {
		CodeLocation location = finding == null ? null : finding.location();
		if (location == null) {
			clear();
			return;
		}
		List<String> needles = FindingNeedles.of(finding);
		Target wanted = new Target(location, needles);
		if (wanted.equals(target) && pane != null)
			return;
		PathNode<?> resolved = navigation.resolve(location);
		ClassPathNode classPath = resolved instanceof ClassMemberPathNode member ? member.getParent()
				: resolved instanceof ClassPathNode owner ? owner : null;
		if (classPath == null) {
			clear();
			return;
		}
		Node focused = getScene() == null ? null : getScene().getFocusOwner();
		release();
		target = wanted;
		try {
			JvmDecompilerPane created = decompilerProvider.get();
			pane = created;
			created.setRecordsNavigationHistory(false);
			created.getEditor().getCodeArea().setEditable(false);
			created.onUpdatePath(classPath);
			for (Navigable child : created.getNavigableChildren()) {
				if (child instanceof JavaContextActionSupport support && resolved instanceof ClassMemberPathNode member) {
					support.setLineNeedles(needles);
					support.select(member.getValue());
				}
			}
			getChildren().setAll(created);
			if (focused != null && !isInside(focused))
				FxThreadUtil.delayedRun(RESTORE_FOCUS_DELAY_MS, () -> {
					if (focused.getScene() != null && getScene() != null && isInside(getScene().getFocusOwner()))
						focused.requestFocus();
				});
		} catch (RuntimeException ex) {
			logger.error("Cannot preview {}", location, ex);
			clear();
		}
	}

	private boolean isInside(@Nullable Node node) {
		for (Node current = node; current != null; current = current.getParent())
			if (current == this)
				return true;
		return false;
	}

	/**
	 * Removes the code shown.
	 */
	public void clear() {
		release();
		getChildren().setAll(placeholder);
	}

	/**
	 * Releases the decompiler pane. Call when the preview is no longer needed.
	 */
	public void release() {
		target = null;
		JvmDecompilerPane old = pane;
		pane = null;
		if (old == null)
			return;
		getChildren().remove(old);
		try {
			old.disable();
			decompilerProvider.destroy(old);
		} catch (RuntimeException ex) {
			logger.warn("Failed to release the code preview", ex);
		}
	}

	private record Target(@Nonnull CodeLocation location, @Nonnull List<String> needles) {}
}
