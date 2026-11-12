package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.services.decompile.filter.OutputTextFilter;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Basic setup for {@link JvmDecompiler}.
 *
 * @author Matt Coley
 */
public abstract class AbstractJvmDecompiler extends AbstractDecompiler implements JvmDecompiler {
	private final List<JvmBytecodeFilter> bytecodeFilters = new ArrayList<>();
	private final List<JvmBytecodeFilter> nestedClassFilters = new CopyOnWriteArrayList<>();

	/**
	 * @param name
	 * 		Decompiler name.
	 * @param version
	 * 		Decompiler version.
	 * @param config
	 * 		Decompiler configuration.
	 */
	public AbstractJvmDecompiler(@Nonnull String name, @Nonnull String version, @Nonnull DecompilerConfig config) {
		super(name, version, config);
	}

	@Override
	public boolean addJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter) {
		return bytecodeFilters.add(filter);
	}

	@Override
	public boolean removeJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter) {
		return bytecodeFilters.remove(filter);
	}

	/**
	 * Adds a filter for classes that are only loaded because the class being decompiled refers to them, such as its
	 * inner classes. Those are not passed through the other bytecode filters.
	 *
	 * @param filter
	 * 		Filter to add.
	 *
	 * @return {@code true} when the filter was added.
	 */
	@Override
	public boolean addNestedClassFilter(@Nonnull JvmBytecodeFilter filter) {
		return nestedClassFilters.add(filter);
	}

	/**
	 * @param filter
	 * 		Filter to remove.
	 *
	 * @return {@code true} when the filter was removed.
	 */
	@Override
	public boolean removeNestedClassFilter(@Nonnull JvmBytecodeFilter filter) {
		return nestedClassFilters.remove(filter);
	}

	/**
	 * @param workspace
	 * 		Workspace the class is from.
	 * @param targetName
	 * 		Name of the class being decompiled.
	 * @param info
	 * 		Class the decompiler needs to load because the class it is decompiling refers to it.
	 *
	 * @return The class to give the decompiler. The same instance unless the class is nested in the target and a nested
	 * class filter changes it. Other classes the target refers to are only read, never printed.
	 */
	@Nonnull
	protected JvmClassInfo nestedClassView(@Nonnull Workspace workspace, @Nonnull String targetName, @Nonnull JvmClassInfo info) {
		if (nestedClassFilters.isEmpty() || !info.getName().startsWith(targetName + '$'))
			return info;
		return JvmBytecodeFilter.applyFilters(workspace, info, nestedClassFilters);
	}

	@Nonnull
	@Override
	public final DecompileResult decompile(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
		// Get bytecode and run through filters.
		JvmClassInfo filteredBytecode = JvmBytecodeFilter.applyFilters(workspace, classInfo, bytecodeFilters);

		// Pass to implementation.
		DecompileResult result = decompileInternal(workspace, filteredBytecode);

		// Adapt output decompilation if output filters are registered.
		if (result.getType() == DecompileResult.ResultType.SUCCESS && result.getText() != null && !textFilters.isEmpty()) {
			String text = result.getText();
			for (OutputTextFilter filter : textFilters)
				text = filter.filter(workspace, classInfo, text);
			result = result.withText(text);
		}

		return result;
	}

	/**
	 * Takes on the work of {@link #decompile(Workspace, JvmClassInfo)} after the {@link #bytecodeFilters} have been applied to the class.
	 *
	 * @param workspace
	 * 		Workspace to pull data from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Decompilation result.
	 */
	@Nonnull
	protected abstract DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo);

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;

		AbstractJvmDecompiler other = (AbstractJvmDecompiler) o;

		return bytecodeFilters.equals(other.bytecodeFilters);
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + bytecodeFilters.hashCode();
		return result;
	}
}
