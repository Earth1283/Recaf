package software.coley.recaf.services.decompile.procyon;

import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.ITypeLoader;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.function.UnaryOperator;

/**
 * Type loader that pulls classes from a {@link Workspace}.
 *
 * @author xDark
 */
public final class WorkspaceTypeLoader implements ITypeLoader {
	private final Workspace workspace;
	private final UnaryOperator<JvmClassInfo> classView;

	/**
	 * @param workspace
	 * 		Active workspace.
	 */
	public WorkspaceTypeLoader(Workspace workspace) {
		this(workspace, UnaryOperator.identity());
	}

	/**
	 * @param workspace
	 * 		Active workspace.
	 * @param classView
	 * 		Mapping applied to every class before Procyon reads it.
	 */
	public WorkspaceTypeLoader(Workspace workspace, UnaryOperator<JvmClassInfo> classView) {
		this.workspace = workspace;
		this.classView = classView;
	}

	@Override
	public boolean tryLoadType(String internalName, Buffer buffer) {
		ClassPathNode node = workspace.findClass(internalName);
		if (node == null)
			return false;
		byte[] data = classView.apply(node.getValue().asJvmClass()).getBytecode();
		buffer.position(0);
		buffer.putByteArray(data, 0, data.length);
		buffer.position(0);
		return true;
	}
}