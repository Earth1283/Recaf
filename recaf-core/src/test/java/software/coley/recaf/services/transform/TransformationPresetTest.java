package software.coley.recaf.services.transform;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformationPresetTest {
	private final Gson gson = new Gson();

	@Test
	void previewOnlySurvivesSerialization() {
		TransformationPreset preset = new TransformationPreset(List.of(new TransformationPreset.TransformerPreset(
				BitwiseFoldingTransformer.class.getName(), Map.of(BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING, true,
				BitwiseFoldingTransformer.KEY_REPEAT_BITWISE, true))), 5, false,
				TransformationPreset.ScopeMode.NONE, List.of(), true);
		assertEquals(preset, gson.fromJson(gson.toJson(preset), TransformationPreset.class));
	}

	@Test
	void existingPresetsRetainWorkspaceMode() {
		TransformationPreset preset = gson.fromJson("""
				{"transformers":[],"maxPasses":5,"dropFaultyClasses":false,"scopeMode":"NONE","packagePrefixes":[]}
				""", TransformationPreset.class);
		assertFalse(preset.previewOnly());
		assertEquals(new TransformationPreset(List.of(), 5, false,
				TransformationPreset.ScopeMode.NONE, List.of()), preset);
	}
}
