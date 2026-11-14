package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.ARIAEngine;
import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.engines.CAST5Engine;
import org.bouncycastle.crypto.engines.CAST6Engine;
import org.bouncycastle.crypto.engines.CamelliaEngine;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.bouncycastle.crypto.engines.DESEngine;
import org.bouncycastle.crypto.engines.DESedeEngine;
import org.bouncycastle.crypto.engines.GOST28147Engine;
import org.bouncycastle.crypto.engines.Grain128Engine;
import org.bouncycastle.crypto.engines.HC128Engine;
import org.bouncycastle.crypto.engines.HC256Engine;
import org.bouncycastle.crypto.engines.IDEAEngine;
import org.bouncycastle.crypto.engines.ISAACEngine;
import org.bouncycastle.crypto.engines.LEAEngine;
import org.bouncycastle.crypto.engines.NoekeonEngine;
import org.bouncycastle.crypto.engines.RC2Engine;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.engines.RC532Engine;
import org.bouncycastle.crypto.engines.RC6Engine;
import org.bouncycastle.crypto.engines.SEEDEngine;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.engines.SerpentEngine;
import org.bouncycastle.crypto.engines.SkipjackEngine;
import org.bouncycastle.crypto.engines.TEAEngine;
import org.bouncycastle.crypto.engines.TwofishEngine;
import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.engines.XTEAEngine;
import org.bouncycastle.crypto.engines.Zuc128Engine;
import org.bouncycastle.crypto.engines.Zuc256Engine;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Symmetric ciphers. Block ciphers combine with a {@link CipherMode}; stream ciphers are used directly.
 */
public enum CipherAlgorithm {
	AES("AES", 16, sizes(16, 24, 32), 16, 32, AESEngine::newInstance, true),
	DES("DES", 8, sizes(8), 8, 8, DESEngine::new, true),
	DESEDE("Triple DES (DESede)", 8, sizes(24, 16), 16, 24, DESedeEngine::new, true),
	BLOWFISH("Blowfish", 8, sizes(16, 8, 32, 56), 4, 56, BlowfishEngine::new, true),
	TWOFISH("Twofish", 16, sizes(16, 24, 32), 16, 32, TwofishEngine::new, false),
	SERPENT("Serpent", 16, sizes(16, 24, 32), 1, 32, SerpentEngine::new, false),
	CAMELLIA("Camellia", 16, sizes(16, 24, 32), 16, 32, CamelliaEngine::new, false),
	ARIA("ARIA", 16, sizes(16, 24, 32), 16, 32, ARIAEngine::new, false),
	CAST5("CAST5", 8, sizes(16, 5), 5, 16, CAST5Engine::new, false),
	CAST6("CAST6", 16, sizes(32, 16, 24), 16, 32, CAST6Engine::new, false),
	IDEA("IDEA", 8, sizes(16), 16, 16, IDEAEngine::new, false),
	RC2("RC2", 8, sizes(16, 5, 8), 1, 128, RC2Engine::new, false),
	RC5("RC5-32/12", 8, sizes(16), 1, 255, RC532Engine::new, false),
	RC6("RC6", 16, sizes(16, 24, 32), 1, 255, RC6Engine::new, false),
	SEED("SEED", 16, sizes(16), 16, 16, SEEDEngine::new, false),
	SM4("SM4", 16, sizes(16), 16, 16, SM4Engine::new, false),
	LEA("LEA", 16, sizes(16, 24, 32), 16, 32, LEAEngine::new, false),
	NOEKEON("Noekeon", 16, sizes(16), 16, 16, NoekeonEngine::new, false),
	GOST28147("GOST 28147-89", 8, sizes(32), 32, 32, GOST28147Engine::new, false),
	SKIPJACK("Skipjack", 8, sizes(10), 10, 10, SkipjackEngine::new, false),
	TEA("TEA", 8, sizes(16), 16, 16, TEAEngine::new, false),
	XTEA("XTEA", 8, sizes(16), 16, 16, XTEAEngine::new, false),
	RC4("RC4 (ARCFOUR)", sizes(16, 5, 8, 32), 1, 256, 0, RC4Engine::new, true),
	CHACHA20("ChaCha20 (original, 8-byte nonce)", sizes(32, 16), 16, 32, 8, ChaChaEngine::new, false),
	CHACHA20_IETF("ChaCha20 (RFC 7539, 12-byte nonce)", sizes(32), 32, 32, 12, ChaCha7539Engine::new, true),
	CHACHA20_POLY1305("ChaCha20-Poly1305", sizes(32), 32, 32, 12),
	SALSA20("Salsa20", sizes(32, 16), 16, 32, 8, Salsa20Engine::new, false),
	XSALSA20("XSalsa20", sizes(32), 32, 32, 24, XSalsa20Engine::new, false),
	HC128("HC-128", sizes(16), 16, 16, 16, HC128Engine::new, false),
	HC256("HC-256", sizes(32), 32, 32, 32, HC256Engine::new, false),
	GRAIN128("Grain-128", sizes(16), 16, 16, 12, Grain128Engine::new, false),
	ZUC128("ZUC-128", sizes(16), 16, 16, 16, Zuc128Engine::new, false),
	ZUC256("ZUC-256", sizes(32), 32, 32, 25, Zuc256Engine::new, false),
	ISAAC("ISAAC", sizes(16, 32), 1, 1024, 0, ISAACEngine::new, false);

