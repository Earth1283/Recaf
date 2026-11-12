package software.coley.recaf.ui;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.cdi.InitializationStage;
import software.coley.recaf.ui.config.EditorThemeConfig;
import software.coley.recaf.util.FxThreadUtil;

/**
 * Applies the selected editor color scheme to every window, including windows opened later and docked tabs that are
 * dragged out into their own window. The default scheme adds no stylesheet at all.
 */
@ApplicationScoped
@EagerInitialization(InitializationStage.AFTER_UI_INIT)
public class EditorThemeService {
	private static final String APPLIED_KEY = "recaf-editor-theme-url";
	private final EditorThemeConfig config;

	@Inject
	public EditorThemeService(@Nonnull EditorThemeConfig config) {
		this.config = config;
		FxThreadUtil.run(() -> {
			Window.getWindows().addListener((ListChangeListener<Window>) change -> {
				while (change.next())
					if (change.wasAdded())
						change.getAddedSubList().forEach(this::watch);
			});
			Window.getWindows().forEach(this::watch);
		});
		config.getTheme().addChangeListener((ob, old, cur) ->
				FxThreadUtil.run(() -> Window.getWindows().forEach(window -> apply(window.getScene()))));
	}

	private void watch(@Nonnull Window window) {
		window.sceneProperty().addListener((ob, old, cur) -> {
			if (old != null)
				apply(old);
			apply(cur);
		});
		apply(window.getScene());
	}

	private void apply(@Nullable Scene scene) {
		if (scene == null)
			return;
		Object applied = scene.getProperties().remove(APPLIED_KEY);
		if (applied instanceof String url)
			scene.getStylesheets().remove(url);
		String url = config.getTheme().getValue().getStylesheetUrl();
		if (url != null) {
			scene.getStylesheets().add(url);
			scene.getProperties().put(APPLIED_KEY, url);
		}
	}
}
