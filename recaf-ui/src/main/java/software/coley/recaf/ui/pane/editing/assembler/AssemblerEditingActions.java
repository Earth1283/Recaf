package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, testable text editing utilities for JASM assembly editors.
 */
public final class AssemblerEditingActions {
	private AssemblerEditingActions() {}

	/**
	 * Result of a pure text transformation, including updated selection bounds.
	 */
	public record TextEdit(@Nonnull String text, int anchor, int caret) {}

	/**
	 * Apply a {@link TextEdit} to {@code area} as an isolated undo step.
	 */
	public static void apply(@Nonnull CodeArea area, @Nullable TextEdit edit) {
		if (edit == null || edit.text().equals(area.getText()))
			return;
		area.getUndoManager().preventMerge();
		area.replaceText(edit.text());
		int max = area.getLength();
		int anchor = Math.clamp(edit.anchor(), 0, max);
		int caret = Math.clamp(edit.caret(), 0, max);
		area.selectRange(anchor, caret);
		area.getUndoManager().preventMerge();
	}

	/**
	 * Insert text at the current caret line, preserving indentation.
	 */
	public static void insertSnippet(@Nonnull CodeArea area, @Nonnull String rawAssembly) {
		String current = area.getText();
		int caret = area.getCaretPosition();
		String prepared = prepareSnippetForInsertion(current, caret, rawAssembly);
		if (prepared.isEmpty())
			return;

		int lineStart = lineStart(current, caret);
		int lineEnd = lineEnd(current, caret);
		String currentLine = current.substring(lineStart, lineEnd);
		int insertPos;
		String toInsert;
		if (currentLine.isBlank()) {
			insertPos = lineStart;
			toInsert = prepared;
		} else {
			insertPos = lineEnd;
			toInsert = "\n" + prepared;
		}
		area.getUndoManager().preventMerge();
		area.replaceText(insertPos, currentLine.isBlank() ? lineEnd : insertPos, toInsert);
		int end = insertPos + toInsert.length();
		area.selectRange(end, end);
		area.getUndoManager().preventMerge();
		area.showParagraphAtCenter(area.offsetToPosition(end, TwoDimensional.Bias.Forward).getMajor());
	}

	/**
	 * Toggle {@code // } line comments across every line touched by {@code [start, end]}.
	 */
	@Nonnull
	public static TextEdit toggleLineComment(@Nonnull String text, int start, int end) {
		int selStart = Math.min(start, end);
		int selEnd = Math.max(start, end);
		int blockStart = lineStart(text, selStart);
		int blockEnd = effectiveLineEnd(text, selStart, selEnd);

		List<String> lines = splitLines(text.substring(blockStart, blockEnd));
		boolean hasNonBlank = false;
		boolean allCommented = true;
		int minIndent = Integer.MAX_VALUE;
		for (String line : lines) {
			if (line.isBlank())
				continue;
			hasNonBlank = true;
			int ws = leadingWhitespaceCount(line);
			minIndent = Math.min(minIndent, ws);
			if (!line.startsWith("//", ws))
				allCommented = false;
		}
		if (!hasNonBlank)
			return new TextEdit(text, start, end);

		StringBuilder replacement = new StringBuilder();
		int firstLineDelta = 0;
		int totalDelta = 0;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String updated;
			if (line.isBlank()) {
				updated = line;
			} else if (allCommented) {
				int ws = leadingWhitespaceCount(line);
				int removeLen = line.startsWith("// ", ws) ? 3 : 2;
				updated = line.substring(0, ws) + line.substring(ws + removeLen);
			} else {
				int col = Math.min(minIndent, line.length());
				updated = line.substring(0, col) + "// " + line.substring(col);
			}
			int delta = updated.length() - line.length();
			if (i == 0)
				firstLineDelta = delta;
			totalDelta += delta;
			if (i > 0)
				replacement.append('\n');
			replacement.append(updated);
		}

