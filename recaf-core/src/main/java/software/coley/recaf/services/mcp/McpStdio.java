package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.analytics.logging.RecafLoggingFilter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Claims the process's standard streams for the MCP stdio transport.
 * <p/>
 * The protocol owns stdout, so anything else that prints there corrupts the stream. {@link #claim()} moves the
 * rest of the process onto stderr and keeps the real streams for the transport.
 *
 * @author Matt Coley
 */
public final class McpStdio {
	private static InputStream in;
	private static OutputStream out;
	private static Runnable endOfInputHook = () -> {};

	private McpStdio() {}

	/**
	 * Must run before anything can print or log, which means before the CDI container is created and before any
	 * class with a logger is initialized.
	 */
	public static synchronized void claim() {
		if (out != null)
			return;
		if (!Logging.redirectConsoleToStandardError())
			RecafLoggingFilter.setConsoleLevel(System.Logger.Level.OFF);
		in = new EndOfInputInputStream(System.in);
		out = System.out;
		System.setOut(System.err);
	}

	/**
	 * @return {@code true} when {@link #claim()} was called.
	 */
	public static synchronized boolean isClaimed() {
		return out != null;
	}

	/**
	 * @return Stream the client writes requests to.
	 */
	@Nonnull
	public static synchronized InputStream input() {
		if (in == null)
			throw new IllegalStateException("Stdio was not claimed");
		return in;
	}

	/**
	 * @return Stream responses are written to.
	 */
	@Nonnull
	public static synchronized OutputStream output() {
		if (out == null)
			throw new IllegalStateException("Stdio was not claimed");
		return out;
	}

	/**
	 * @param hook
	 * 		Action to run when the client closes its end of the pipe, which is how stdio clients say they are done.
	 */
	public static synchronized void onEndOfInput(@Nonnull Runnable hook) {
		endOfInputHook = hook;
	}

	private static synchronized Runnable endOfInputHook() {
		return endOfInputHook;
	}

	private static class EndOfInputInputStream extends FilterInputStream {
		private boolean fired;

		private EndOfInputInputStream(@Nonnull InputStream delegate) {
			super(delegate);
		}

		@Override
		public int read() throws IOException {
			return check(super.read());
		}

		@Override
		public int read(@Nonnull byte[] b, int off, int len) throws IOException {
			return check(super.read(b, off, len));
		}

		private int check(int result) {
			if (result < 0) {
				boolean first;
				synchronized (this) {
					first = !fired;
					fired = true;
				}
				if (first)
					endOfInputHook().run();
			}
			return result;
		}
	}
}
