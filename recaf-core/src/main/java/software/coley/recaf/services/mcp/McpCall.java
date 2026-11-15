package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;

import java.time.Instant;

/**
 * Record of one tool invocation, for showing what a client has been doing.
 *
 * @param time
 * 		When the call started.
 * @param tool
 * 		Tool name.
 * @param access
 * 		Tier of the tool.
 * @param success
 * 		{@code false} when the tool reported an error.
 * @param millis
 * 		How long the call took.
 * @param summary
 * 		The arguments, shortened.
 *
 * @author Matt Coley
 */
public record McpCall(@Nonnull Instant time, @Nonnull String tool, @Nonnull McpAccess access, boolean success,
                      long millis, @Nonnull String summary) {}
