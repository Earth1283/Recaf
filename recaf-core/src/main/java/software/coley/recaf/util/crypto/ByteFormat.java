package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ways to write bytes as text, used for tool inputs <i>(data, keys, IVs, salts)</i> and outputs.
 */
public enum ByteFormat {
	/** Text encoded as UTF-8. */
	UTF8("UTF-8 text"),
	/** Text where each char is one byte, which round-trips binary data held in Java strings. */
	LATIN1("Latin-1 text"),
	/** Hexadecimal, tolerating whitespace, {@code 0x}, {@code \x}, {@code :} and {@code ,} separators. */
	HEX("Hex"),
	/** Base64, tolerating whitespace, missing padding and the URL-safe alphabet. */
	BASE64("Base64"),
	/** A Java array initializer such as {@code {12, -3, 0x7F}}, as decompilers write {@code byte[]} literals. */
	BYTE_ARRAY("Java byte[]");

	private static final Pattern ARRAY_ELEMENT = Pattern.compile("[-+]?(0x[0-9a-fA-F]+|\\d+)");
	private final String displayName;

	ByteFormat(@Nonnull String displayName) {
		this.displayName = displayName;
	}

	/**
	 * @return Display name of the format.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @param text
	 * 		Text in this format.
	 *
	 * @return Bytes represented by the text.
	 *
	 * @throws CryptoException
	 * 		When the text is not valid for this format.
	 */
	@Nonnull
	public byte[] parse(@Nonnull String text) throws CryptoException {
		return switch (this) {
			case UTF8 -> text.getBytes(StandardCharsets.UTF_8);
			case LATIN1 -> toLatin1(text);
			case HEX -> parseHex(text);
			case BASE64 -> parseBase64(text);
			case BYTE_ARRAY -> parseByteArray(text);
		};
	}

	/**
	 * @param data
	 * 		Bytes to write.
	 *
	 * @return Text in this format.
	 */
	@Nonnull
	public String format(@Nonnull byte[] data) {
		return switch (this) {
			case UTF8 -> new String(data, StandardCharsets.UTF_8);
			case LATIN1 -> new String(data, StandardCharsets.ISO_8859_1);
			case HEX -> HexFormat.of().formatHex(data);
			case BASE64 -> Base64.getEncoder().encodeToString(data);
			case BYTE_ARRAY -> formatByteArray(data);
		};
	}

	@Override
	public String toString() {
		return displayName;
	}

	/**
	 * Encodes text as Latin-1 without the replacement {@link String#getBytes} does for chars above {@code 0xFF},
	 * keeping their low byte instead. That matches the common {@code (byte) c} cast in string decryption routines.
	 *
	 * @param text
	 * 		Text to convert.
	 *
	 * @return One byte per char.
	 */
	@Nonnull
	public static byte[] toLatin1(@Nonnull String text) {
		byte[] out = new byte[text.length()];
		for (int i = 0; i < out.length; i++)
			out[i] = (byte) text.charAt(i);
		return out;
	}

	/**
	 * @param text
	 * 		Hex text.
	 *
	 * @return Decoded bytes.
	 *
	 * @throws CryptoException
	 * 		When the text has a non-hex digit or an odd digit count.
	 */
	@Nonnull
	public static byte[] parseHex(@Nonnull String text) throws CryptoException {
		String cleaned = text.replaceAll("(?i)0x|\\\\x|[\\s:,;{}\\[\\]-]", "");
		if (cleaned.length() % 2 != 0)
			throw new CryptoException("Hex input has an odd number of digits (" + cleaned.length() + ")");
		try {
			return HexFormat.of().parseHex(cleaned);
		} catch (IllegalArgumentException ex) {
			throw new CryptoException("Input is not valid hex", ex);
		}
	}

	/**
	 * @param text
	 * 		Base64 text in either alphabet.
	 *
	 * @return Decoded bytes.
	 *
	 * @throws CryptoException
	 * 		When the text is not Base64.
	 */
	@Nonnull
	public static byte[] parseBase64(@Nonnull String text) throws CryptoException {
		String cleaned = text.replaceAll("\\s", "");
		cleaned = cleaned.replace('-', '+').replace('_', '/');
		int end = cleaned.length();
		while (end > 0 && cleaned.charAt(end - 1) == '=')
			end--;
		cleaned = cleaned.substring(0, end);
		if (cleaned.length() % 4 == 1)
			throw new CryptoException("Base64 input has an invalid length");
		try {
			return Base64.getDecoder().decode(cleaned + "==".substring(0, (4 - cleaned.length() % 4) % 4));
		} catch (IllegalArgumentException ex) {
			throw new CryptoException("Input is not valid Base64", ex);
		}
	}

	@Nonnull
	private static byte[] parseByteArray(@Nonnull String text) throws CryptoException {
		String body = text;
		int open = body.indexOf('{');
		int close = body.lastIndexOf('}');
		if (open >= 0 && close > open)
			body = body.substring(open + 1, close);
		body = body.replaceAll("\\(byte\\)\\s*", "");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Matcher matcher = ARRAY_ELEMENT.matcher(body);
		int last = 0;
		while (matcher.find()) {
			String gap = body.substring(last, matcher.start());
			if (!gap.isBlank() && !gap.trim().equals(","))
				throw new CryptoException("Unexpected '" + gap.trim() + "' in byte array");
			String token = matcher.group();
			boolean negative = token.startsWith("-");
			String digits = token.replaceFirst("^[-+]", "");
			long value = digits.toLowerCase().startsWith("0x") ?
					Long.parseLong(digits.substring(2), 16) : Long.parseLong(digits);
			if (negative)
				value = -value;
			if (value < Byte.MIN_VALUE || value > 0xFF)
				throw new CryptoException("Byte array value out of range: " + token);
			out.write((int) value);
			last = matcher.end();
		}
		if (!body.substring(last).isBlank())
			throw new CryptoException("Unexpected '" + body.substring(last).trim() + "' in byte array");
		return out.toByteArray();
	}

	@Nonnull
	private static String formatByteArray(@Nonnull byte[] data) {
		StringBuilder sb = new StringBuilder(data.length * 5 + 2).append('{');
		for (int i = 0; i < data.length; i++) {
			if (i > 0)
				sb.append(", ");
			sb.append(data[i]);
		}
		return sb.append('}').toString();
	}
}
