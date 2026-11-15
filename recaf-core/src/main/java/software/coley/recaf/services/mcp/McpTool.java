package software.coley.recaf.services.mcp;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;

/**
 * Definition of a single tool exposed to MCP clients.
 *
 * @param name
 * 		Unique tool name.
 * @param description
 * 		Explanation shown to the model, which decides from this when to call the tool.
 * @param access
 * 		Capability tier required to expose the tool.
 * @param inputSchema
 * 		JSON schema of the arguments.
 * @param handler
 * 		Implementation.
 *
 * @author Matt Coley
 */
public record McpTool(@Nonnull String name, @Nonnull String description, @Nonnull McpAccess access,
                      @Nonnull JsonObject inputSchema, @Nonnull Handler handler) {
	/**
	 * Tool implementation.
	 */
	@FunctionalInterface
	public interface Handler {
		/**
		 * @param args
		 * 		Client arguments.
		 *
		 * @return Result to show to the model.
		 *
		 * @throws Exception
		 * 		Any failure. {@link McpToolException} messages are shown as-is.
		 */
		@Nonnull
		McpToolResult call(@Nonnull McpArgs args) throws Exception;
	}
}