		String newText = text.substring(0, blockStart) + replacement + text.substring(blockEnd);
		if (start == end) {
			int newCaret = Math.max(blockStart, Math.min(newText.length(), start + firstLineDelta));
			return new TextEdit(newText, newCaret, newCaret);
		}
		int newAnchor = Math.max(blockStart, start + (start > blockStart ? firstLineDelta : 0));
		int newCaret = Math.max(blockStart, end + totalDelta);
		return new TextEdit(newText, newAnchor, newCaret);
	}

	/**
	 * Duplicate the active selection inline, or duplicate the current line onto a new line below.
	 */
	@Nonnull
	public static TextEdit duplicateSelectionOrLine(@Nonnull String text, int start, int end) {
		if (start != end) {
			int s = Math.min(start, end);
			int e = Math.max(start, end);
			String selected = text.substring(s, e);
			String newText = text.substring(0, e) + selected + text.substring(e);
			return new TextEdit(newText, e, e + selected.length());
		}
		int lStart = lineStart(text, start);
		int lEnd = lineEnd(text, start);
		String line = text.substring(lStart, lEnd);
		String newText = text.substring(0, lEnd) + "\n" + line + text.substring(lEnd);
		int col = start - lStart;
		int newCaret = lEnd + 1 + Math.min(col, line.length());
		return new TextEdit(newText, newCaret, newCaret);
	}

	/**
	 * Move the line(s) covered by {@code [start, end]} up or down by one line.
	 */
	@Nonnull
	public static TextEdit moveLines(@Nonnull String text, int start, int end, boolean up) {
		int selStart = Math.min(start, end);
		int selEnd = Math.max(start, end);
		int blockStart = lineStart(text, selStart);
		int blockEnd = effectiveLineEnd(text, selStart, selEnd);
		String block = text.substring(blockStart, blockEnd);

		if (up) {
			if (blockStart == 0)
				return new TextEdit(text, start, end);
			int prevLineEnd = blockStart - 1;
			int prevLineStart = lineStart(text, prevLineEnd);
			String prevLine = text.substring(prevLineStart, prevLineEnd);
			String newText = text.substring(0, prevLineStart) + block + "\n" + prevLine + text.substring(blockEnd);
			int shift = -(prevLine.length() + 1);
			return new TextEdit(newText, start + shift, end + shift);
		} else {
			if (blockEnd >= text.length())
				return new TextEdit(text, start, end);
			int nextLineStart = blockEnd + 1;
			int nextLineEnd = lineEnd(text, nextLineStart);
			String nextLine = text.substring(nextLineStart, nextLineEnd);
			String newText = text.substring(0, blockStart) + nextLine + "\n" + block + text.substring(nextLineEnd);
			int shift = nextLine.length() + 1;
			return new TextEdit(newText, start + shift, end + shift);
		}
	}

	/**
	 * Replace instruction lines in {@code [start, end]} with {@code nop}, skipping labels, directives, and comments.
	 */
	@Nonnull
	public static TextEdit replaceInstructionsWithNop(@Nonnull String text, int start, int end) {
		int selStart = Math.min(start, end);
		int selEnd = Math.max(start, end);
		int blockStart = lineStart(text, selStart);
		int blockEnd = effectiveLineEnd(text, selStart, selEnd);

		List<String> lines = splitLines(text.substring(blockStart, blockEnd));
		StringBuilder replacement = new StringBuilder();
		boolean changed = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String updated = replaceLineIfInstruction(line);
			if (!updated.equals(line))
				changed = true;
			if (i > 0)
				replacement.append('\n');
			replacement.append(updated);
		}
		if (!changed)
			return new TextEdit(text, start, end);

		String newText = text.substring(0, blockStart) + replacement + text.substring(blockEnd);
		int newEnd = blockStart + replacement.length();
		if (start == end)
			return new TextEdit(newText, newEnd, newEnd);
		return new TextEdit(newText, blockStart, newEnd);
	}

	/**
	 * Strip outer {@code .method ... { code: { ... } }} boilerplate emitted by {@code ExpressionCompiler}
	 * and indent the instructions to match the target caret line.
	 */
	@Nonnull
	public static String prepareSnippetForInsertion(@Nonnull String targetText, int caretOffset, @Nonnull String rawAssembly) {
		String body = extractBodyIfWrappedMethod(rawAssembly);
		List<String> lines = splitLines(body.stripTrailing());
		while (!lines.isEmpty() && lines.getFirst().isBlank())
			lines.removeFirst();
		while (!lines.isEmpty() && lines.getLast().isBlank())
			lines.removeLast();
		if (lines.isEmpty())
			return "";

		int commonIndent = Integer.MAX_VALUE;
		for (String line : lines) {
			if (!line.isBlank())
				commonIndent = Math.min(commonIndent, leadingWhitespaceCount(line));
		}
		if (commonIndent == Integer.MAX_VALUE)
			commonIndent = 0;

		String targetIndent = detectTargetIndent(targetText, caretOffset);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0)
				sb.append('\n');
			String line = lines.get(i);
			if (!line.isBlank()) {
				sb.append(targetIndent).append(line.substring(Math.min(commonIndent, line.length())));
			}
		}
		return sb.toString();
	}

	@Nonnull
	private static String extractBodyIfWrappedMethod(@Nonnull String raw) {
		String trimmed = raw.trim();
		if (!trimmed.startsWith(".method "))
			return raw;
		int codeMarker = trimmed.indexOf("code:");
		if (codeMarker < 0)
			return raw;
		int openBrace = trimmed.indexOf('{', codeMarker);
		int closeBrace = trimmed.lastIndexOf('}');
		if (openBrace < 0 || closeBrace <= openBrace)
			return raw;
		// Innermost block inside code: { ... }
		int innerClose = trimmed.lastIndexOf('}', closeBrace - 1);
		if (innerClose > openBrace)
			return trimmed.substring(openBrace + 1, innerClose);
		return trimmed.substring(openBrace + 1, closeBrace);
	}

	@Nonnull
	private static String detectTargetIndent(@Nonnull String targetText, int caretOffset) {
		int clamped = Math.clamp(caretOffset, 0, targetText.length());
		int lStart = lineStart(targetText, clamped);
		int lEnd = lineEnd(targetText, clamped);
		String currentLine = targetText.substring(lStart, lEnd);
		int ws = leadingWhitespaceCount(currentLine);
		if (ws > 0)
			return currentLine.substring(0, ws);
		// Look backward for nearest non-blank line indentation
		int pos = lStart - 1;
		while (pos > 0) {
			int prevStart = lineStart(targetText, pos);
			String prev = targetText.substring(prevStart, pos);
			if (!prev.isBlank()) {
				int prevWs = leadingWhitespaceCount(prev);
				String indent = prev.substring(0, prevWs);
				return prev.trim().endsWith(":") || prev.trim().endsWith("{") ? indent + "    " : indent;
			}
			pos = prevStart - 1;
		}
		return "";
	}

	@Nonnull
	private static String replaceLineIfInstruction(@Nonnull String line) {
		int ws = leadingWhitespaceCount(line);
		if (ws >= line.length())
			return line;
		int commentIdx = findLineCommentStart(line, ws);
		String codePart = (commentIdx >= 0 ? line.substring(ws, commentIdx) : line.substring(ws)).trim();
		if (codePart.isEmpty()
				|| codePart.startsWith(".")
				|| codePart.equals("{")
				|| codePart.equals("}")
				|| codePart.endsWith(":")
				|| codePart.endsWith("{")) {
			return line;
		}
		String trailingComment = commentIdx >= 0 ? " " + line.substring(commentIdx) : "";
		return line.substring(0, ws) + "nop" + trailingComment;
	}

	private static int findLineCommentStart(@Nonnull String line, int from) {
		boolean inQuotes = false;
		for (int i = from; i < line.length() - 1; i++) {
			char c = line.charAt(i);
			if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
				inQuotes = !inQuotes;
			} else if (!inQuotes && c == '/' && line.charAt(i + 1) == '/') {
				return i;
			}
		}
		return -1;
	}

	private static int lineStart(@Nonnull String text, int offset) {
		int clamped = Math.clamp(offset, 0, text.length());
		int nl = text.lastIndexOf('\n', Math.max(0, clamped - 1));
		if (clamped > 0 && clamped <= text.length() && text.charAt(clamped - 1) == '\n')
			return clamped;
		return nl < 0 ? 0 : nl + 1;
	}

	private static int lineEnd(@Nonnull String text, int offset) {
		int clamped = Math.clamp(offset, 0, text.length());
		int nl = text.indexOf('\n', clamped);
		return nl < 0 ? text.length() : nl;
	}

	private static int effectiveLineEnd(@Nonnull String text, int selStart, int selEnd) {
		if (selEnd > selStart && selEnd > 0 && selEnd <= text.length() && text.charAt(selEnd - 1) == '\n')
			return selEnd - 1;
		return lineEnd(text, selEnd);
	}

	private static int leadingWhitespaceCount(@Nonnull String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'))
			i++;
		return i;
	}

	@Nonnull
	private static List<String> splitLines(@Nonnull String text) {
		List<String> list = new ArrayList<>();
		int start = 0;
		int nl;
		while ((nl = text.indexOf('\n', start)) >= 0) {
			list.add(text.substring(start, nl));
			start = nl + 1;
		}
		list.add(text.substring(start));
		return list;
	}
}
