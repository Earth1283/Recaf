package software.coley.recaf.services.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.launch.LaunchArguments;
import software.coley.recaf.RecafBuildConfig;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.mcp.transport.LoopbackHttpTransport;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.time.Instant;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.Set;

/**
 * Exposes Recaf to MCP clients, such as coding agents, as a set of tools operating on the current workspace.
 * <p/>
 * Two transports are available, and they can run at the same time:
 * <ul>
 *     <li><b>stdio</b>, for clients that launch Recaf themselves. Enabled with {@code --mcp}.</li>
 *     <li><b>Streamable HTTP</b> on loopback, for clients attaching to a running instance, including one with the UI.
 *     Enabled with {@code --mcp-http} or in the service config.</li>
 * </ul>
 * Both operate on the workspace held by the workspace manager, so when the UI is running the client sees and edits
 * what the user has open.
 *
 * @author Matt Coley
 */
@EagerInitialization
@ApplicationScoped
public class McpServerService implements Service {
	public static final String SERVICE_ID = "mcp-server";
	private static final Logger logger = Logging.get(McpServerService.class);
	private static final int MAX_RESULT_CHARS = 400_000;
	private static final String INSTRUCTIONS = """
			Recaf is a Java and Android bytecode reverse engineering tool. All tools operate on one current workspace.
			Start with workspace_open (or workspace_info if one is already open), then explore with class_list,
			class_decompile, search_* and graph tools. Class names use internal form, such as 'com/example/Foo'.
			Results are paged: pass offset and limit and check the 'truncated' flag.""";
	private final McpServerConfig config;
	private final McpToolRegistry registry;
	private final LaunchArguments launchArguments;
	private static final int MAX_CALL_LOG = 200;
	private final Deque<McpCall> callLog = new ConcurrentLinkedDeque<>();
	private final List<Consumer<McpCall>> callListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
	private McpSyncServer stdioServer;
	private McpStatelessSyncServer httpServer;
	private LoopbackHttpTransport httpTransport;
	private String httpToken;

	@Inject
	public McpServerService(@Nonnull McpServerConfig config, @Nonnull McpToolRegistry registry,
	                        @Nonnull LaunchArguments launchArguments) {
		this.config = config;
		this.registry = registry;
		this.launchArguments = launchArguments;

		if (McpStdio.isClaimed())
			startStdio();

		Integer launchPort = launchArguments.getMcpHttpPort();
		if (launchPort != null)
			startHttp(launchPort, launchArguments.getMcpToken());
		else if (config.getHttpEnabled().getValue())
			startHttp(config.getHttpPort().getValue(), null);
		config.getHttpEnabled().addChangeListener((ob, old, cur) -> {
			if (cur)
				startHttp(config.getHttpPort().getValue(), null);
			else
				stopHttp();
		});
		config.getAllowWrite().addChangeListener((ob, old, cur) -> refreshTools());
		config.getAllowExecute().addChangeListener((ob, old, cur) -> refreshTools());
	}

	/**
	 * @return Tiers of tools clients may use.
	 */
	@Nonnull
	public Set<McpAccess> getAllowedAccess() {
		Set<McpAccess> allowed = EnumSet.of(McpAccess.READ);
		if (config.getAllowWrite().getValue() && !launchArguments.isMcpReadOnly())
			allowed.add(McpAccess.WRITE);
		if (config.getAllowExecute().getValue() || launchArguments.isMcpAllowExecute())
			allowed.add(McpAccess.EXECUTE);
		return allowed;
	}

	/**
	 * @return {@code true} when a launch argument keeps write tools off regardless of the config.
	 */
	public boolean isWriteLockedByLaunch() {
		return launchArguments.isMcpReadOnly();
	}

	/**
	 * @return {@code true} when a launch argument keeps execute tools on regardless of the config.
	 */
	public boolean isExecuteForcedByLaunch() {
		return launchArguments.isMcpAllowExecute();
	}

	/**
	 * @return {@code true} when the stdio transport was started, which happens when Recaf is launched by a client.
	 */
	public synchronized boolean isStdioRunning() {
		return stdioServer != null;
	}

	/**
	 * @return Every tool that exists, whether or not the current permissions expose it.
	 */
	@Nonnull
	public List<McpTool> getAllTools() {
		return registry.getTools(EnumSet.allOf(McpAccess.class));
	}

	/**
	 * @return Most recent tool calls, newest first.
	 */
	@Nonnull
	public List<McpCall> getRecentCalls() {
		List<McpCall> calls = new ArrayList<>(callLog);
		java.util.Collections.reverse(calls);
		return calls;
	}

