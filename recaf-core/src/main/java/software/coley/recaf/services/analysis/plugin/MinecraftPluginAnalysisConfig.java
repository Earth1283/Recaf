package software.coley.recaf.services.analysis.plugin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link MinecraftPluginAnalysisService}.
 */
@ApplicationScoped
public class MinecraftPluginAnalysisConfig extends BasicConfigContainer implements ServiceConfig {
	@Inject
	public MinecraftPluginAnalysisConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, MinecraftPluginAnalysisService.SERVICE_ID + CONFIG_SUFFIX);
	}
}
