package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateCatchMergingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaquePredicateFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.RedundantTryCatchRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.dasho.DashOpaqueSeedFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.zkm.ZkmDecryptionCleanupTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.workspace.model.Workspace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transformed copies of a plugin's classes, made once for the whole plugin and used only for decompiling. The classes in
 * the workspace are never changed.
 * <p>
 * A copy is only used for the exact class it was made from. If the class is replaced, for example by a mapping, the
 * copy no longer applies and the class decompiles as it is, so a copy can never show out-of-date code.
 */
final class DeobfuscatedView {
	/**
	 * Transformers that make code easier to read without renaming or restructuring anything. Renaming transformers are
	 * left out because a view cannot rename. Transformers that change method signatures are left out because the
	 * plugin index describes methods by their original signatures.
	 */
	static final List<Class<? extends JvmClassTransformer>> PLAN = List.of(
			StaticValueCollectionTransformer.class,
			StaticValueInliningTransformer.class,
			CallResultInliningTransformer.class,
			InvokeDynamicInliningTransformer.class,
			OpaqueConstantFoldingTransformer.class,
			OpaquePredicateFoldingTransformer.class,
			GotoInliningTransformer.class,
			DeadCodeRemovingTransformer.class,
			DuplicateCatchMergingTransformer.class,
			RedundantTryCatchRemovingTransformer.class,
			VariableFoldingTransformer.class,
			DashOpaqueSeedFoldingTransformer.class,
			ZkmDecryptionCleanupTransformer.class);
	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	private DeobfuscatedView() {}

	/**
	 * Transforms every class of the workspace's primary resource. Slow for large plugins, so do not call this on a
	 * thread anything waits on.
	 *
	 * @param applierService
	 * 		Service to make the transformation applier with.
	 * @param graphService
	 * 		Service to make sure the workspace has an inheritance graph with.
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 * @param passes
	 * 		Times to repeat the transformers.
	 * @param feedback
	 * 		Feedback that can cancel the run.
	 *
	 * @return The transformed classes. Empty when nothing needed transforming.
	 *
	 * @throws TransformationException
	 * 		When the transformers cannot be run.
	 */
	@Nonnull
	static DeobfuscatedView build(@Nonnull TransformationApplierService applierService,
	                              @Nonnull InheritanceGraphService graphService,
	                              @Nonnull Workspace workspace, int passes,
	                              @Nonnull TransformationFeedback feedback) throws TransformationException {
		graphService.getOrCreateInheritanceGraph(workspace);
		// The paths in the transform result point at the transformed classes, so the originals have to be taken first.
		Map<String, JvmClassInfo> originals = new HashMap<>();
		for (JvmClassInfo info : workspace.getPrimaryResource().getJvmClassBundle().values())
			originals.put(info.getName(), info);
		TransformationApplier applier = applierService.newApplier(workspace);
		applier.setDropFaultyClasses(true);
		applier.setMaxPasses(passes);
		JvmTransformResult result = applier.transformJvm(PLAN, feedback);

		DeobfuscatedView view = new DeobfuscatedView();
		result.getTransformedClasses().values().forEach(transformed -> {
			JvmClassInfo original = originals.get(transformed.getName());
			if (original != null)
				view.entries.put(original.getName(), new Entry(original, transformed));
		});
		return view;
	}

	/**
	 * @param original
	 * 		Class as it is in the workspace.
	 *
	 * @return The transformed copy of exactly this class, or {@code null} if it has none or was replaced since.
	 */
	@Nullable
	JvmClassInfo transformedFor(@Nonnull JvmClassInfo original) {
		Entry entry = entries.get(original.getName());
		return entry != null && entry.original() == original ? entry.transformed() : null;
	}

	/**
	 * @return Names of the classes that have a transformed copy.
	 */
	@Nonnull
	Set<String> classNames() {
		return Set.copyOf(entries.keySet());
	}

	/**
	 * @return Number of classes that have a transformed copy.
	 */
	int size() {
		return entries.size();
	}

	private record Entry(@Nonnull JvmClassInfo original, @Nonnull JvmClassInfo transformed) {}
}
