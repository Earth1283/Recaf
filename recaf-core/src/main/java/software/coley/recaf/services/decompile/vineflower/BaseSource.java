package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.function.UnaryOperator;

/**
 * Base Vineflower class/library source.
 *
 * @author therathatter
 */
public abstract class BaseSource implements IContextSource {
	protected final JvmClassInfo targetInfo;
	protected final Workspace workspace;
	protected final UnaryOperator<JvmClassInfo> nestedClassView;

	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param targetInfo
	 * 		Target class to decompile.
	 */
	protected BaseSource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo targetInfo) {
		this(workspace, targetInfo, UnaryOperator.identity());
	}

	/**
	 * @param workspace
	 * 		Workspace to pull class files from.
	 * @param targetInfo
	 * 		Target class to decompile.
	 * @param nestedClassView
	 * 		Mapping applied to every other class before Vineflower reads it.
	 */
	protected BaseSource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo targetInfo,
	                     @Nonnull UnaryOperator<JvmClassInfo> nestedClassView) {
		this.workspace = workspace;
		this.targetInfo = targetInfo;
		this.nestedClassView = nestedClassView;
	}

	@Override
	public String getName() {
		return "Recaf";
	}

	/**
	 * Hands Vineflower the class bytes directly. The default implementation reads them from
	 * {@link #getInputStream(String)}, copying every class Vineflower loads.
	 */
	@Override
	public byte[] getClassBytes(String className) {
		return classBytes(className);
	}

	@Override
	public InputStream getInputStream(String resource) {
		String name = resource.substring(0, resource.length() - IContextSource.CLASS_SUFFIX.length());
		byte[] bytes = classBytes(name);
		return bytes == null ? null : new ByteArrayInputStream(bytes);
	}

	/**
	 * @param name
	 * 		Internal class name.
	 *
	 * @return Bytecode of the class, or {@code null} when it is not available. Vineflower wants missing data to be
	 * {@code null}, not an exception or empty stream.
	 */
	@Nullable
	protected byte[] classBytes(@Nonnull String name) {
		if (name.equals(targetInfo.getName()))
			return targetInfo.getBytecode();
		ClassPathNode node = workspace.findClass(name);
		if (node == null)
			return null;
		return nestedClassView.apply(node.getValue().asJvmClass()).getBytecode();
	}
}