	private final String displayName;
	private final int blockSize;
	private final int[] keySizes;
	private final int minKeySize;
	private final int maxKeySize;
	private final int nonceSize;
	private final Supplier<BlockCipher> blockFactory;
	private final Supplier<StreamCipher> streamFactory;
	private final boolean common;

	CipherAlgorithm(@Nonnull String displayName, int blockSize, @Nonnull int[] keySizes, int minKeySize, int maxKeySize,
	                @Nonnull Supplier<BlockCipher> blockFactory, boolean common) {
		this.displayName = displayName;
		this.blockSize = blockSize;
		this.keySizes = keySizes;
		this.minKeySize = minKeySize;
		this.maxKeySize = maxKeySize;
		this.nonceSize = 0;
		this.blockFactory = blockFactory;
		this.streamFactory = null;
		this.common = common;
	}

	CipherAlgorithm(@Nonnull String displayName, @Nonnull int[] keySizes, int minKeySize, int maxKeySize, int nonceSize,
	                @Nonnull Supplier<StreamCipher> streamFactory, boolean common) {
		this.displayName = displayName;
		this.blockSize = 0;
		this.keySizes = keySizes;
		this.minKeySize = minKeySize;
		this.maxKeySize = maxKeySize;
		this.nonceSize = nonceSize;
		this.blockFactory = null;
		this.streamFactory = streamFactory;
		this.common = common;
	}

	CipherAlgorithm(@Nonnull String displayName, @Nonnull int[] keySizes, int minKeySize, int maxKeySize, int nonceSize) {
		this.displayName = displayName;
		this.blockSize = 0;
		this.keySizes = keySizes;
		this.minKeySize = minKeySize;
		this.maxKeySize = maxKeySize;
		this.nonceSize = nonceSize;
		this.blockFactory = null;
		this.streamFactory = null;
		this.common = false;
	}

	@Nonnull
	private static int[] sizes(int... sizes) {
		return sizes;
	}

	/**
	 * @return Display name of the algorithm.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @return {@code true} for block ciphers, which need a {@link CipherMode}.
	 */
	public boolean isBlockCipher() {
		return blockFactory != null;
	}

	/**
	 * @return {@code true} for authenticated stream ciphers.
	 */
	public boolean isAead() {
		return this == CHACHA20_POLY1305;
	}

	/**
	 * @return Block size in bytes, or {@code 0} for stream ciphers.
	 */
	public int blockSize() {
		return blockSize;
	}

	/**
	 * @return Key sizes in bytes worth trying, most common first.
	 */
	@Nonnull
	public int[] keySizes() {
		return keySizes.clone();
	}

	/**
	 * @return Default key size in bytes.
	 */
	public int defaultKeySize() {
		return keySizes[0];
	}

	/**
	 * @param size
	 * 		Key size in bytes.
	 *
	 * @return {@code true} when the algorithm accepts a key of the given size.
	 */
	public boolean isValidKeySize(int size) {
		return switch (this) {
			case BLOWFISH, SERPENT, CAST5, CAST6, RC2, RC5, RC6, RC4, ISAAC -> size >= minKeySize && size <= maxKeySize;
			default -> Arrays.stream(keySizes).anyMatch(s -> s == size);
		};
	}

	/**
	 * @return Nonce/IV size in bytes for stream ciphers, or {@code 0} if the stream cipher takes none.
	 * Block ciphers return {@code 0}, their IV size depends on the {@link CipherMode}.
	 */
	public int nonceSize() {
		return nonceSize;
	}

	/**
	 * @return {@code true} for algorithms widely used in the wild, tried first when brute forcing.
	 */
	public boolean isCommon() {
		return common;
	}

	/**
	 * @return New block cipher engine.
	 */
	@Nonnull
	public BlockCipher newBlockCipher() {
		if (blockFactory == null)
			throw new IllegalStateException(displayName + " is not a block cipher");
		return blockFactory.get();
	}

	/**
	 * @return New stream cipher engine.
	 */
	@Nonnull
	public StreamCipher newStreamCipher() {
		if (streamFactory == null)
			throw new IllegalStateException(displayName + " is not a plain stream cipher");
		return streamFactory.get();
	}

	@Override
	public String toString() {
		return displayName;
	}
}
