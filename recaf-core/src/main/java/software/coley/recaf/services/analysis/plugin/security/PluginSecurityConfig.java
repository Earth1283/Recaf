package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link PluginSecurityService}.
 */
@ApplicationScoped
public class PluginSecurityConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean skipBundledLibraries = new ObservableBoolean(false);

	@Inject
	public PluginSecurityConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, PluginSecurityService.SERVICE_ID + CONFIG_SUFFIX);

		addValue(new BasicConfigValue<>("skip-bundled-libraries", boolean.class, skipBundledLibraries));
	}

	/**
	 * Off by default. Bundled libraries such as database drivers make a lot of low findings, but malware also hides
	 * in packages named after well known libraries, so their findings are shown and marked rather than skipped.
	 *
	 * @return {@code true} to not scan classes in the packages of commonly bundled libraries.
	 */
	@Nonnull
	public ObservableBoolean getSkipBundledLibraries() {
		return skipBundledLibraries;
	}
}
