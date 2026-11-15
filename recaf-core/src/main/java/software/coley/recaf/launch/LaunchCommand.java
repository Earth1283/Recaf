package software.coley.recaf.launch;

import jakarta.annotation.Nullable;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import software.coley.recaf.Bootstrap;
import software.coley.recaf.RecafBuildConfig;
import software.coley.recaf.analytics.SystemInformation;
import software.coley.recaf.analytics.logging.RecafLoggingFilter;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.mcp.McpStdio;
import software.coley.recaf.util.StringUtil;

import java.io.File;
import java.io.StringWriter;
import java.lang.System.Logger.Level;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Launch arguments for Recaf.
 *
 * @author Matt Coley
 * @see LaunchArguments Bean accessible form available to CDI components.
 */
@Command(name = "recaf", mixinStandardHelpOptions = true, version = RecafBuildConfig.VERSION,
		description = "Recaf: The modern Java reverse engineering tool.")
public class LaunchCommand implements Callable<Boolean> {
	@Option(names = {"-i", "--input"}, description = "Input to load into a workspace on startup.")
	private File input;
	@Option(names = {"-s", "--script"}, description = "Script to run on startup.")
	private File script;
	@Option(names = {"-d", "--datadir"}, description = "Override the directory to store recaf info within.")
	private File dataDir;
	@Option(names = {"-r", "--extraplugins"}, description = "Point to an external location to load additional plugins.")
	private File extraPluginDirectory;
	@Option(names = {"-h", "--headless"}, description = "Flag to skip over initializing the UI. Should be paired with -i, -s, or --idle.")
	private boolean headless;
	@Option(names = {"--idle"}, description = "Keep headless Recaf running after startup for plugin-hosted services.")
	private boolean idle;
	@Option(names = {"--mcp"}, description = "Serve MCP over stdio, for clients that launch Recaf themselves. Pair with -h to skip the UI.")
	private boolean mcp;
	@Option(names = {"--mcp-http"}, arity = "0..1", fallbackValue = "7733",
			description = "Serve MCP over streamable HTTP on 127.0.0.1 at the given port (default: ${FALLBACK-VALUE}). 0 picks a free port.")
	private Integer mcpHttpPort;
	@Option(names = {"--mcp-token"}, description = "Bearer token for the MCP HTTP transport. A random token is logged when omitted.")
	private String mcpToken;
	@Option(names = {"--mcp-read-only"}, description = "Only expose MCP tools which do not modify anything.")
	private boolean mcpReadOnly;
	@Option(names = {"--mcp-allow-execute"}, description = "Expose MCP tools which run code, such as scripts, or attach to other processes.")
	private boolean mcpAllowExecute;
	@Option(names = {"-q", "--silent"}, description = "Disable slf4j logging to std-out.")
	private boolean silent;
	@Option(names = {"-v", "--version"}, description = "Display the version information.")
	private boolean version;
	@Option(names = {"-l", "--listservices"}, description = "Display the version information.")
	private boolean listServices;
	@Option(names = {"-p", "--listprops"}, description = "Display system properties.")
	private boolean dumpProperties;

	@Override
	public Boolean call() throws Exception {
		boolean ret = false;
		if (mcp)
			McpStdio.claim();
		if (silent)
			RecafLoggingFilter.setConsoleLevel(Level.OFF);
		if (dataDir != null)
			System.setProperty("RECAF_DIR", dataDir.getAbsolutePath());
		if (extraPluginDirectory != null)
			System.setProperty("RECAF_EXTRA_PLUGINS", extraPluginDirectory.getAbsolutePath());
		if (version || listServices || dumpProperties)
			System.out.println("======================= RECAF =======================");
		if (version) {
			System.out.printf("""
							VERSION:    %s
							GIT-COMMIT: %s
							GIT-TIME:   %s
							GIT-BRANCH: %s
							=====================================================
							""",
					RecafBuildConfig.VERSION,
					RecafBuildConfig.GIT_SHA,
					RecafBuildConfig.GIT_DATE,
					RecafBuildConfig.GIT_BRANCH
			);
			ret = true;
		}
		if (listServices) {
			try {
				BeanManager beanManager = Bootstrap.get().getContainer().getBeanManager();
				List<Bean<?>> beans = beanManager.getBeans(Service.class).stream()
						.sorted(Comparator.comparing(o -> o.getBeanClass().getName()))
						.toList();
				System.out.println("Services: " + beans.size());
				for (Bean<?> bean : beans)
					System.out.println(" - " + bean.getBeanClass().getName());
			} catch (Throwable t) {
				System.out.println("Error occurred iterating over services...");
				System.out.println(StringUtil.traceToString(t));
			}
			System.out.println("=====================================================");
			ret = true;
		}
		if (dumpProperties) {
			StringWriter sw = new StringWriter();
			SystemInformation.dump(sw);
			System.out.println(sw);
			System.out.println("=====================================================");
			ret = true;
		}
		return ret;
	}

	/**
	 * @return Input to load into a workspace on startup.
	 */
	@Nullable
	public File getInput() {
		return input;
	}

	/**
	 * @return Script to run on startup.
	 */
	@Nullable
	public File getScript() {
		return script;
	}

	/**
	 * @return Flag to skip over initializing the UI.
	 */
	public boolean isHeadless() {
		return headless;
	}

	/**
	 * @return Flag to keep headless Recaf running after startup.
	 */
	public boolean isIdle() {
		return idle || (headless && (mcp || mcpHttpPort != null));
	}

	/**
	 * @return Flag to serve MCP over stdio.
	 */
	public boolean isMcp() {
		return mcp;
	}

	/**
	 * @return Port to serve MCP over HTTP on, or {@code null} when not requested.
	 */
	@Nullable
	public Integer getMcpHttpPort() {
		return mcpHttpPort;
	}

	/**
	 * @return Bearer token for the MCP HTTP transport.
	 */
	@Nullable
	public String getMcpToken() {
		return mcpToken;
	}

	/**
	 * @return Flag to hide MCP tools which modify anything.
	 */
	public boolean isMcpReadOnly() {
		return mcpReadOnly;
	}

	/**
	 * @return Flag to show MCP tools which execute code.
	 */
	public boolean isMcpAllowExecute() {
		return mcpAllowExecute;
	}
}
