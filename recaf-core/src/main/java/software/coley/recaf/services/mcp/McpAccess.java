package software.coley.recaf.services.mcp;

/**
 * Capability tier of an {@link McpTool}. The server only exposes tools whose tier it was configured to allow.
 *
 * @author Matt Coley
 */
public enum McpAccess {
	/** Inspects the workspace or runs analysis without modifying anything. */
	READ,
	/** Modifies the workspace or writes output files chosen by the client. */
	WRITE,
	/** Runs arbitrary code, or reaches outside of the workspace into other processes. */
	EXECUTE
}
