package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Outline for decompilers targeting {@link JvmClassInfo}.
 *
 * @author Matt Coley
 */
public interface JvmDecompiler extends Decompiler {
	/**
	 * Adds a filter which operates on the bytecode of classes before passing it along to the decompiler.
	 *
	 * @param filter
	 * 		Filter to add.
	 *
	 * @return {@code true} on successful addition.
	 * {@code false} if the filter has already been added.
	 */
	boolean addJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter);

	/**
	 * Removes a filter which operates on the bytecode of classes before passing it along to the decompiler.
	 *
	 * @param filter
	 * 		Filter to remove.
	 *
	 * @return {@code true} on successful removal.
	 * {@code false} if the filter was not already registered.
	 */
	boolean removeJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter);

	/**
	 * Adds a filter for classes that are only loaded because the class being decompiled refers to them, such as its
	 * inner classes. Those are not passed through the filters added with {@link #addJvmBytecodeFilter(JvmBytecodeFilter)}.
	 *
	 * @param filter
	 * 		Filter to add.
	 *
	 * @return {@code true} when the filter was added. Implementations that load no other classes return {@code false}.
	 */
	default boolean addNestedClassFilter(@Nonnull JvmBytecodeFilter filter) {
		return false;
	}

	/**
	 * @param filter
	 * 		Filter to remove.
	 *
	 * @return {@code true} when the filter was removed.
	 */
	default boolean removeNestedClassFilter(@Nonnull JvmBytecodeFilter filter) {
		return false;
	}

	/**
	 * @param workspace
	 * 		Workspace to pull data from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Decompilation result.
	 */
	@Nonnull
	DecompileResult decompile(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo);
}
