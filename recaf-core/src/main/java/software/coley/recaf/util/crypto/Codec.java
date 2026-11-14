package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.Deflater;

/**
 * Reversible, keyless transformations: text encodings, compression and simple substitutions.
 */
public enum Codec {
	BASE64("Base64"),
	BASE64_URL("Base64 (URL-safe, no padding)"),
	BASE64_MIME("Base64 (MIME, 76 per line)"),
	BASE32("Base32"),
	BASE32_HEX("Base32 (extended hex)"),
	BASE58("Base58"),
	ASCII85("Ascii85"),
	HEX("Hex"),
	URL("URL percent-encoding"),
	JAVA_ESCAPE("Java string escapes"),
	ROT13("ROT13"),
	ROT47("ROT47"),
	REVERSE("Reverse bytes"),
	GZIP("GZip"),
	ZLIB("Zlib"),
	DEFLATE("Raw deflate");

	/** Upper bound on decompressed output, guarding against decompression bombs in untrusted data. */
	public static final int MAX_INFLATED_SIZE = 64 * 1024 * 1024;
	private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final String BASE32_HEX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
	private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	private final String displayName;

	Codec(@Nonnull String displayName) {
		this.displayName = displayName;
	}

	/**
	 * @return Display name of the codec.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @return {@code true} when encoding and decoding are the same operation.
	 */
	public boolean isSymmetric() {
		return this == ROT13 || this == ROT47 || this == REVERSE;
	}

	/**
	 * @return {@code true} for compression formats, whose names read better as compress/decompress.
	 */
	public boolean isCompression() {
		return this == GZIP || this == ZLIB || this == DEFLATE;
	}

	@Override
	public String toString() {
		return displayName;
	}

	/**
	 * @param data
	 * 		Input bytes.
	 *
	 * @return Encoded <i>(or compressed)</i> output.
	 *
	 * @throws CryptoException
	 * 		When encoding fails.
	 */
	@Nonnull
	public byte[] encode(@Nonnull byte[] data) throws CryptoException {
		return switch (this) {
			case BASE64 -> Base64.getEncoder().encode(data);
			case BASE64_URL -> Base64.getUrlEncoder().withoutPadding().encode(data);
			case BASE64_MIME -> Base64.getMimeEncoder().encode(data);
			case BASE32 -> ascii(base32Encode(data, BASE32_ALPHABET));
			case BASE32_HEX -> ascii(base32Encode(data, BASE32_HEX_ALPHABET));
			case BASE58 -> ascii(base58Encode(data));
			case ASCII85 -> ascii(ascii85Encode(data));
			case HEX -> ascii(HexFormat.of().formatHex(data));
			case URL -> ascii(urlEncode(data));
			case JAVA_ESCAPE -> ascii(javaEscape(new String(data, StandardCharsets.UTF_8)));
			case ROT13 -> rot13(data);
			case ROT47 -> rot47(data);
			case REVERSE -> reverse(data);
			case GZIP -> compress(data, Format.GZIP);
			case ZLIB -> compress(data, Format.ZLIB);
			case DEFLATE -> compress(data, Format.RAW);
		};
	}

