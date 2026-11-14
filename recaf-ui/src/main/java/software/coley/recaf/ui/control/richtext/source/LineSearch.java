package software.coley.recaf.ui.control.richtext.source;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Finds the line of some text inside a range of source, for narrowing a selection of a whole member to one line.
 */
public final class LineSearch {
	private LineSearch() {}

	/**
	 * @param text
	 * 		Source text.
	 * @param begin
	 * 		Start of the range to search, inclusive.
	 * @param end
	 * 		End of the range to search, exclusive.
	 * @param needles
	 * 		Candidate texts in order of preference.
	 *
	 * @return The line of the first needle that occurs in the range, without its indentation and line break.
	 * {@code null} when no needle occurs or the range does not fit the text.
	 */
	@Nullable
	public static Span find(@Nonnull String text, int begin, int end, @Nonnull List<String> needles) {
		if (begin < 0 || end > text.length() || begin >= end)
			return null;
		for (String needle : needles) {
			if (needle.isEmpty())
				continue;
			int index = text.indexOf(needle, begin);
			if (index < 0 || index + needle.length() > end)
				continue;
			int lineStart = text.lastIndexOf('\n', index) + 1;
			int lineEnd = text.indexOf('\n', index);
			if (lineEnd < 0)
				lineEnd = text.length();
			while (lineStart < lineEnd && Character.isWhitespace(text.charAt(lineStart)))
				lineStart++;
			while (lineEnd > lineStart && Character.isWhitespace(text.charAt(lineEnd - 1)))
				lineEnd--;
			return new Span(lineStart, lineEnd);
		}
		return null;
	}

	/**
	 * @param start
	 * 		Offset of the first character.
	 * @param end
	 * 		Offset after the last character.
	 */
	public record Span(int start, int end) {}
}
