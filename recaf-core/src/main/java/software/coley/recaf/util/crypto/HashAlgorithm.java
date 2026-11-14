package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.Xof;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.Blake2sDigest;
import org.bouncycastle.crypto.digests.Blake3Digest;
import org.bouncycastle.crypto.digests.GOST3411Digest;
import org.bouncycastle.crypto.digests.GOST3411_2012_256Digest;
import org.bouncycastle.crypto.digests.GOST3411_2012_512Digest;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.MD2Digest;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.digests.RIPEMD128Digest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.RIPEMD256Digest;
import org.bouncycastle.crypto.digests.RIPEMD320Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA224Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.digests.SHA512tDigest;
import org.bouncycastle.crypto.digests.SHAKEDigest;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.digests.TigerDigest;
import org.bouncycastle.crypto.digests.WhirlpoolDigest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

import java.util.function.Supplier;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * Message digests and checksums.
 */
public enum HashAlgorithm {
	MD2("MD2", MD2Digest::new),
	MD4("MD4", MD4Digest::new),
	MD5("MD5", MD5Digest::new),
	SHA1("SHA-1", SHA1Digest::new),
	SHA224("SHA-224", SHA224Digest::new),
	SHA256("SHA-256", SHA256Digest::new),
	SHA384("SHA-384", SHA384Digest::new),
	SHA512("SHA-512", SHA512Digest::new),
	SHA512_224("SHA-512/224", () -> new SHA512tDigest(224)),
	SHA512_256("SHA-512/256", () -> new SHA512tDigest(256)),
	SHA3_224("SHA3-224", () -> new SHA3Digest(224)),
	SHA3_256("SHA3-256", () -> new SHA3Digest(256)),
	SHA3_384("SHA3-384", () -> new SHA3Digest(384)),
	SHA3_512("SHA3-512", () -> new SHA3Digest(512)),
	KECCAK_256("Keccak-256", () -> new KeccakDigest(256)),
	KECCAK_512("Keccak-512", () -> new KeccakDigest(512)),
	SHAKE128("SHAKE128 (256-bit)", () -> new SHAKEDigest(128)),
	SHAKE256("SHAKE256 (512-bit)", () -> new SHAKEDigest(256)),
	RIPEMD128("RIPEMD-128", RIPEMD128Digest::new),
	RIPEMD160("RIPEMD-160", RIPEMD160Digest::new),
	RIPEMD256("RIPEMD-256", RIPEMD256Digest::new),
	RIPEMD320("RIPEMD-320", RIPEMD320Digest::new),
	WHIRLPOOL("Whirlpool", WhirlpoolDigest::new),
	TIGER("Tiger", TigerDigest::new),
	SM3("SM3", SM3Digest::new),
	GOST3411("GOST R 34.11-94", GOST3411Digest::new),
	STREEBOG256("Streebog-256", GOST3411_2012_256Digest::new),
	STREEBOG512("Streebog-512", GOST3411_2012_512Digest::new),
	BLAKE2B_256("BLAKE2b-256", () -> new Blake2bDigest(256)),
	BLAKE2B_512("BLAKE2b-512", () -> new Blake2bDigest(512)),
	BLAKE2S_256("BLAKE2s-256", () -> new Blake2sDigest(256)),
	BLAKE3("BLAKE3-256", () -> new Blake3Digest(256)),
	CRC32("CRC-32", null),
	CRC32C("CRC-32C", null),
	ADLER32("Adler-32", null);

	private final String displayName;
	private final Supplier<Digest> digestFactory;

	HashAlgorithm(@Nonnull String displayName, @Nullable Supplier<Digest> digestFactory) {
		this.displayName = displayName;
		this.digestFactory = digestFactory;
	}

	/**
	 * @return Display name of the algorithm.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @return {@code true} for 32-bit checksums, which cannot back an HMAC or key derivation.
	 */
	public boolean isChecksum() {
		return digestFactory == null;
	}

	/**
	 * @return New digest instance.
	 *
	 * @throws IllegalStateException
	 * 		When this is a {@link #isChecksum() checksum}.
	 */
	@Nonnull
	public Digest newDigest() {
		if (digestFactory == null)
			throw new IllegalStateException(displayName + " is a checksum, not a digest");
		return digestFactory.get();
	}

	/**
	 * @param data
	 * 		Data to hash.
	 *
	 * @return Hash of the data. Checksums are returned as 4 big-endian bytes.
	 */
	@Nonnull
	public byte[] hash(@Nonnull byte[] data) {
		if (digestFactory == null) {
			Checksum checksum = switch (this) {
				case CRC32 -> new CRC32();
				case CRC32C -> new CRC32C();
				default -> new Adler32();
			};
			checksum.update(data, 0, data.length);
			long value = checksum.getValue();
			return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
		}
		Digest digest = digestFactory.get();
		digest.update(data, 0, data.length);
		byte[] out = new byte[digest.getDigestSize()];
		if (digest instanceof Xof xof)
			xof.doFinal(out, 0, out.length);
		else
			digest.doFinal(out, 0);
		return out;
	}

	/**
	 * @param key
	 * 		HMAC key.
	 * @param data
	 * 		Data to authenticate.
	 *
	 * @return HMAC of the data.
	 *
	 * @throws CryptoException
	 * 		When this is a {@link #isChecksum() checksum}.
	 */
	@Nonnull
	public byte[] hmac(@Nonnull byte[] key, @Nonnull byte[] data) throws CryptoException {
		if (digestFactory == null)
			throw new CryptoException(displayName + " is a checksum and cannot be used for HMAC");
		HMac mac;
		try {
			mac = new HMac(digestFactory.get());
		} catch (IllegalArgumentException ex) {
			throw new CryptoException(displayName + " cannot be used for HMAC", ex);
		}
		mac.init(new KeyParameter(key));
		mac.update(data, 0, data.length);
		byte[] out = new byte[mac.getMacSize()];
		mac.doFinal(out, 0);
		return out;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
