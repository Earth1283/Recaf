package software.coley.recaf.services.mcp.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import software.coley.recaf.analytics.logging.Logging;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stateless streamable HTTP transport on top of the JDK's built-in server.
 * <p/>
 * The SDK's own HTTP transports need a servlet container, which is a large dependency for a server that only has
 * to answer one POST at a time. A stateless server is a good fit since all of the real state is the open workspace,
 * which lives in Recaf and not in the MCP session.
 * <p/>
 * Binds to loopback only, requires a bearer token, and rejects requests whose {@code Host} or {@code Origin} is not
 * loopback. The last check is what stops a web page from reaching the server through DNS rebinding.
 *
 * @author Matt Coley
 */
public class LoopbackHttpTransport implements McpStatelessServerTransport {
	private static final Logger logger = Logging.get(LoopbackHttpTransport.class);
	private static final String PATH = "/mcp";
	private static final int MAX_BODY = 16 * 1024 * 1024;
	private static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);
	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
	private final McpJsonMapper mapper;
	private final int port;
	private final byte[] token;
	private HttpServer server;
	private ExecutorService executor;
	private McpStatelessServerHandler handler;

	/**
	 * @param mapper
	 * 		JSON mapper for the protocol messages.
	 * @param port
	 * 		Port to listen on, {@code 0} for any free port.
	 * @param token
	 * 		Bearer token clients must present.
	 */
	public LoopbackHttpTransport(@Nonnull McpJsonMapper mapper, int port, @Nonnull String token) {
		this.mapper = mapper;
		this.port = port;
		this.token = token.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public void setMcpHandler(@Nonnull McpStatelessServerHandler handler) {
		this.handler = handler;
	}

	/**
	 * Starts listening. Must be called after the server has installed the handler.
	 *
	 * @throws IOException
	 * 		When the port cannot be bound.
	 */
	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "mcp-http");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext(PATH, this::handle);
		server.start();
	}

	/**
	 * @return Port actually bound.
	 */
	public int getBoundPort() {
		return server.getAddress().getPort();
	}

	@Nonnull
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(this::close);
	}

	@Override
	public void close() {
		if (server != null)
			server.stop(0);
		if (executor != null)
			executor.shutdownNow();
	}

	private void handle(@Nonnull HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!isLoopback(exchange.getRequestHeaders().getFirst("Host"), true) ||
					!isLoopback(exchange.getRequestHeaders().getFirst("Origin"), false)) {
				respond(exchange, 403, "Forbidden origin");
				return;
			}
			if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
				exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
				respond(exchange, 401, "Missing or invalid bearer token");
				return;
			}
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().add("Allow", "POST");
				respond(exchange, 405, "Only POST is supported");
				return;
			}

			byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
			if (body.length > MAX_BODY) {
				respond(exchange, 413, "Request too large");
				return;
			}

			McpSchema.JSONRPCMessage message;
			try {
				message = McpSchema.deserializeJsonRpcMessage(mapper, new String(body, StandardCharsets.UTF_8));
			} catch (IOException | RuntimeException ex) {
				respond(exchange, 400, "Invalid JSON-RPC message");
				return;
			}

			McpTransportContext context = McpTransportContext.EMPTY;
			if (message instanceof McpSchema.JSONRPCRequest request) {
				McpSchema.JSONRPCResponse response;
				try {
					response = handler.handleRequest(context, request).block(CALL_TIMEOUT);
				} catch (RuntimeException ex) {
					logger.error("MCP request '{}' failed", request.method(), ex);
					response = McpSchema.JSONRPCResponse.error(request.id(),
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
									"Internal error", null));
				}
				if (response == null) {
					respond(exchange, 500, "No response");
					return;
				}
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				respond(exchange, 200, mapper.writeValueAsString(response));
			} else if (message instanceof McpSchema.JSONRPCNotification notification) {
				handler.handleNotification(context, notification).block(CALL_TIMEOUT);
				respond(exchange, 202, "");
			} else {
				respond(exchange, 202, "");
			}
		}
	}

	private boolean isAuthorized(String header) {
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7))
			return false;
		byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(presented, token);
	}

	private static boolean isLoopback(String value, boolean hostHeader) {
		if (value == null)
			return !hostHeader;
		String host = value.toLowerCase(Locale.ROOT);
		int scheme = host.indexOf("://");
		if (scheme >= 0)
			host = host.substring(scheme + 3);
		int slash = host.indexOf('/');
		if (slash >= 0)
			host = host.substring(0, slash);
		int colon = host.lastIndexOf(':');
		if (colon > host.lastIndexOf(']'))
			host = host.substring(0, colon);
		return LOOPBACK_HOSTS.contains(host);
	}

	private static void respond(@Nonnull HttpExchange exchange, int status, @Nonnull String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0)
			exchange.getResponseBody().write(bytes);
	}
}
