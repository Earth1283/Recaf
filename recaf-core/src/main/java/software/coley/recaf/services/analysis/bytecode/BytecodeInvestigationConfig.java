package software.coley.recaf.services.analysis.bytecode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/** Configuration namespace for bytecode investigation. */
@ApplicationScoped
public class BytecodeInvestigationConfig extends BasicConfigContainer implements ServiceConfig {
	@Inject
	public BytecodeInvestigationConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, BytecodeInvestigationService.SERVICE_ID + CONFIG_SUFFIX);
	}
}
