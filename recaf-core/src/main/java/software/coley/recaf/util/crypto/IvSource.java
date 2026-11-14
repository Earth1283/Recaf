package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;

/**
 * Where a cipher's IV or nonce comes from.
 */
public enum IvSource {
	/** All zero bytes. */
	ZERO("All zeros"),
	/** Bytes the user supplies. */
	EXPLICIT("Explicit"),
	/** Leading bytes of the ciphertext. On encryption a random IV is generated and prepended. */
	PREFIX("Prefixed to data"),
	/** Leading bytes of the key, as in {@code new IvParameterSpec(key)}. */
	KEY("Same as key"),
	/** Produced by the {@link KeyDerivation} alongside the key, as OpenSSL does. */
	DERIVED("From key derivation");

	private final String displayName;

	IvSource(@Nonnull String displayName) {
		this.displayName = displayName;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
