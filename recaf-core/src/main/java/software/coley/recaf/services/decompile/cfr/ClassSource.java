package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.TargetCodeScope;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collection;
import java.util.Collections;
import java.util.function.UnaryOperator;

/**
 * CFR class source. Provides access to workspace clases.
 *
 * @author Matt Coley
 */
public class ClassSource implements ClassFileSource {
	private final Workspace workspace;
	private final String targetClassName;
	private final byte[] targetClassBytecode;
	private final UnaryOperator<JvmClassInfo> nestedClassView;
	private final TargetCodeScope scope;

	/**
	 * Constructs a CFR class source.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 * @param targetClassName
	 * 		Name to override.
	 * @param targetClassBytecode
	 * 		Bytecode to override.
	 */
	public ClassSource(@Nonnull Workspace workspace, @Nonnull String targetClassName,
	                   @Nonnull byte[] targetClassBytecode) {
		this(workspace, targetClassName, targetClassBytecode, UnaryOperator.identity());
	}

	/**
	 * Constructs a CFR class source.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 * @param targetClassName
	 * 		Name to override.
	 * @param targetClassBytecode
	 * 		Bytecode to override.
	 * @param nestedClassView
	 * 		Mapping applied to every other class before CFR reads it.
	 */
	public ClassSource(@Nonnull Workspace workspace, @Nonnull String targetClassName,
	                   @Nonnull byte[] targetClassBytecode, @Nonnull UnaryOperator<JvmClassInfo> nestedClassView) {
		this(workspace, targetClassName, targetClassBytecode, nestedClassView, null);
	}

	/**
	 * Constructs a CFR class source.
	 *
	 * @param workspace
	 * 		Workspace to pull classes from.
	 * @param target
	 * 		Class to decompile.
	 * @param nestedClassView
	 * 		Mapping applied to every other class before CFR reads it.
	 */
	public ClassSource(@Nonnull Workspace workspace, @Nonnull JvmClassInfo target,
	                   @Nonnull UnaryOperator<JvmClassInfo> nestedClassView) {
		this(workspace, target.getName(), target.getBytecode(), nestedClassView, target);
	}

	private ClassSource(@Nonnull Workspace workspace, @Nonnull String targetClassName,
	                    @Nonnull byte[] targetClassBytecode, @Nonnull UnaryOperator<JvmClassInfo> nestedClassView,
	                    @Nullable JvmClassInfo targetInfo) {
		this.workspace = workspace;
		this.targetClassName = targetClassName;
		this.targetClassBytecode = targetClassBytecode;
		this.nestedClassView = nestedClassView;
		scope = targetInfo == null ? TargetCodeScope.of(workspace, targetClassName, targetClassBytecode) :
				TargetCodeScope.of(workspace, targetInfo);
	}

	@Override
	public void informAnalysisRelativePathDetail(String usePath, String specPath) {
	}

	@Override
	public Collection<String> addJar(String jarPath) {
		return Collections.emptySet();
	}

	@Override
	public String getPossiblyRenamedPath(String path) {
		return path;
	}

	@Override
	public Pair<byte[], String> getClassFileContent(String inputPath) {
		int endIndex = inputPath.indexOf(".class");
		String className = endIndex > 0 ? inputPath.substring(0, endIndex) : inputPath;
		byte[] code;
		if (className.equals(targetClassName)) {
			code = targetClassBytecode;
		} else {
			ClassPathNode result = workspace.findClass(className);
			if (result == null) {
				code = null;
			} else {
				code = scope.bytecodeFor(result.getValue().asJvmClass(), nestedClassView);
			}
		}
		return new Pair<>(code, inputPath);
	}
}
