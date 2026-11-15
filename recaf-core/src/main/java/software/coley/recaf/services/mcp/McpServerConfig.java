package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.observables.ObservableString;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link McpServerService}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class McpServerConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean httpEnabled = new ObservableBoolean(false);
	private final ObservableInteger httpPort = new ObservableInteger(7733);
	private final ObservableString httpToken = new ObservableString("");
	private final ObservableBoolean allowWrite = new ObservableBoolean(true);
	private final ObservableBoolean allowExecute = new ObservableBoolean(false);

	@Inject
	public McpServerConfig() {
		super(ConfigGroups.SERVICE_IO, McpServerService.SERVICE_ID + CONFIG_SUFFIX);
		addValue(new BasicConfigValue<>("http-enabled", boolean.class, httpEnabled));
		addValue(new BasicConfigValue<>("http-port", int.class, httpPort));
		addValue(new BasicConfigValue<>("http-token", String.class, httpToken));
		addValue(new BasicConfigValue<>("allow-write", boolean.class, allowWrite));
		addValue(new BasicConfigValue<>("allow-execute", boolean.class, allowExecute));
	}

	/**
	 * @return {@code true} to serve MCP over streamable HTTP on the loopback interface.
	 */
	@Nonnull
	public ObservableBoolean getHttpEnabled() {
		return httpEnabled;
	}

	/**
	 * @return Loopback port for the HTTP transport.
	 */
	@Nonnull
	public ObservableInteger getHttpPort() {
		return httpPort;
	}

	/**
	 * @return Bearer token the HTTP transport requires. When blank a random token is generated for each start.
	 */
	@Nonnull
	public ObservableString getHttpToken() {
		return httpToken;
	}

	/**
	 * @return {@code true} to expose tools which modify the workspace or write files.
	 */
	@Nonnull
	public ObservableBoolean getAllowWrite() {
		return allowWrite;
	}

	/**
	 * @return {@code true} to expose tools which execute code, such as scripts, or reach into other processes.
	 */
	@Nonnull
	public ObservableBoolean getAllowExecute() {
		return allowExecute;
	}
}
