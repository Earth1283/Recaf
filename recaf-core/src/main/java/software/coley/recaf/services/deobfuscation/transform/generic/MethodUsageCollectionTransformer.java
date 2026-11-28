package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.CollectionTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.util.analysis.MethodUsageSupport;
import software.coley.recaf.util.analysis.MethodUsageSupport.ClassMethodUsage;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects cross-class method references, nestmate relationships, and reflection usage
 * from the current transformed state of classes in the workspace.
 */
@Dependent
public class MethodUsageCollectionTransformer implements JvmClassTransformer, CollectionTransformer {
	public static final String IDENTIFIER = "peephole.data.methodusage";

	private final Map<String, ClassMethodUsage> usageByClass = new ConcurrentHashMap<>();
	private final InheritanceGraphService graphService;
	private InheritanceGraph inheritanceGraph;

	@Inject
	public MethodUsageCollectionTransformer(@Nonnull InheritanceGraphService graphService) {
		this.graphService = graphService;
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		inheritanceGraph = graphService.getOrCreateInheritanceGraph(workspace);
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		usageByClass.put(node.name, MethodUsageSupport.scanClassNode(node, inheritanceGraph));
	}

	/**
	 * @param owner
	 * 		Internal name of the declaring class.
	 * @param name
	 * 		Method name.
	 * @param desc
	 * 		Method descriptor.
	 *
	 * @return {@code true} when any other class in the collected workspace references this method.
	 */
	public boolean isReferencedOutsideClass(@Nonnull String owner, @Nonnull String name, @Nonnull String desc) {
		String key = MethodUsageSupport.methodKey(owner, name, desc);
		for (Map.Entry<String, ClassMethodUsage> entry : usageByClass.entrySet()) {
			if (!owner.equals(entry.getKey()) && entry.getValue().outgoingMethodKeys().contains(key))
				return true;
		}
		return false;
	}

	/**
	 * @param node
	 * 		Class node to check.
	 *
	 * @return {@code true} when this class or any of its nestmates uses declared/lookup method reflection.
	 */
	public boolean hasDeclaredMethodReflectionInNest(@Nonnull ClassNode node) {
		return MethodUsageSupport.hasDeclaredMethodReflectionInNest(node, usageByClass);
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the class calls {@code Class.getMethod} or {@code Class.getMethods}.
	 */
	public boolean hasPublicMethodReflection(@Nonnull String className) {
		ClassMethodUsage usage = usageByClass.get(className);
		return usage != null && usage.usesPublicMethodReflection();
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(
				CallResultInliningTransformer.class,
				OpaqueConstantFoldingTransformer.class,
				DeadCodeRemovingTransformer.class,
				InvokeDynamicInliningTransformer.class,
				OpaquePredicateFoldingTransformer.class,
				StaticValueInliningTransformer.class
		);
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Collections.singleton(DeadPrivateMethodRemovingTransformer.class);
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}
}
