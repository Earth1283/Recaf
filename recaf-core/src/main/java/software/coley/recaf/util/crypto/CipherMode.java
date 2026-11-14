package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;

/**
 * Block cipher modes of operation.
 */
public enum CipherMode {
	ECB("ECB", false, true, false),
	CBC("CBC", true, true, false),
	CFB("CFB", true, false, false),
	CFB8("CFB8", true, false, false),
	OFB("OFB", true, false, false),
	CTR("CTR", true, false, false),
	CTS("CBC-CTS", true, false, false),
	GCM("GCM", true, false, true),
	CCM("CCM", true, false, true),
	EAX("EAX", true, false, true),
	OCB("OCB", true, false, true);

	private final String displayName;
	private final boolean usesIv;
	private final boolean usesPadding;
	private final boolean aead;

	CipherMode(@Nonnull String displayName, boolean usesIv, boolean usesPadding, boolean aead) {
		this.displayName = displayName;
		this.usesIv = usesIv;
		this.usesPadding = usesPadding;
		this.aead = aead;
	}

	/**
	 * @return {@code true} when the mode takes an IV or nonce.
	 */
	public boolean usesIv() {
		return usesIv;
	}

	/**
	 * @return {@code true} when the mode pads input to whole blocks.
	 */
	public boolean usesPadding() {
		return usesPadding;
	}

	/**
	 * @return {@code true} for authenticated modes, which append a tag and accept associated data.
	 */
	public boolean isAead() {
		return aead;
	}

	/**
	 * @param algorithm
	 * 		Block cipher.
	 *
	 * @return {@code true} when this mode can be used with the algorithm.
	 */
	public boolean supports(@Nonnull CipherAlgorithm algorithm) {
		if (!algorithm.isBlockCipher())
			return false;
		if (this == GCM || this == CCM || this == OCB)
			return algorithm.blockSize() == 16;
		return true;
	}

	/**
	 * @param algorithm
	 * 		Block cipher.
	 *
	 * @return Default IV/nonce length in bytes for the algorithm in this mode.
	 */
	public int defaultIvSize(@Nonnull CipherAlgorithm algorithm) {
		return switch (this) {
			case ECB -> 0;
			case GCM, CCM, OCB -> 12;
			default -> algorithm.blockSize();
		};
	}

	@Override
	public String toString() {
		return displayName;
	}
}
