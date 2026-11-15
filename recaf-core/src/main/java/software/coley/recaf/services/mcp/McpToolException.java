package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;

/**
 * Failure with a message that is safe and useful to show to the MCP client.
 * Any other exception type thrown from a tool is reported as an internal error.
 *
 * @author Matt Coley
 */
public class McpToolException extends RuntimeException {
	/**
	 * @param message
	 * 		Message shown to the client.
	 */
	public McpToolException(@Nonnull String message) {
		super(message);
	}

	/**
	 * @param message
	 * 		Message shown to the client.
	 * @param cause
	 * 		Underlying cause.
	 */
	public McpToolException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}
}
