package software.coley.recaf.services.analysis.plugin.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UrlPluginApiFetcher} against a real HTTP server on the loopback interface.
 */
class UrlPluginApiFetcherTest {
	private static final byte[] BODY = "hello".getBytes(StandardCharsets.UTF_8);
	private final UrlPluginApiFetcher fetcher = new UrlPluginApiFetcher();
	private HttpServer server;
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/ok", exchange -> reply(exchange, 200, BODY));
		server.createContext("/big", exchange -> reply(exchange, 200, new byte[100_000]));
		server.createContext("/error", exchange -> reply(exchange, 500, new byte[0]));
		server.createContext("/chunked", exchange -> {
			// Length unknown up front, so the declared length can't be trusted to enforce the limit.
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(new byte[100_000]);
			}
		});
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", base + "/ok");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0)
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		exchange.close();
	}

	@Test
	void fetchesContent() throws IOException {
		assertArrayEquals(BODY, fetcher.fetch(base + "/ok", 1024));
	}

	@Test
	void smallLimitsWork() throws IOException {
		// The limit used for checksum files is smaller than typical buffer sizes.
		assertArrayEquals(BODY, fetcher.fetch(base + "/ok", 16));
	}

	@Test
	void missingFilesAreNullRatherThanErrors() throws IOException {
		assertNull(fetcher.fetch(base + "/does-not-exist", 1024));
	}

	@Test
	void otherStatusesAreErrors() {
		assertThrows(IOException.class, () -> fetcher.fetch(base + "/error", 1024));
	}

	@Test
	void enforcesTheSizeLimitFromTheDeclaredLength() {
		IOException ex = assertThrows(IOException.class, () -> fetcher.fetch(base + "/big", 1000));
		assertTrue(ex.getMessage().contains("limit"));
	}

	@Test
	void enforcesTheSizeLimitWhenTheLengthIsNotDeclared() {
		IOException ex = assertThrows(IOException.class, () -> fetcher.fetch(base + "/chunked", 1000));
		assertTrue(ex.getMessage().contains("limit"));
	}

	@Test
	void followsRedirects() throws IOException {
		assertArrayEquals(BODY, fetcher.fetch(base + "/redirect", 1024));
	}

	@Test
	void refusesPlainHttpToNonLoopbackHosts() {
		IOException ex = assertThrows(IOException.class, () -> fetcher.fetch("http://repo.example.com/file.jar", 1024));
		assertTrue(ex.getMessage().contains("insecure"));
	}

	@Test
	void refusesOtherSchemes() {
		assertThrows(IOException.class, () -> fetcher.fetch("file:///etc/passwd", 1024));
		assertThrows(IOException.class, () -> fetcher.fetch("ftp://repo.example.com/file.jar", 1024));
	}
}
