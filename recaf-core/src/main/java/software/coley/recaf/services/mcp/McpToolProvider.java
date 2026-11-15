package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;

/**
 * Contributes tools to the MCP server.
 * <p/>
 * Implementations are discovered as CDI beans, so plugins can add their own tools by providing an
 * application scoped implementation of this interface.
 *
 * @author Matt Coley
 */
public interface McpToolProvider {
	/**
	 * @param registry
	 * 		Registry to add tools to.
	 */
	void registerTools(@Nonnull McpToolRegistry registry);
}