	/**
	 * @param data
	 * 		Encoded <i>(or compressed)</i> input.
	 *
	 * @return Decoded output.
	 *
	 * @throws CryptoException
	 * 		When the input is not valid for this codec.
	 */
	@Nonnull
	public byte[] decode(@Nonnull byte[] data) throws CryptoException {
		return switch (this) {
			case BASE64, BASE64_URL, BASE64_MIME -> ByteFormat.parseBase64(text(data));
			case BASE32 -> base32Decode(text(data), BASE32_ALPHABET);
			case BASE32_HEX -> base32Decode(text(data), BASE32_HEX_ALPHABET);
			case BASE58 -> base58Decode(text(data).trim());
			case ASCII85 -> ascii85Decode(text(data));
			case HEX -> ByteFormat.parseHex(text(data));
			case URL -> urlDecode(data);
			case JAVA_ESCAPE -> javaUnescape(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
			case ROT13 -> rot13(data);
			case ROT47 -> rot47(data);
			case REVERSE -> reverse(data);
			case GZIP -> inflate(data, Format.GZIP);
			case ZLIB -> inflate(data, Format.ZLIB);
			case DEFLATE -> inflate(data, Format.RAW);
		};
	}

	@Nonnull
	private static byte[] ascii(@Nonnull String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	@Nonnull
	private static String text(@Nonnull byte[] data) {
		return new String(data, StandardCharsets.ISO_8859_1);
	}

	@Nonnull
	private static String base32Encode(@Nonnull byte[] data, @Nonnull String alphabet) {
		StringBuilder sb = new StringBuilder((data.length + 4) / 5 * 8);
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				sb.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0)
			sb.append(alphabet.charAt((buffer << (5 - bits)) & 31));
		while (sb.length() % 8 != 0)
			sb.append('=');
		return sb.toString();
	}

	@Nonnull
	private static byte[] base32Decode(@Nonnull String text, @Nonnull String alphabet) throws CryptoException {
		String cleaned = text.replaceAll("[\\s=]", "").toUpperCase();
		ByteArrayOutputStream out = new ByteArrayOutputStream(cleaned.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (int i = 0; i < cleaned.length(); i++) {
			int value = alphabet.indexOf(cleaned.charAt(i));
			if (value < 0)
				throw new CryptoException("Invalid Base32 character '" + cleaned.charAt(i) + "'");
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	@Nonnull
	private static String base58Encode(@Nonnull byte[] data) {
		int zeros = 0;
		while (zeros < data.length && data[zeros] == 0)
			zeros++;
		StringBuilder sb = new StringBuilder();
		BigInteger value = new BigInteger(1, data);
		BigInteger base = BigInteger.valueOf(58);
		while (value.signum() > 0) {
			BigInteger[] divRem = value.divideAndRemainder(base);
			sb.append(BASE58_ALPHABET.charAt(divRem[1].intValue()));
			value = divRem[0];
		}
		sb.append("1".repeat(zeros));
		return sb.reverse().toString();
	}

	@Nonnull
	private static byte[] base58Decode(@Nonnull String text) throws CryptoException {
		BigInteger value = BigInteger.ZERO;
		BigInteger base = BigInteger.valueOf(58);
		int zeros = 0;
		while (zeros < text.length() && text.charAt(zeros) == '1')
			zeros++;
		for (int i = 0; i < text.length(); i++) {
			int digit = BASE58_ALPHABET.indexOf(text.charAt(i));
			if (digit < 0)
				throw new CryptoException("Invalid Base58 character '" + text.charAt(i) + "'");
			value = value.multiply(base).add(BigInteger.valueOf(digit));
		}
		byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
		if (magnitude.length > 1 && magnitude[0] == 0)
			magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
		byte[] out = new byte[zeros + magnitude.length];
		System.arraycopy(magnitude, 0, out, zeros, magnitude.length);
		return out;
	}

	@Nonnull
	private static String ascii85Encode(@Nonnull byte[] data) {
		StringBuilder sb = new StringBuilder("<~");
		for (int i = 0; i < data.length; i += 4) {
			int chunk = Math.min(4, data.length - i);
			long value = 0;
			for (int j = 0; j < 4; j++)
				value = (value << 8) | (j < chunk ? data[i + j] & 0xFF : 0);
			if (value == 0 && chunk == 4) {
				sb.append('z');
				continue;
			}
			char[] digits = new char[5];
			for (int j = 4; j >= 0; j--) {
				digits[j] = (char) ('!' + value % 85);
				value /= 85;
			}
			sb.append(digits, 0, chunk + 1);
		}
		return sb.append("~>").toString();
	}

	@Nonnull
	private static byte[] ascii85Decode(@Nonnull String text) throws CryptoException {
		String body = text.replaceAll("\\s", "");
		if (body.startsWith("<~"))
			body = body.substring(2);
		if (body.endsWith("~>"))
			body = body.substring(0, body.length() - 2);
		ByteArrayOutputStream out = new ByteArrayOutputStream(body.length() * 4 / 5);
		long value = 0;
		int count = 0;
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == 'z' && count == 0) {
				out.writeBytes(new byte[4]);
				continue;
			}
			if (c < '!' || c > 'u')
				throw new CryptoException("Invalid Ascii85 character '" + c + "'");
			value = value * 85 + (c - '!');
			if (++count == 5) {
				if (value > 0xFFFFFFFFL)
					throw new CryptoException("Ascii85 group out of range");
				for (int j = 3; j >= 0; j--)
					out.write((int) (value >> (j * 8)) & 0xFF);
				value = 0;
				count = 0;
			}
		}
		if (count == 1)
			throw new CryptoException("Ascii85 input ends with a single character");
		if (count > 0) {
			for (int j = count; j < 5; j++)
				value = value * 85 + 84;
			for (int j = 0; j < count - 1; j++)
				out.write((int) (value >> ((3 - j) * 8)) & 0xFF);
		}
		return out.toByteArray();
	}

	@Nonnull
	private static String urlEncode(@Nonnull byte[] data) {
		StringBuilder sb = new StringBuilder(data.length * 3);
		for (byte b : data) {
			int c = b & 0xFF;
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-_.~".indexOf(c) >= 0)
				sb.append((char) c);
			else
				sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
		}
		return sb.toString();
	}

	@Nonnull
	private static byte[] urlDecode(@Nonnull byte[] data) throws CryptoException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		for (int i = 0; i < data.length; i++) {
			int c = data[i] & 0xFF;
			if (c == '+') {
				out.write(' ');
			} else if (c == '%') {
				if (i + 2 >= data.length)
					throw new CryptoException("Truncated percent escape at offset " + i);
				int hi = Character.digit(data[i + 1], 16);
				int lo = Character.digit(data[i + 2], 16);
				if (hi < 0 || lo < 0)
					throw new CryptoException("Invalid percent escape at offset " + i);
				out.write((hi << 4) | lo);
				i += 2;
			} else {
				out.write(c);
			}
		}
		return out.toByteArray();
	}

	@Nonnull
	private static String javaEscape(@Nonnull String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				default -> {
					if (c < 0x20 || c > 0x7E)
						sb.append(String.format("\\u%04x", (int) c));
					else
						sb.append(c);
				}
			}
		}
		return sb.toString();
	}

	@Nonnull
	private static String javaUnescape(@Nonnull String text) throws CryptoException {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '\\' || i + 1 >= text.length()) {
				sb.append(c);
				continue;
			}
			char next = text.charAt(++i);
			switch (next) {
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 's' -> sb.append(' ');
				case '0', '1', '2', '3', '4', '5', '6', '7' -> {
					int end = i;
					int max = next <= '3' ? 3 : 2;
					while (end < text.length() && end - i < max && text.charAt(end) >= '0' && text.charAt(end) <= '7')
						end++;
					sb.append((char) Integer.parseInt(text.substring(i, end), 8));
					i = end - 1;
				}
				case 'x' -> {
					if (i + 2 >= text.length())
						throw new CryptoException("Truncated \\x escape");
					sb.append((char) parseHexDigits(text.substring(i + 1, i + 3)));
					i += 2;
				}
				case 'u' -> {
					while (i + 1 < text.length() && text.charAt(i + 1) == 'u')
						i++;
					if (i + 4 >= text.length())
						throw new CryptoException("Truncated \\u escape");
					sb.append((char) parseHexDigits(text.substring(i + 1, i + 5)));
					i += 4;
				}
				default -> sb.append(next);
			}
		}
		return sb.toString();
	}

	private static int parseHexDigits(@Nonnull String digits) throws CryptoException {
		try {
			return Integer.parseInt(digits, 16);
		} catch (NumberFormatException ex) {
			throw new CryptoException("Invalid hex escape '" + digits + "'", ex);
		}
	}

	@Nonnull
	private static byte[] rot13(@Nonnull byte[] data) {
		byte[] out = data.clone();
		for (int i = 0; i < out.length; i++) {
			int c = out[i];
			if (c >= 'a' && c <= 'z')
				out[i] = (byte) ('a' + (c - 'a' + 13) % 26);
			else if (c >= 'A' && c <= 'Z')
				out[i] = (byte) ('A' + (c - 'A' + 13) % 26);
		}
		return out;
	}

	@Nonnull
	private static byte[] rot47(@Nonnull byte[] data) {
		byte[] out = data.clone();
		for (int i = 0; i < out.length; i++) {
			int c = out[i];
			if (c >= '!' && c <= '~')
				out[i] = (byte) ('!' + (c - '!' + 47) % 94);
		}
		return out;
	}

	@Nonnull
	private static byte[] reverse(@Nonnull byte[] data) {
		byte[] out = new byte[data.length];
		for (int i = 0; i < data.length; i++)
			out[i] = data[data.length - 1 - i];
		return out;
	}

	private enum Format {GZIP, ZLIB, RAW}

	@Nonnull
	private static byte[] compress(@Nonnull byte[] data, @Nonnull Format format) throws CryptoException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, format == Format.RAW);
		try (var out = format == Format.GZIP ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes, deflater)) {
			out.write(data);
		} catch (IOException ex) {
			throw new CryptoException("Compression failed", ex);
		} finally {
			deflater.end();
		}
		return bytes.toByteArray();
	}

	@Nonnull
	private static byte[] inflate(@Nonnull byte[] data, @Nonnull Format format) throws CryptoException {
		Inflater inflater = new Inflater(format == Format.RAW);
		try (InputStream in = format == Format.GZIP ?
				new GZIPInputStream(new ByteArrayInputStream(data)) :
				new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				if (out.size() > MAX_INFLATED_SIZE)
					throw new CryptoException("Decompressed output exceeds " + (MAX_INFLATED_SIZE >> 20) + " MiB");
			}
			if (format != Format.GZIP && !inflater.finished())
				throw new CryptoException("Compressed stream is truncated");
			return out.toByteArray();
		} catch (IOException ex) {
			throw new CryptoException("Input is not valid " + format.name().toLowerCase() + " data", ex);
		} finally {
			inflater.end();
		}
	}
}
