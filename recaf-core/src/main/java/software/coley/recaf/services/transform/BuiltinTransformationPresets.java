package software.coley.recaf.services.transform;

import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DispatcherInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.EmptyControlFlowRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaquePredicateFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LiteralStackSimplificationTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.skid.SkidXorInliningTransformer;

import java.util.List;
import java.util.Map;

/**
 * Built-in profiles for common deobfuscation tasks, independent of saved user presets.
 */
public final class BuiltinTransformationPresets {
	/** Java-level Skidfuscator cleanup. Native predicates remain unknown; input initializers are not emulated. */
	public static final TransformationPreset SKIDFUSCATOR_SIMPLIFICATION = new TransformationPreset(List.of(
			new TransformationPreset.TransformerPreset(LiteralStackSimplificationTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(SkidXorInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(StaticValueCollectionTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(StaticValueInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(InvokeDynamicInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(CallResultInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(BitwiseFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(DispatcherInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(OpaquePredicateFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(DeadCodeRemovingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(GotoInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(VariableFoldingTransformer.class.getName(), Map.of())),
			6, false, TransformationPreset.ScopeMode.NONE, List.of());

	/** Conservative local integer dispatcher unflattening, followed by ordinary folding and cleanup. */
	public static final TransformationPreset CONTROL_FLOW_SIMPLIFICATION = new TransformationPreset(List.of(
			new TransformationPreset.TransformerPreset(LiteralStackSimplificationTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(OpaqueConstantFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(DispatcherInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(OpaquePredicateFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(EmptyControlFlowRemovingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(DeadCodeRemovingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(GotoInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(VariableFoldingTransformer.class.getName(), Map.of())),
			6, false, TransformationPreset.ScopeMode.NONE, List.of());

	/**
	 * Folds known integer and long expressions, including reverse, rotations, shifts, and masks.
	 * Combines adjacent literal bitwise assignments to the same local variable.
	 * Static constants are collected and inlined first, then bitwise and variable folding are repeated
	 * up to five times to simplify chains through local variables. Unknown inputs remain unevaluated.
	 */
	public static final TransformationPreset BITWISE_SIMPLIFICATION = new TransformationPreset(List.of(
			new TransformationPreset.TransformerPreset(LiteralStackSimplificationTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(StaticValueCollectionTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(StaticValueInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(BitwiseFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(VariableFoldingTransformer.class.getName(), Map.of())),
			5, false, TransformationPreset.ScopeMode.NONE, List.of());

	private BuiltinTransformationPresets() {}
}
