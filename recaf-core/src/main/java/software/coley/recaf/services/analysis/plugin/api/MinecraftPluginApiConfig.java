package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link MinecraftPluginApiService}.
 */
@ApplicationScoped
public class MinecraftPluginApiConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean includeDependencies = new ObservableBoolean(true);
	private final ObservableInteger maxDependencies = new ObservableInteger(80);
	private final ObservableInteger maxDependencySizeMb = new ObservableInteger(8);
	private final ObservableInteger maxApiSizeMb = new ObservableInteger(64);

	@Inject
	public MinecraftPluginApiConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, MinecraftPluginApiService.SERVICE_ID + CONFIG_SUFFIX);

		addValue(new BasicConfigValue<>("include-dependencies", boolean.class, includeDependencies));
		addValue(new BasicConfigValue<>("max-dependencies", int.class, maxDependencies));
		addValue(new BasicConfigValue<>("max-dependency-size-mb", int.class, maxDependencySizeMb));
		addValue(new BasicConfigValue<>("max-api-size-mb", int.class, maxApiSizeMb));
	}

	/**
	 * The API jar on its own only has the platform's own types. Plugin code also uses types from the libraries the
	 * platform is built on <i>(For example Adventure and Guava for Paper)</i>. Including them lets the decompiler
	 * resolve those types too.
	 *
	 * @return {@code true} to also download the libraries the API depends on.
	 */
	@Nonnull
	public ObservableBoolean getIncludeDependencies() {
		return includeDependencies;
	}

	/**
	 * @return Maximum number of libraries to attach alongside the API.
	 */
	@Nonnull
	public ObservableInteger getMaxDependencies() {
		return maxDependencies;
	}

	/**
	 * Large libraries are skipped, since they cost a lot of memory and time to load for little value in resolving types.
	 * For example Paper's API depends on fastutil, which is over 20 MB.
	 *
	 * @return Maximum size in megabytes of a dependency jar to attach.
	 */
	@Nonnull
	public ObservableInteger getMaxDependencySizeMb() {
		return maxDependencySizeMb;
	}

	/**
	 * @return Maximum size in megabytes of the API jar itself.
	 */
	@Nonnull
	public ObservableInteger getMaxApiSizeMb() {
		return maxApiSizeMb;
	}
}
