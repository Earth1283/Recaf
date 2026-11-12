package software.coley.recaf.ui.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableObject;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;

import java.net.URL;

/**
 * Config for the color scheme of code editors.
 *
 * @see software.coley.recaf.ui.EditorThemeService
 */
@ApplicationScoped
public class EditorThemeConfig extends BasicConfigContainer {
	private final ObservableObject<EditorTheme> theme = new ObservableObject<>(EditorTheme.DEFAULT);

	@Inject
	public EditorThemeConfig() {
		super(ConfigGroups.SERVICE_UI, "editor-theme" + CONFIG_SUFFIX);
		addValue(new BasicConfigValue<>("theme", EditorTheme.class, theme));
	}

	/**
	 * @return Selected color scheme.
	 */
	@Nonnull
	public ObservableObject<EditorTheme> getTheme() {
		return theme;
	}

	/**
	 * Color schemes for code editors. Each one is a stylesheet that restyles the editor background, the line and
	 * selection highlights, the line numbers, and the colors of the syntax highlighting.
	 */
	public enum EditorTheme {
		/** The look of the editors without any scheme applied. */
		DEFAULT("Default", null),
		DRACULA("Dracula", "dracula"),
		MONOKAI("Monokai", "monokai"),
		SOLARIZED_DARK("Solarized Dark", "solarized-dark"),
		HIGH_CONTRAST("High Contrast", "high-contrast");

		private final String displayName;
		private final String resourceName;

		EditorTheme(@Nonnull String displayName, @Nullable String resourceName) {
			this.displayName = displayName;
			this.resourceName = resourceName;
		}

		/**
		 * @return URL of the stylesheet of the scheme, or {@code null} for {@link #DEFAULT}, which adds none.
		 */
		@Nullable
		public String getStylesheetUrl() {
			if (resourceName == null)
				return null;
			URL url = EditorTheme.class.getResource("/style/editor-themes/" + resourceName + ".css");
			return url == null ? null : url.toExternalForm();
		}

		@Override
		public String toString() {
			return displayName;
		}
	}
}
