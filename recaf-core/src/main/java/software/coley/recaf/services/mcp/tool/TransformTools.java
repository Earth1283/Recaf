package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.ClassInfo;
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
import software.coley.recaf.services.transform.BuiltinTransformationPresets;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.services.transform.TransformationPreset;
import software.coley.recaf.services.transform.TransformationPresetManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tools for Recaf's deobfuscation transformers.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class TransformTools implements McpToolProvider {
	private record BuiltinPreset(@Nonnull String name, @Nonnull String description,
	                             @Nonnull TransformationPreset preset) {}

	private static final List<BuiltinPreset> BUILTIN_PRESETS = List.of(
			new BuiltinPreset("skidfuscator",
					"Java-level Skidfuscator cleanup (stack simplification, XOR inlining, static values, invokedynamic, control flow).",
					BuiltinTransformationPresets.SKIDFUSCATOR_SIMPLIFICATION),
			new BuiltinPreset("control-flow",
					"Conservative local dispatcher unflattening, try-catch repair, algebraic/opaque folding, dead & empty flow cleanup.",
					BuiltinTransformationPresets.CONTROL_FLOW_SIMPLIFICATION),
			new BuiltinPreset("obfuscated-plugin",
					"Broad cleanup for obfuscated plugins: try-catch repair, algebraic predicates, constant/call inlining, invokedynamic, control flow, dead private decoy removal.",
					BuiltinTransformationPresets.OBFUSCATED_PLUGIN_CLEANUP),
			new BuiltinPreset("bitwise",
					"Folds known integer/long bitwise expressions, rotations, reverses and constant chains.",
					BuiltinTransformationPresets.BITWISE_SIMPLIFICATION)
	);

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
		registry.add("transform_presets",
				"List built-in and saved transformer presets (e.g. skidfuscator, control-flow, obfuscated-plugin, bitwise).",
				McpAccess.READ, McpToolSchema.create(), this::listPresets);
		registry.add("transform_run",
				"Run deobfuscation transformers over the workspace. Give 'transformers' (identifiers from transform_list) " +
						"or a 'preset' name (see transform_presets). Optionally scope to specific 'classes' or 'package'. " +
						"Applies the result unless preview is true.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.stringArray("transformers", "Transformer identifiers, run in dependency order.")
						.string("preset", "Name of a built-in or saved preset to run instead.")
						.object("parameters", "Transformer parameters by key, as listed by transform_list.")
						.integer("max_passes", "Maximum repeat passes. Default 4 (or preset default).")
						.stringArray("classes", "Optional list of class names to restrict transformation to.")
						.string("package", "Optional package filter (e.g. 'com/example' or 'com/example/*').")
						.bool("drop_faulty", "Drop classes that fail bytecode writing instead of aborting. Default: preset setting or false.")
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
	private McpToolResult listPresets(@Nonnull McpArgs args) {
		JsonObject json = new JsonObject();
		for (BuiltinPreset entry : BUILTIN_PRESETS) {
			JsonObject p = describePreset(entry.preset(), true, entry.description());
			json.add(entry.name(), p);
		}
		for (String name : presets.getPresetNames()) {
			TransformationPreset preset = presets.getPreset(name);
			if (preset != null)
				json.add(name, describePreset(preset, false, null));
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private JsonObject describePreset(@Nonnull TransformationPreset preset, boolean builtin,
	                                  @Nullable String description) {
		JsonObject json = new JsonObject();
		json.addProperty("builtin", builtin);
		if (description != null)
			json.addProperty("description", description);
		json.addProperty("maxPasses", preset.maxPasses());
		json.addProperty("dropFaultyClasses", preset.dropFaultyClasses());
		JsonArray transformers = new JsonArray();
		for (TransformationPreset.TransformerPreset tp : preset.transformers()) {
			String id = tp.transformerClassName();
			try {
				id = manager.newJvmTransformer(byName(tp.transformerClassName())).identifier();
			} catch (TransformationException | McpToolException ignored) {
			}
			transformers.add(id);
		}
		json.add("transformers", transformers);
		return json;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private McpToolResult run(@Nonnull McpArgs args) throws TransformationException {
		support.workspace();
		List<Class<? extends JvmClassTransformer>> chosen = new ArrayList<>();
		Map<String, Object> parameters = new HashMap<>(McpJson.toMap(args.object("parameters")));
		int maxPasses = args.integer("max_passes", 4);
		TransformationPreset resolvedPreset = null;
		if (args.has("preset")) {
			String presetName = args.requireString("preset");
			resolvedPreset = findPreset(presetName);
			if (resolvedPreset == null)
				throw new McpToolException("Unknown preset '" + presetName + "'. See transform_presets.");
			for (TransformationPreset.TransformerPreset entry : resolvedPreset.transformers()) {
				chosen.add(byName(entry.transformerClassName()));
				entry.parameters().forEach(parameters::putIfAbsent);
			}
			if (!args.has("max_passes"))
				maxPasses = resolvedPreset.maxPasses();
		} else {
			for (String id : args.strings("transformers"))
				chosen.add(byName(id));
		}
		if (chosen.isEmpty())
			throw new McpToolException("Provide 'transformers' or 'preset'");

		Set<String> classFilter = null;
		if (args.has("classes")) {
			List<String> rawClasses = args.strings("classes");
			if (!rawClasses.isEmpty()) {
				classFilter = new HashSet<>();
				for (String c : rawClasses)
					classFilter.add(McpSupport.normalizeClassName(c));
			}
		}
		String packageFilter = args.string("package", null);
		TransformationPreset presetForScope = resolvedPreset;
		Set<String> finalClassFilter = classFilter;

		TransformationFeedback feedback = new TransformationFeedback() {
			@Override
			public boolean shouldTransform(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
			                               @Nonnull ClassBundle<?> bundle, @Nonnull ClassInfo classInfo,
			                               @Nonnull ClassTransformer transformer, int pass) {
				String name = classInfo.getName();
				if (finalClassFilter != null && !finalClassFilter.contains(name))
					return false;
				if (packageFilter != null && !McpSupport.matchesPackage(name, true, packageFilter))
					return false;
				if (presetForScope != null && !presetForScope.packagePrefixes().isEmpty()) {
					boolean matchesPrefix = presetForScope.packagePrefixes().stream()
							.anyMatch(prefix -> name.startsWith(McpSupport.normalizeClassName(prefix)));
					if (presetForScope.scopeMode() == TransformationPreset.ScopeMode.WHITELIST && !matchesPrefix)
						return false;
					if (presetForScope.scopeMode() == TransformationPreset.ScopeMode.BLACKLIST && matchesPrefix)
						return false;
				}
				return true;
			}
		};

		boolean dropFaulty = args.has("drop_faulty")
				? args.bool("drop_faulty", false)
				: (resolvedPreset != null && resolvedPreset.dropFaultyClasses());

		TransformationApplier applier = applierService.newApplierForCurrentWorkspace();
		applier.setMaxPasses(Math.max(1, Math.min(20, maxPasses)));
		applier.setDropFaultyClasses(dropFaulty);
		JvmTransformResult result = applier.transformJvm(chosen, new TransformationParameters(parameters), feedback);
		boolean preview = args.bool("preview", false);
		if (!preview)
			result.apply();

		JsonObject json = new JsonObject();
		json.addProperty("applied", !preview);
		JsonObject modified = new JsonObject();
		TreeSet<String> modifiedClassNames = new TreeSet<>();
		for (Map.Entry<Class<? extends JvmClassTransformer>, Collection<ClassPathNode>> entry :
				result.getModifiedClassesPerTransformer().entrySet()) {
			modified.addProperty(entry.getKey().getSimpleName(), entry.getValue().size());
			entry.getValue().forEach(p -> modifiedClassNames.add(p.getValue().getName()));
		}
		json.add("classesModifiedPerTransformer", modified);
		JsonArray modifiedClasses = new JsonArray();
		modifiedClassNames.stream().limit(100).forEach(modifiedClasses::add);
		json.add("modifiedClasses", modifiedClasses);
		json.addProperty("classesTransformed", result.getTransformedClasses().size());
		json.addProperty("classesRemoved", result.getClassesToRemove().size());
		json.addProperty("renames", result.getMappingsToApply().getClasses().size());
		Map<String, String> failures = new LinkedHashMap<>();
		result.getTransformerFailures().forEach((path, perTransformer) -> perTransformer.forEach((type, error) ->
				failures.put(path.getValue().getName() + " / " + type.getSimpleName(), String.valueOf(error))));
		json.add("failures", McpValues.toJson(failures));
		return McpToolResult.json(json);
	}

	@Nullable
	private TransformationPreset findPreset(@Nonnull String name) {
		String normalized = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		for (BuiltinPreset entry : BUILTIN_PRESETS) {
			if (entry.name().equals(normalized)
					|| (entry.name().equals("skidfuscator") && "skidfuscator-simplification".equals(normalized))
					|| (entry.name().equals("control-flow") && "control-flow-simplification".equals(normalized))
					|| (entry.name().equals("obfuscated-plugin") && "obfuscated-plugin-cleanup".equals(normalized))
					|| (entry.name().equals("bitwise") && "bitwise-simplification".equals(normalized)))
				return entry.preset();
		}
		return presets.getPreset(name);
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
