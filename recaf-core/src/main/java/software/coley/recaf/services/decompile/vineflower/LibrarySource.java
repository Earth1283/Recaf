package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.RuntimeWorkspaceResource;

import java.util.function.UnaryOperator;

/**
 * Library source for Vineflower covering the Java runtime and every class in the workspace.
 * <p>
 * The source is lazy: Vineflower asks for classes by name as it needs them instead of being given the name of every
 * available class up front. A new Vineflower instance is created for every decompilation, and registering every
 * workspace and runtime class name with each one would otherwise dominate the decompiler's allocations.
 *
 * @author Matt Coley
 * @author therathatter
 */
public class LibrarySource extends BaseSource {
	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param targetInfo
	 * 		Target class to decompile.
	 * @param nestedClassView
	 * 		Mapping applied to every other class before Vineflower reads it.
	 */
	protected LibrarySource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo targetInfo,
	                        @Nonnull UnaryOperator<JvmClassInfo> nestedClassView) {
		super(workspace, targetInfo, nestedClassView);
	}

	@Override
	public boolean isLazy() {
		return true;
	}

	@Override
	public Entries getEntries() {
		return Entries.EMPTY;
	}

	/**
	 * Resolves runtime classes before workspace classes, the same precedence Vineflower gives its own runtime source.
	 * Only classes of the runtime image are offered, not the libraries Recaf itself is built with.
	 */
	@Nullable
	@Override
	protected byte[] classBytes(@Nonnull String name) {
		if (RuntimeWorkspaceResource.isSystemClass(name)) {
			byte[] runtime = RuntimeWorkspaceResource.getRuntimeClass(name);
			if (runtime != null)
				return runtime;
		}
		ClassPathNode node = workspace.findJvmClass(false, name);
		if (node == null)
			return null;
		return nestedClassView.apply(node.getValue().asJvmClass()).getBytecode();
	}
}
