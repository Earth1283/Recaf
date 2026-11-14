package software.coley.recaf.ui.pane.crypto;

import jakarta.annotation.Nonnull;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.crypto.ByteFormat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Ways to display output bytes.
 */
public enum OutputFormat {
	UTF8("crypto.output.utf8"),
	LATIN1("crypto.output.latin1"),
	HEX("crypto.output.hex"),
	HEX_DUMP("crypto.output.hex-dump"),
	BASE64("crypto.output.base64"),
	BYTE_ARRAY("crypto.output.byte-array"),
	JAVA_STRING("crypto.output.java-string");

	/** Displaying more than this many bytes makes the text area sluggish; saving to a file writes everything. */
	public static final int MAX_DISPLAY_BYTES = 512 * 1024;
	private final String langKey;

	OutputFormat(@Nonnull String langKey) {
		this.langKey = langKey;
	}

	/**
	 * @param data
	 * 		Bytes to display.
	 *
	 * @return Display text, cut to {@link #MAX_DISPLAY_BYTES} of input.
	 */
	@Nonnull
	public String format(@Nonnull byte[] data) {
		byte[] shown = data.length > MAX_DISPLAY_BYTES ? Arrays.copyOf(data, MAX_DISPLAY_BYTES) : data;
		return switch (this) {
			case UTF8 -> ByteFormat.UTF8.format(shown);
			case LATIN1 -> ByteFormat.LATIN1.format(shown);
			case HEX -> ByteFormat.HEX.format(shown);
			case HEX_DUMP -> hexDump(shown);
			case BASE64 -> ByteFormat.BASE64.format(shown);
			case BYTE_ARRAY -> ByteFormat.BYTE_ARRAY.format(shown);
			case JAVA_STRING -> javaString(shown);
		};
	}

	@Nonnull
	private static String hexDump(@Nonnull byte[] data) {
		StringBuilder sb = new StringBuilder(data.length * 4 + 16);
		for (int offset = 0; offset < data.length; offset += 16) {
			sb.append(String.format("%08x  ", offset));
			for (int i = 0; i < 16; i++) {
				if (offset + i < data.length)
					sb.append(String.format("%02x ", data[offset + i] & 0xFF));
				else
					sb.append("   ");
				if (i == 7)
					sb.append(' ');
			}
			sb.append(" |");
			for (int i = 0; i < 16 && offset + i < data.length; i++) {
				int c = data[offset + i] & 0xFF;
				sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
			}
			sb.append("|\n");
		}
		return sb.toString();
	}

	@Nonnull
	private static String javaString(@Nonnull byte[] data) {
		String text = new String(data, StandardCharsets.UTF_8);
		StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				default -> {
					if (c < 0x20 || c >= 0x7F)
						sb.append(String.format("\\u%04x", (int) c));
					else
						sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	@Override
	public String toString() {
		return Lang.get(langKey);
	}
}
