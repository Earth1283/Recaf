package software.coley.recaf.services.transform;

import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.VariableFoldingTransformer;

import java.util.List;
import java.util.Map;

/**
 * Built-in profiles for common deobfuscation tasks, independent of saved user presets.
 */
public final class BuiltinTransformationPresets {
	/**
	 * Folds known integer and long expressions, including reverse, rotations, shifts, and masks.
	 * Static constants are collected and inlined first, then bitwise and variable folding are repeated
	 * up to five times to simplify chains through local variables. Unknown inputs remain unevaluated.
	 */
	public static final TransformationPreset BITWISE_SIMPLIFICATION = new TransformationPreset(List.of(
			new TransformationPreset.TransformerPreset(StaticValueCollectionTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(StaticValueInliningTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(BitwiseFoldingTransformer.class.getName(), Map.of()),
			new TransformationPreset.TransformerPreset(VariableFoldingTransformer.class.getName(), Map.of())),
			5, false, TransformationPreset.ScopeMode.NONE, List.of());

	private BuiltinTransformationPresets() {}
}
