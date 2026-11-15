package software.coley.recaf.services.mcp;

import com.google.gson.JsonElement;
import jakarta.annotation.Nonnull;

/**
 * Result of an {@link McpTool} invocation.
 *
 * @param text
 * 		Text shown to the model.
 *
 * @author Matt Coley
 */
public record McpToolResult(@Nonnull String text) {
	/**
	 * @param text
	 * 		Plain text.
	 *
	 * @return Result of the text.
	 */
	@Nonnull
	public static McpToolResult text(@Nonnull String text) {
		return new McpToolResult(text);
	}

	/**
	 * @param json
	 * 		Any JSON value.
	 *
	 * @return Result of the compact JSON form.
	 */
	@Nonnull
	public static McpToolResult json(@Nonnull JsonElement json) {
		return new McpToolResult(McpJson.GSON.toJson(json));
	}
}
