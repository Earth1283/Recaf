package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;

/**
 * Downloads files for {@link MinecraftPluginApiService}.
 * Exists as a seam so the network can be replaced, for example in tests.
 */
public interface PluginApiFetcher {
	/**
	 * @param url
	 * 		Location of the file.
	 * @param maxBytes
	 * 		Maximum number of bytes to accept.
	 *
	 * @return Content of the file, or {@code null} if the server reports that the file does not exist.
	 *
	 * @throws IOException
	 * 		When the file could not be read for any other reason, including exceeding the size limit.
	 */
	@Nullable
	byte[] fetch(@Nonnull String url, long maxBytes) throws IOException;
}
