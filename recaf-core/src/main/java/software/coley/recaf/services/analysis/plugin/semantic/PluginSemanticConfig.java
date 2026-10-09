package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link PluginSemanticService}.
 */
@ApplicationScoped
public class PluginSemanticConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean skipShadedLibraries = new ObservableBoolean(true);

	@Inject
	public PluginSemanticConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, PluginSemanticService.SERVICE_ID + CONFIG_SUFFIX);

		addValue(new BasicConfigValue<>("skip-shaded-libraries", boolean.class, skipShadedLibraries));
	}

	/**
	 * Plugins often bundle libraries such as bStats or HikariCP. Those keep their real names, and their use of the
	 * server API is not part of the plugin's own structure.
	 *
	 * @return {@code true} to ignore classes in the packages of commonly shaded libraries.
	 */
	@Nonnull
	public ObservableBoolean getSkipShadedLibraries() {
		return skipShadedLibraries;
	}
}
