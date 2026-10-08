package software.coley.recaf.services.analysis.plugin.api;

import java.io.IOException;

/**
 * Thrown when a file is larger than the size limit it was requested with.
 */
public class FileTooLargeException extends IOException {
	/**
	 * @param name
	 * 		Name or location of the file.
	 * @param maxBytes
	 * 		The limit that was exceeded.
	 */
	public FileTooLargeException(String name, long maxBytes) {
		super("File is larger than the " + maxBytes + " byte limit: " + name);
	}
}
