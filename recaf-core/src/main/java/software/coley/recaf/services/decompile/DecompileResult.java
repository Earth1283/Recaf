package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.util.StringUtil;

import java.util.List;
import java.util.Objects;

/**
 * Result for a {@link Decompiler} output.
 *
 * @author Matt Coley
 */
public class DecompileResult {
	private final String text;
	private final Throwable exception;
	private final ResultType type;
	private final int configHash;
	private final List<DecompileDiagnostic> diagnostics;

	/**
	 * Constructor for a successful decompilation.
	 *
	 * @param text
	 * 		Decompiled text.
	 * @param configHash
	 * 		Value of {@link DecompilerConfig#getHash()} of associated decompiler.
	 * 		Used to determine if cached value in {@link CachedDecompileProperty} is up-to-date with current config.
	 */
	public DecompileResult(@Nonnull String text, int configHash) {
		this(text, null, ResultType.SUCCESS, configHash, List.of());
	}

	/**
	 * Constructor for a failed decompilation.
	 *
	 * @param exception
	 * 		Failure reason.
	 * @param configHash
	 * 		Value of {@link DecompilerConfig#getHash()} of associated decompiler.
	 * 		Used to determine if cached value in {@link CachedDecompileProperty} is up-to-date with current config.
	 */
	public DecompileResult(@Nonnull Throwable exception, int configHash) {
		this("// " + StringUtil.traceToString(exception).replace("\n", "\n// "), exception, ResultType.FAILURE, configHash, List.of());
	}

	/**
	 * Constructor for a skipped decompilation.
	 *
	 * @param configHash
	 * 		Value of {@link DecompilerConfig#getHash()} of associated decompiler.
	 * 		Used to determine if cached value in {@link CachedDecompileProperty} is up-to-date with current config.
	 */
	public DecompileResult(int configHash) {
		this(null, null, ResultType.SKIPPED, configHash, List.of());
	}

	/**
	 * Constructor for a skipped decompilation, with pre-defined text.
	 * Typically used for displaying feedback if the decompiler had an issue or timed out.
	 *
	 * @param text
	 * 		Decompiled text.
	 */
	public DecompileResult(@Nonnull String text) {
		this(text, null, ResultType.SKIPPED, 0, List.of());
	}

	/**
	 * Private constructor for wither operations.
	 *
	 * @param text
	 * 		Decompiled text.
	 * @param exception
	 * 		Failure reason.
	 * @param type
	 * 		Result type.
	 * @param configHash
	 * 		Hash of config used to decompile the code.
	 * @param diagnostics
	 * 		Problems reported by the decompiler.
	 */
	private DecompileResult(String text, Throwable exception, ResultType type, int configHash, List<DecompileDiagnostic> diagnostics) {
		this.text = text;
		this.exception = exception;
		this.type = type;
		this.configHash = configHash;
		this.diagnostics = diagnostics;
	}

	/**
	 * @param text
	 * 		New text content.
	 *
	 * @return Copy of result, with new text content.
	 */
	@Nonnull
	public DecompileResult withText(@Nonnull String text) {
		return new DecompileResult(text, exception, type, configHash, diagnostics);
	}

	/**
	 * @param configHash
	 * 		New config hash value.
	 *
	 * @return Copy of result, with updated config hash.
	 */
	@Nonnull
	DecompileResult withConfigHash(int configHash) {
		if (this.configHash == configHash)
			return this;
		return new DecompileResult(text, exception, type, configHash, diagnostics);
	}

	/**
	 * @param diagnostics
	 * 		Problems reported by the decompiler.
	 *
	 * @return Copy of result with the supplied diagnostics.
	 */
	@Nonnull
	public DecompileResult withDiagnostics(@Nonnull List<DecompileDiagnostic> diagnostics) {
		if (this.diagnostics.isEmpty() && diagnostics.isEmpty())
			return this;
		return new DecompileResult(text, exception, type, configHash, List.copyOf(diagnostics));
	}

	/**
	 * @return Problems reported even when a decompiler returned partial source.
	 */
	@Nonnull
	public List<DecompileDiagnostic> getDiagnostics() {
		return diagnostics;
	}

	/**
	 * @return Decompiled text.
	 * May be {@code null} when {@link #getType()} is not {@link ResultType#SUCCESS}.
	 */
	@Nullable
	public String getText() {
		return text;
	}

	/**
	 * @return Failure reason.
	 * May be {@code null} when {@link #getType()} is not {@link ResultType#FAILURE}.
	 */
	@Nullable
	public Throwable getException() {
		return exception;
	}

	/**
	 * @return Result type.
	 */
	@Nonnull
	public ResultType getType() {
		return type;
	}

	/**
	 * @return Value of {@link DecompilerConfig#getHash()} of associated decompiler.
	 * Used to determine if cached value in {@link CachedDecompileProperty} is up-to-date with current config.
	 */
	public int getConfigHash() {
		return configHash;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		DecompileResult that = (DecompileResult) o;

		if (configHash != that.configHash) return false;
		if (!Objects.equals(text, that.text)) return false;
		if (!Objects.equals(exception, that.exception)) return false;
		if (!diagnostics.equals(that.diagnostics)) return false;
		return type == that.type;
	}

	@Override
	public int hashCode() {
		int result = text != null ? text.hashCode() : 0;
		result = 31 * result + (exception != null ? exception.hashCode() : 0);
		result = 31 * result + type.hashCode();
		result = 31 * result + configHash;
		result = 31 * result + diagnostics.hashCode();
		return result;
	}

	/**
	 * Type of result.
	 */
	public enum ResultType {
		/**
		 * Successful decompilation.
		 */
		SUCCESS,
		/**
		 * Decompilation skipped for some reason. Likely due to a thread being cancelled.
		 */
		SKIPPED,
		/**
		 * Decompilation failed to emit any output.
		 */
		FAILURE
	}
}