	/**
	 * Forgets the recorded calls.
	 */
	public void clearRecentCalls() {
		callLog.clear();
		notifyStatus();
	}

	/**
	 * @param listener
	 * 		Called, on the calling thread of the tool, after each tool call.
	 */
	public void addCallListener(@Nonnull Consumer<McpCall> listener) {
		callListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeCallListener(@Nonnull Consumer<McpCall> listener) {
		callListeners.remove(listener);
	}

	/**
	 * @param listener
	 * 		Called when a transport starts or stops, or the exposed tools change. May run on any thread.
	 */
	public void addStatusListener(@Nonnull Runnable listener) {
		statusListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeStatusListener(@Nonnull Runnable listener) {
		statusListeners.remove(listener);
	}

	/**
	 * Restarts the HTTP transport with the configured port and token, or starts it if it was off.
	 */
	public void restartHttp() {
		startHttp(config.getHttpPort().getValue(), null);
	}

	/**
	 * Updates the tools running servers expose after the permissions changed.
	 */
	public synchronized void refreshTools() {
		List<McpTool> wanted = registry.getTools(getAllowedAccess());
		Set<String> wantedNames = new HashSet<>();
		wanted.forEach(tool -> wantedNames.add(tool.name()));
		if (stdioServer != null) {
			Set<String> present = new HashSet<>();
			for (McpSchema.Tool tool : stdioServer.listTools())
				present.add(tool.name());
			present.stream().filter(name -> !wantedNames.contains(name)).forEach(stdioServer::removeTool);
			for (McpTool tool : wanted)
				if (!present.contains(tool.name()))
					stdioServer.addTool(new McpServerFeatures.SyncToolSpecification(toSdkTool(tool),
							(exchange, request) -> invoke(tool, request)));
			stdioServer.notifyToolsListChanged();
		}
		if (httpServer != null) {
			Set<String> present = new HashSet<>();
			for (McpSchema.Tool tool : httpServer.listTools())
				present.add(tool.name());
			present.stream().filter(name -> !wantedNames.contains(name)).forEach(httpServer::removeTool);
			for (McpTool tool : wanted)
				if (!present.contains(tool.name()))
					httpServer.addTool(new McpStatelessServerFeatures.SyncToolSpecification(toSdkTool(tool),
							(context, request) -> invoke(tool, request)));
		}
		notifyStatus();
	}

	/**
	 * @return {@code true} when the HTTP transport is listening.
	 */
	public synchronized boolean isHttpRunning() {
		return httpTransport != null;
	}

	/**
	 * @return Port the HTTP transport is bound to, or {@code -1} when it is not running.
	 */
	public synchronized int getHttpPort() {
		return httpTransport == null ? -1 : httpTransport.getBoundPort();
	}

	/**
	 * @return Bearer token HTTP clients must present, or {@code null} when the HTTP transport is not running.
	 */
	@Nullable
	public synchronized String getHttpToken() {
		return httpTransport == null ? null : httpToken;
	}

	/**
	 * Starts the HTTP transport, replacing any running one.
	 *
	 * @param port
	 * 		Port to bind, {@code 0} for any free port.
	 * @param token
	 * 		Bearer token, or {@code null} to use the configured one, or a random one when that is blank.
	 */
	public synchronized void startHttp(int port, @Nullable String token) {
		stopHttp();
		try {
			if (token == null || token.isBlank())
				token = config.getHttpToken().getValue();
			boolean generated = token == null || token.isBlank();
			if (generated)
				token = randomToken();
			McpJsonMapper mapper = McpJsonDefaults.getMapper();
			LoopbackHttpTransport transport = new LoopbackHttpTransport(mapper, port, token);
			Set<McpAccess> allowed = getAllowedAccess();
			List<McpStatelessServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
			for (McpTool tool : registry.getTools(allowed))
				specs.add(new McpStatelessServerFeatures.SyncToolSpecification(toSdkTool(tool),
						(context, request) -> invoke(tool, request)));
			httpServer = McpServer.sync(transport)
					.serverInfo("recaf", RecafBuildConfig.VERSION)
					.instructions(INSTRUCTIONS)
					.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
					.tools(specs)
					.build();
			transport.start();
			httpTransport = transport;
			httpToken = token;
			notifyStatus();
			if (generated)
				logger.info("MCP HTTP server listening on http://127.0.0.1:{}/mcp ({} tools). Bearer token: {}",
						transport.getBoundPort(), specs.size(), token);
			else
				logger.info("MCP HTTP server listening on http://127.0.0.1:{}/mcp ({} tools)",
						transport.getBoundPort(), specs.size());
		} catch (IOException | RuntimeException ex) {
			logger.error("Failed to start MCP HTTP server", ex);
			stopHttp();
		}
	}

	/**
	 * Stops the HTTP transport if it is running.
	 */
	public synchronized void stopHttp() {
		if (httpServer != null) {
			try {
				httpServer.close();
			} catch (RuntimeException ex) {
				logger.warn("Failed closing MCP HTTP server", ex);
			}
		}
		httpServer = null;
		httpTransport = null;
		httpToken = null;
		notifyStatus();
	}

	private synchronized void startStdio() {
		try {
			McpJsonMapper mapper = McpJsonDefaults.getMapper();
			StdioServerTransportProvider transport =
					new StdioServerTransportProvider(mapper, McpStdio.input(), McpStdio.output());
			List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
			for (McpTool tool : registry.getTools(getAllowedAccess()))
				specs.add(new McpServerFeatures.SyncToolSpecification(toSdkTool(tool),
						(exchange, request) -> invoke(tool, request)));
			stdioServer = McpServer.sync(transport)
					.serverInfo("recaf", RecafBuildConfig.VERSION)
					.instructions(INSTRUCTIONS)
					.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
					.tools(specs)
					.build();
			McpStdio.onEndOfInput(this::onStdioClosed);
			logger.info("MCP stdio server started ({} tools)", specs.size());
		} catch (RuntimeException ex) {
			logger.error("Failed to start MCP stdio server", ex);
		}
	}

	private void onStdioClosed() {
		logger.info("MCP stdio client disconnected");
		if (launchArguments.isHeadless())
			System.exit(0);
	}

	@Nonnull
	private static McpSchema.Tool toSdkTool(@Nonnull McpTool tool) {
		boolean readOnly = tool.access() == McpAccess.READ;
		return McpSchema.Tool.builder()
				.name(tool.name())
				.description(tool.description())
				.inputSchema(McpJson.toMap(tool.inputSchema()))
				.annotations(McpSchema.ToolAnnotations.builder()
						.readOnlyHint(readOnly)
						.destructiveHint(!readOnly)
						.openWorldHint(tool.access() == McpAccess.EXECUTE)
						.build())
				.build();
	}

	@Nonnull
	private McpSchema.CallToolResult invoke(@Nonnull McpTool tool, @Nonnull McpSchema.CallToolRequest request) {
		long start = System.nanoTime();
		Instant time = Instant.now();
		McpSchema.CallToolResult result;
		if (!getAllowedAccess().contains(tool.access())) {
			result = error("The '" + tool.name() + "' tool is turned off in Recaf's MCP settings");
		} else {
			try {
				McpToolResult toolResult = tool.handler().call(new McpArgs(McpJson.toObject(request.arguments())));
				String text = toolResult.text();
				if (text.length() > MAX_RESULT_CHARS)
					text = text.substring(0, MAX_RESULT_CHARS) + "\n... [output cut at " + MAX_RESULT_CHARS +
							" characters, narrow the request or page it]";
				result = McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
			} catch (McpToolException ex) {
				result = error(ex.getMessage());
			} catch (Throwable t) {
				logger.error("MCP tool '{}' failed", tool.name(), t);
				result = error("Internal error in " + tool.name() + ": " + t.getClass().getSimpleName() +
						(t.getMessage() == null ? "" : ": " + t.getMessage()));
			}
		}
		record(new McpCall(time, tool.name(), tool.access(), !Boolean.TRUE.equals(result.isError()),
				(System.nanoTime() - start) / 1_000_000, summarize(request.arguments())));
		return result;
	}

	private void record(@Nonnull McpCall call) {
		callLog.addLast(call);
		while (callLog.size() > MAX_CALL_LOG)
			callLog.pollFirst();
		for (Consumer<McpCall> listener : callListeners) {
			try {
				listener.accept(call);
			} catch (RuntimeException ex) {
				logger.warn("MCP call listener failed", ex);
			}
		}
	}

	private void notifyStatus() {
		for (Runnable listener : statusListeners) {
			try {
				listener.run();
			} catch (RuntimeException ex) {
				logger.warn("MCP status listener failed", ex);
			}
		}
	}

	@Nonnull
	private static String summarize(java.util.Map<String, Object> arguments) {
		if (arguments == null || arguments.isEmpty())
			return "";
		String text = McpJson.toString(McpJson.toObject(arguments));
		return text.length() <= 160 ? text : text.substring(0, 160) + "...";
	}

	@Nonnull
	private static McpSchema.CallToolResult error(@Nonnull String message) {
		return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
	}

	@Nonnull
	private static String randomToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public McpServerConfig getServiceConfig() {
		return config;
	}
}
