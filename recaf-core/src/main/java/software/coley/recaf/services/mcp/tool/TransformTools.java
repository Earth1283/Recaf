package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpJson;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.mcp.McpValues;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.services.transform.TransformationPreset;
import software.coley.recaf.services.transform.TransformationPresetManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools for Recaf's deobfuscation transformers.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class TransformTools implements McpToolProvider {
	private final McpSupport support;
	private final TransformationManager manager;
	private final TransformationApplierService applierService;
	private final TransformationPresetManager presets;

	@Inject
	public TransformTools(@Nonnull McpSupport support, @Nonnull TransformationManager manager,
	                      @Nonnull TransformationApplierService applierService,
	                      @Nonnull TransformationPresetManager presets) {
		this.support = support;
		this.manager = manager;
		this.applierService = applierService;
		this.presets = presets;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("transform_list",
				"List the deobfuscation transformers: opaque predicate and constant folding, control flow cleanup, " +
						"string and invokedynamic resolution, illegal-name fixes and more. Use the 'identifier' with transform_run.",
				McpAccess.READ, McpToolSchema.create(), this::list);
		registry.add("transform_presets", "Saved transformer presets.", McpAccess.READ, McpToolSchema.create(),
				args -> {
					JsonObject json = new JsonObject();
					for (String name : presets.getPresetNames())
						json.add(name, McpValues.toJson(presets.getPreset(name)));
					return McpToolResult.json(json);
				});
		registry.add("transform_run",
				"Run deobfuscation transformers over the workspace. Give 'transformers' (identifiers from transform_list) " +
						"or a 'preset' name. Applies the result unless preview is true.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.stringArray("transformers", "Transformer identifiers, run in dependency order.")
						.string("preset", "Name of a saved preset to run instead.")
						.object("parameters", "Transformer parameters by key, as listed by transform_list.")
						.integer("max_passes", "Maximum repeat passes. Default 4.")
						.bool("preview", "Report what would change without applying it. Default false."),
				this::run);
	}

	@Nonnull
	private McpToolResult list(@Nonnull McpArgs args) throws TransformationException {
		JsonArray array = new JsonArray();
		for (Class<? extends JvmClassTransformer> type : manager.getJvmClassTransformers()) {
			JvmClassTransformer transformer = manager.newJvmTransformer(type);
			JsonObject json = new JsonObject();
			json.addProperty("identifier", transformer.identifier());
			json.addProperty("class", type.getName());
			JsonArray parameters = new JsonArray();
			for (TransformationParameter<?> parameter : transformer.getParameterDefinitions()) {
				JsonObject p = new JsonObject();
				p.addProperty("key", parameter.key());
				p.addProperty("type", parameter.type().getSimpleName());
				p.addProperty("default", String.valueOf(parameter.defaultValue()));
				parameters.add(p);
			}
			if (!parameters.isEmpty())
				json.add("parameters", parameters);
			JsonArray dependencies = new JsonArray();
			transformer.dependencies().forEach(d -> dependencies.add(d.getSimpleName()));
			if (!dependencies.isEmpty())
				json.add("dependsOn", dependencies);
			array.add(json);
		}
		return McpToolResult.json(array);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private McpToolResult run(@Nonnull McpArgs args) throws TransformationException {
		support.workspace();
		List<Class<? extends JvmClassTransformer>> chosen = new ArrayList<>();
		Map<String, Object> parameters = new HashMap<>(McpJson.toMap(args.object("parameters")));
		int maxPasses = args.integer("max_passes", 4);
		if (args.has("preset")) {
			TransformationPreset preset = presets.getPreset(args.requireString("preset"));
			if (preset == null)
				throw new McpToolException("Unknown preset '" + args.string("preset", "") + "'. See transform_presets.");
			for (TransformationPreset.TransformerPreset entry : preset.transformers()) {
				chosen.add(byName(entry.transformerClassName()));
				entry.parameters().forEach(parameters::putIfAbsent);
			}
			if (!args.has("max_passes"))
				maxPasses = preset.maxPasses();
		} else {
			for (String id : args.strings("transformers"))
				chosen.add(byName(id));
		}
		if (chosen.isEmpty())
			throw new McpToolException("Provide 'transformers' or 'preset'");

		TransformationApplier applier = applierService.newApplierForCurrentWorkspace();
		applier.setMaxPasses(Math.max(1, Math.min(20, maxPasses)));
		JvmTransformResult result = applier.transformJvm(chosen, new TransformationParameters(parameters));
		boolean preview = args.bool("preview", false);
		if (!preview)
			result.apply();

		JsonObject json = new JsonObject();
		json.addProperty("applied", !preview);
		JsonObject modified = new JsonObject();
		for (Map.Entry<Class<? extends JvmClassTransformer>, Collection<ClassPathNode>> entry :
				result.getModifiedClassesPerTransformer().entrySet())
			modified.addProperty(entry.getKey().getSimpleName(), entry.getValue().size());
		json.add("classesModifiedPerTransformer", modified);
		json.addProperty("classesTransformed", result.getTransformedClasses().size());
		json.addProperty("classesRemoved", result.getClassesToRemove().size());
		json.addProperty("renames", result.getMappingsToApply().getClasses().size());
		Map<String, String> failures = new LinkedHashMap<>();
		result.getTransformerFailures().forEach((path, perTransformer) -> perTransformer.forEach((type, error) ->
				failures.put(path.getValue().getName() + " / " + type.getSimpleName(), String.valueOf(error))));
		json.add("failures", McpValues.toJson(failures));
		return McpToolResult.json(json);
	}

	@Nonnull
	private Class<? extends JvmClassTransformer> byName(@Nonnull String name) throws TransformationException {
		for (Class<? extends JvmClassTransformer> type : manager.getJvmClassTransformers()) {
			if (type.getName().equals(name) || type.getSimpleName().equals(name) ||
					manager.newJvmTransformer(type).identifier().equals(name))
				return type;
		}
		throw new McpToolException("Unknown transformer '" + name + "'. See transform_list.");
	}
}
