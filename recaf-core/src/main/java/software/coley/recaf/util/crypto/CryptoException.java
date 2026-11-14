package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Thrown when a crypto tool operation cannot be completed, such as bad padding, a wrong key length or malformed input.
 */
public class CryptoException extends Exception {
	/**
	 * @param message
	 * 		Human-readable reason.
	 */
	public CryptoException(@Nonnull String message) {
		super(message);
	}

	/**
	 * @param message
	 * 		Human-readable reason.
	 * @param cause
	 * 		Underlying failure.
	 */
	public CryptoException(@Nonnull String message, @Nullable Throwable cause) {
		super(message, cause);
	}
}
