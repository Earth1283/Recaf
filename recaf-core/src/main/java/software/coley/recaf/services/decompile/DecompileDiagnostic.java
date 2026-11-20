package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A problem reported by a decompiler while producing source.
 *
 * @param method
 * 		Affected method, if identified by the decompiler.
 * @param message
 * 		Decompiler message.
 * @param cause
 * 		Underlying error, if available.
 */
public record DecompileDiagnostic(@Nullable Method method, @Nonnull String message, @Nullable Throwable cause) {
	/**
	 * JVM identity of an affected method.
	 *
	 * @param owner
	 * 		Internal class name.
	 * @param name
	 * 		Method name.
	 * @param descriptor
	 * 		Method descriptor.
	 */
	public record Method(@Nonnull String owner, @Nonnull String name, @Nonnull String descriptor) {}
}
