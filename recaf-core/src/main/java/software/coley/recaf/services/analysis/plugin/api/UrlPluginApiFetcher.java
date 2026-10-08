package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Fetcher that downloads over HTTPS.
 * Plain HTTP is only accepted for loopback addresses, to support local repository mirrors.
 */
@ApplicationScoped
public class UrlPluginApiFetcher implements PluginApiFetcher {
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 30_000;

	@Nullable
	@Override
	public byte[] fetch(@Nonnull String url, long maxBytes) throws IOException {
		URI uri = URI.create(url);
		String scheme = uri.getScheme();
		String host = uri.getHost();
		boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
		if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && loopback))
			throw new IOException("Refusing to download over an insecure scheme: " + url);

		URL target = uri.toURL();
		HttpURLConnection connection = (HttpURLConnection) target.openConnection();
		try {
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setInstanceFollowRedirects(true);
			connection.setRequestProperty("User-Agent", "Recaf");
			int status = connection.getResponseCode();
			if (status == HttpURLConnection.HTTP_NOT_FOUND)
				return null;
			if (status != HttpURLConnection.HTTP_OK)
				throw new IOException("Unexpected response " + status + " for " + url);

			long declared = connection.getContentLengthLong();
			if (declared > maxBytes)
				throw new FileTooLargeException(url, maxBytes);
			try (InputStream in = connection.getInputStream()) {
				// The declared length is only a hint (it is -1 when unknown), and never larger than the limit.
				int initialSize = (int) Math.min(maxBytes, Math.max(declared, 1024));
				ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize);
				byte[] buffer = new byte[16384];
				long total = 0;
				int read;
				while ((read = in.read(buffer)) != -1) {
					total += read;
					if (total > maxBytes)
						throw new FileTooLargeException(url, maxBytes);
					out.write(buffer, 0, read);
				}
				return out.toByteArray();
			}
		} finally {
			connection.disconnect();
		}
	}
}
