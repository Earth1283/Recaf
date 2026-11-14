package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Full configuration of a symmetric cipher operation.
 *
 * @param algorithm
 * 		Cipher.
 * @param mode
 * 		Mode of operation, ignored for stream ciphers.
 * @param padding
 * 		Padding, only used by modes that {@link CipherMode#usesPadding() pad}.
 * @param keyMaterial
 * 		Raw key or passphrase bytes.
 * @param kdf
 * 		How the key is derived from the key material.
 * @param keySize
 * 		Key size in bytes for derived keys, or {@code 0} for the algorithm default. Raw keys use their own length.
 * @param ivSource
 * 		Where the IV/nonce comes from.
 * @param iv
 * 		Explicit IV, used with {@link IvSource#EXPLICIT}.
 * @param ivSize
 * 		IV/nonce size in bytes, or {@code 0} for the mode/algorithm default.
 * @param tagBits
 * 		Authentication tag size in bits, for authenticated modes.
 * @param aad
 * 		Associated data for authenticated modes, or {@code null}.
 */
public record SymmetricCipherSpec(@Nonnull CipherAlgorithm algorithm,
                                  @Nonnull CipherMode mode,
                                  @Nonnull PaddingScheme padding,
                                  @Nonnull byte[] keyMaterial,
                                  @Nonnull KeyDerivation kdf,
                                  int keySize,
                                  @Nonnull IvSource ivSource,
                                  @Nullable byte[] iv,
                                  int ivSize,
                                  int tagBits,
                                  @Nullable byte[] aad) {
	/**
	 * Copies array arguments.
	 */
	public SymmetricCipherSpec {
		Objects.requireNonNull(algorithm);
		Objects.requireNonNull(mode);
		Objects.requireNonNull(padding);
		Objects.requireNonNull(kdf);
		Objects.requireNonNull(ivSource);
		keyMaterial = keyMaterial.clone();
		iv = iv == null ? null : iv.clone();
		aad = aad == null ? null : aad.clone();
		if (tagBits <= 0)
			tagBits = 128;
	}

	/**
	 * @param algorithm
	 * 		Cipher.
	 * @param mode
	 * 		Mode of operation.
	 * @param padding
	 * 		Padding.
	 * @param key
	 * 		Raw key.
	 * @param ivSource
	 * 		IV source.
	 * @param iv
	 * 		Explicit IV, or {@code null}.
	 *
	 * @return Spec using a raw key and default sizes.
	 */
	@Nonnull
	public static SymmetricCipherSpec of(@Nonnull CipherAlgorithm algorithm, @Nonnull CipherMode mode,
	                                     @Nonnull PaddingScheme padding, @Nonnull byte[] key,
	                                     @Nonnull IvSource ivSource, @Nullable byte[] iv) {
		return new SymmetricCipherSpec(algorithm, mode, padding, key, KeyDerivation.RAW, 0, ivSource, iv, 0, 128, null);
	}

	/**
	 * @return Effective IV/nonce size in bytes, {@code 0} when no IV is used.
	 */
	public int effectiveIvSize() {
		if (!algorithm.isBlockCipher())
			return algorithm.nonceSize();
		if (!mode.usesIv())
			return 0;
		return ivSize > 0 ? ivSize : mode.defaultIvSize(algorithm);
	}

	/**
	 * @return Effective key size in bytes for derived keys.
	 */
	public int effectiveKeySize() {
		if (kdf.type() == KeyDerivation.Type.RAW)
			return keyMaterial.length;
		return keySize > 0 ? keySize : algorithm.defaultKeySize();
	}

	/**
	 * @return Short description, such as {@code AES-128/CBC/PKCS#5, SHA-256(passphrase), IV prefixed}.
	 */
	@Nonnull
	public String describe() {
		StringBuilder sb = new StringBuilder(algorithm.name());
		if (algorithm.keySizes().length > 1)
			sb.append('-').append(effectiveKeySize() * 8);
		if (algorithm.isBlockCipher()) {
			sb.append('/').append(mode);
			if (mode.usesPadding())
				sb.append('/').append(padding == PaddingScheme.PKCS7 ? "PKCS5" : padding.name());
		}
		sb.append(", ").append(kdf.describe());
		if (effectiveIvSize() > 0) {
			sb.append(", IV ");
			if (ivSource == IvSource.EXPLICIT && iv != null)
				sb.append(HexFormat.of().formatHex(iv));
			else
				sb.append(ivSource.toString().toLowerCase());
		}
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof SymmetricCipherSpec other && algorithm == other.algorithm && mode == other.mode
				&& padding == other.padding && Arrays.equals(keyMaterial, other.keyMaterial) && kdf.equals(other.kdf)
				&& keySize == other.keySize && ivSource == other.ivSource && Arrays.equals(iv, other.iv)
				&& ivSize == other.ivSize && tagBits == other.tagBits && Arrays.equals(aad, other.aad);
	}

	@Override
	public int hashCode() {
		return Objects.hash(algorithm, mode, padding, Arrays.hashCode(keyMaterial), kdf, keySize, ivSource,
				Arrays.hashCode(iv), ivSize, tagBits, Arrays.hashCode(aad));
	}

	@Override
	public String toString() {
		return describe();
	}
}
