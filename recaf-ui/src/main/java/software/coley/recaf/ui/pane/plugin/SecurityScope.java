package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.util.Lang;

/**
 * Which findings the security triage lists: the plugin's own, those in the libraries it bundles, or both.
 * <p>
 * The libraries are kept apart by default because most of what they do is their own business, but they are never
 * dropped from the scan, since malware also hides in packages named after libraries.
 */
public enum SecurityScope {
	/** Findings outside bundled libraries. */
	PLUGIN("plugin"),
	/** Findings in the packages of bundled libraries. */
	LIBRARIES("libraries"),
	/** All findings. */
	EVERYTHING("everything");
	private final String id;

	SecurityScope(@Nonnull String id) {
		this.id = id;
	}

	/**
	 * @param finding
	 * 		Some finding.
	 *
	 * @return {@code true} when the scope lists the finding.
	 */
	public boolean includes(@Nonnull SecurityFinding finding) {
		return switch (this) {
			case PLUGIN -> !finding.library();
			case LIBRARIES -> finding.library();
			case EVERYTHING -> true;
		};
	}

	/**
	 * @return Translated name of the scope.
	 */
	@Nonnull
	public String displayName() {
		return Lang.get("mcplugin.security.scope." + id);
	}
}
