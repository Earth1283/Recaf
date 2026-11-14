package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.ISO10126d2Padding;
import org.bouncycastle.crypto.paddings.ISO7816d4Padding;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.TBCPadding;
import org.bouncycastle.crypto.paddings.X923Padding;
import org.bouncycastle.crypto.paddings.ZeroBytePadding;

import java.util.function.Supplier;

/**
 * Block paddings for {@link CipherMode#ECB} and {@link CipherMode#CBC}.
 */
public enum PaddingScheme {
	PKCS7("PKCS#5 / PKCS#7", PKCS7Padding::new),
	NONE("No padding", null),
	ZERO_BYTE("Zero bytes", ZeroBytePadding::new),
	ISO10126("ISO 10126", ISO10126d2Padding::new),
	X923("ANSI X9.23", X923Padding::new),
	ISO7816("ISO 7816-4", ISO7816d4Padding::new),
	TBC("Trailing bit complement", TBCPadding::new);

	private final String displayName;
	private final Supplier<BlockCipherPadding> factory;

	PaddingScheme(@Nonnull String displayName, @Nullable Supplier<BlockCipherPadding> factory) {
		this.displayName = displayName;
		this.factory = factory;
	}

	/**
	 * @return New padding instance, or {@code null} for {@link #NONE}.
	 */
	@Nullable
	public BlockCipherPadding newPadding() {
		return factory == null ? null : factory.get();
	}

	@Override
	public String toString() {
		return displayName;
	}
}
