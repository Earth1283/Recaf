package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.analysis.plugin.semantic.NameSuggestion;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.mapping.gen.filter.IncludeKeywordNameFilter;
import software.coley.recaf.services.mapping.gen.filter.IncludeLongNameFilter;
import software.coley.recaf.services.mapping.gen.filter.IncludeNonAsciiNameFilter;
import software.coley.recaf.services.mapping.gen.filter.IncludeNonJavaIdentifierNameFilter;
import software.coley.recaf.services.mapping.gen.filter.IncludeWhitespaceNameFilter;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;
import software.coley.recaf.services.mapping.gen.naming.NameGeneratorProvider;
import software.coley.recaf.services.mapping.gen.naming.NameGeneratorProviders;
import software.coley.recaf.services.mapping.matching.SimilarityMappingOptions;
import software.coley.recaf.services.mapping.matching.SimilarityMappingService;
import software.coley.recaf.services.mapping.matching.SimilarityMappingsReport;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.mcp.McpValues;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tools for renaming and for mapping files.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class MappingTools implements McpToolProvider {
	private final McpSupport support;
	private final MappingApplierService applierService;
	private final MappingFormatManager formatManager;
	private final MappingGenerator generator;
	private final NameGeneratorProviders nameProviders;
	private final InheritanceGraphService graphService;
	private final AggregateMappingManager aggregateManager;
	private final SimilarityMappingService similarityService;
	private final PluginSemanticService semanticService;

	@Inject
	public MappingTools(@Nonnull McpSupport support, @Nonnull MappingApplierService applierService,
	                    @Nonnull MappingFormatManager formatManager, @Nonnull MappingGenerator generator,
	                    @Nonnull NameGeneratorProviders nameProviders, @Nonnull InheritanceGraphService graphService,
	                    @Nonnull AggregateMappingManager aggregateManager,
	                    @Nonnull SimilarityMappingService similarityService,
	                    @Nonnull PluginSemanticService semanticService) {
		this.support = support;
		this.applierService = applierService;
		this.formatManager = formatManager;
		this.generator = generator;
		this.nameProviders = nameProviders;
		this.graphService = graphService;
		this.aggregateManager = aggregateManager;
		this.similarityService = similarityService;
		this.semanticService = semanticService;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("rename_class",
				"Rename a class, updating every reference to it across the workspace.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("name", "Current class name.")
						.requiredString("new_name", "New class name (internal form, may change the package)."),
				this::renameClass);
		registry.add("rename_member",
				"Rename a field or method, including the overrides and overridden declarations in its hierarchy.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("class", "Declaring class.")
						.requiredString("name", "Current member name.")
						.string("descriptor", "Descriptor, needed when the name is overloaded.")
						.requiredString("new_name", "New member name."),
				this::renameMember);
		registry.add("mapping_formats", "Mapping file formats Recaf can read and write.", McpAccess.READ,
				McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						new ArrayList<>(formatManager.getMappingFileFormats()))));
		registry.add("mapping_apply",
				"Apply mappings given as text in one of the supported formats (see mapping_formats). " +
						"Pass 'text' or a 'path' to read.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("format", "Format name.")
						.string("text", "Mapping file content.")
						.string("path", "Path of a mapping file to read instead of passing text."),
				this::applyText);
		registry.add("mapping_export",
				"Export every rename applied in this session as a mapping file, so the work can be reused.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("format", "Format name.")
						.string("path", "Write to this file instead of returning the text."),
				this::export);
		registry.add("mapping_generate",
				"Auto-generate names for obfuscated classes and members (non-ASCII, whitespace, keywords, overly long, " +
						"or invalid identifiers). Preview by default, set apply=true to rename.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.string("generator", "Name generator, one of: " + "alphabet, incrementing. Default alphabet.")
						.bool("apply", "Apply the generated names. Default false (preview only).")
						.paging(),
				this::generate);
		registry.add("mapping_similarity",
				"Port names from a deobfuscated or older version onto an obfuscated one by matching class and member " +
						"structure. Both versions must be in the workspace, e.g. one opened and the other added as a library.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("source", "Label of the resource with the good names (see workspace_info).")
						.requiredString("target", "Label of the resource to rename.")
						.integer("class_threshold", "Minimum class similarity percent. Default 70.")
						.integer("member_threshold", "Minimum member similarity percent. Default 60.")
						.bool("apply", "Apply the mappings to the primary resource. Default false (preview only)."),
				this::similarity);
		registry.add("plugin_name_suggestions",
				"Suggest readable names for obfuscated classes and members of a Minecraft plugin from how they are used " +
						"(listeners, commands, config keys, API calls).",
				McpAccess.READ,
				McpToolSchema.create()
						.integer("min_confidence", "Minimum confidence 0-100. Default " + NameSuggestion.RECOMMENDED_CONFIDENCE + ".")
						.paging(),
				this::suggestNames);
		registry.add("plugin_apply_names",
				"Apply the suggestions from plugin_name_suggestions at or above a confidence threshold.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.integer("min_confidence", "Minimum confidence 0-100. Default " + NameSuggestion.RECOMMENDED_CONFIDENCE + "."),
				this::applyNames);
	}

	@Nonnull
	private McpToolResult renameClass(@Nonnull McpArgs args) {
		ClassInfo info = support.requireClass(args.requireString("name")).getValue();
		String newName = McpSupport.normalizeClassName(args.requireString("new_name"));
		if (support.workspace().findClass(false, newName) != null)
			throw new McpToolException("A class named '" + newName + "' already exists");
		IntermediateMappings mappings = new IntermediateMappings();
		mappings.addClass(info.getName(), newName);
		for (var inner : info.getInnerClasses()) {
			if (inner.isExternalReference() || inner.isSelfReference())
				continue;
			String innerName = inner.getInnerClassName();
			if (innerName.startsWith(info.getName() + "$"))
				mappings.addClass(innerName, newName + innerName.substring(info.getName().length()));
		}
		return apply(mappings);
	}

	@Nonnull
	private McpToolResult renameMember(@Nonnull McpArgs args) {
		ClassInfo info = support.requireClass(args.requireString("class")).getValue();
		String name = args.requireString("name");
		String descriptor = args.string("descriptor", null);
		String newName = args.requireString("new_name");
		List<FieldMember> fields = info.getFields().stream()
				.filter(f -> f.getName().equals(name) && (descriptor == null || f.getDescriptor().equals(descriptor))).toList();
		List<MethodMember> methods = info.getMethods().stream()
				.filter(m -> m.getName().equals(name) && (descriptor == null || m.getDescriptor().equals(descriptor))).toList();
		if (fields.size() + methods.size() == 0)
			throw new McpToolException("No member '" + name + "' in " + info.getName());
		if (fields.size() + methods.size() > 1)
			throw new McpToolException("'" + name + "' is ambiguous, pass 'descriptor'. Candidates: " +
					java.util.stream.Stream.concat(fields.stream().map(FieldMember::getDescriptor),
							methods.stream().map(MethodMember::getDescriptor)).toList());
		IntermediateMappings mappings = new IntermediateMappings();
		if (!fields.isEmpty())
			mappings.addField(info.getName(), fields.getFirst().getDescriptor(), name, newName);
		else
			mappings.addMethod(info.getName(), methods.getFirst().getDescriptor(), name, newName);
		return apply(mappings);
	}

	@Nonnull
	private McpToolResult applyText(@Nonnull McpArgs args) throws Exception {
		MappingFileFormat format = format(args.requireString("format"));
		String text = args.has("path") ? Files.readString(Path.of(args.requireString("path"))) : args.string("text", null);
		if (text == null)
			throw new McpToolException("Provide 'text' or 'path'");
		IntermediateMappings mappings;
		try {
			mappings = format.parse(text);
		} catch (InvalidMappingException ex) {
			throw new McpToolException("Invalid mappings: " + ex.getMessage());
		}
		return apply(mappings);
	}

	@Nonnull
	private McpToolResult export(@Nonnull McpArgs args) throws Exception {
		MappingFileFormat format = format(args.requireString("format"));
		if (!format.supportsExportText())
			throw new McpToolException("The '" + format.implementationName() + "' format cannot be exported");
		support.workspace();
		String text = format.exportText(aggregateManager.getAggregatedMappings());
		if (text == null)
			throw new McpToolException("Export is not supported by that format");
		if (args.has("path")) {
			Path path = Path.of(args.requireString("path")).toAbsolutePath();
			Files.writeString(path, text);
			JsonObject json = new JsonObject();
			json.addProperty("written", path.toString());
			json.addProperty("chars", text.length());
			return McpToolResult.json(json);
		}
		return McpToolResult.json(McpSupport.bounded(text, 100_000));
	}

	@Nonnull
	private McpToolResult generate(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		String providerId = args.string("generator", "alphabet");
		NameGeneratorProvider<?> provider = nameProviders.getProviders().get(providerId);
		if (provider == null)
			throw new McpToolException("Unknown generator '" + providerId + "'. Available: " + nameProviders.getProviders().keySet());
		NameGeneratorFilter filter = new IncludeNonAsciiNameFilter(new IncludeNonJavaIdentifierNameFilter(
				new IncludeKeywordNameFilter(new IncludeWhitespaceNameFilter(new IncludeLongNameFilter(null, 60, true, true, true, false)))));
		InheritanceGraph graph = graphService.getOrCreateInheritanceGraph(workspace);
		Mappings mappings = generator.generate(workspace, workspace.getPrimaryResource(), graph,
				provider.createGenerator(), filter);
		IntermediateMappings intermediate = mappings.exportIntermediate();
		if (args.bool("apply", false))
			return apply(intermediate);
		return McpToolResult.json(previewMappings(intermediate, args));
	}

	@Nonnull
	private McpToolResult similarity(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource source = resource(args.requireString("source"));
		WorkspaceResource target = resource(args.requireString("target"));
		SimilarityMappingOptions options = new SimilarityMappingOptions(args.integer("class_threshold", 70), 5,
				args.integer("member_threshold", 60), 25, 10);
		SimilarityMappingsReport report = similarityService.analyze(workspace, source, target, options,
				software.coley.recaf.services.search.SearchFeedback.DEFAULT);
		IntermediateMappings mappings = report.getMappings();
		if (args.bool("apply", false))
			return apply(mappings);
		JsonObject json = previewMappings(mappings, args);
		json.addProperty("acceptedClassMatches", report.getAcceptedMatches().size());
		json.addProperty("unresolvedClasses", report.getUnresolvedClasses().size());
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult suggestNames(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		int min = args.integer("min_confidence", NameSuggestion.RECOMMENDED_CONFIDENCE);
		return McpToolResult.json(McpSupport.page(semanticService.suggestNames(workspace).stream()
				.filter(s -> s.confidence() >= min), args, 100, McpValues::toJson));
	}

	@Nonnull
	private McpToolResult applyNames(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		int min = args.integer("min_confidence", NameSuggestion.RECOMMENDED_CONFIDENCE);
		List<NameSuggestion> chosen = semanticService.suggestNames(workspace).stream()
				.filter(s -> s.confidence() >= min).toList();
		PluginSemanticService.MappingPlan plan = semanticService.createMappings(workspace, chosen);
		McpToolResult result = apply(plan.mappings());
		JsonObject json = result.text().startsWith("{") ? com.google.gson.JsonParser.parseString(result.text()).getAsJsonObject()
				: new JsonObject();
		json.add("problems", McpValues.toJson(plan.problems()));
		json.addProperty("suggestionsApplied", plan.count());
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult apply(@Nonnull Mappings mappings) {
		Workspace workspace = support.workspace();
		MappingResults results = applierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
		results.apply();
		JsonObject json = new JsonObject();
		Map<String, String> mapped = results.getMappedClasses();
		json.addProperty("classesChanged", mapped.size());
		JsonArray renamed = new JsonArray();
		mapped.entrySet().stream().limit(100).forEach(e -> renamed.add(e.getKey() + " -> " + e.getValue()));
		json.add("renamedClasses", renamed);
		return McpToolResult.json(json);
	}

	@Nonnull
	private JsonObject previewMappings(@Nonnull IntermediateMappings mappings, @Nonnull McpArgs args) {
		List<String> lines = new ArrayList<>();
		mappings.getClasses().forEach((old, mapping) -> lines.add("class " + old + " -> " + mapping.getNewName()));
		mappings.getFields().forEach((owner, list) -> list.forEach(f ->
				lines.add("field " + owner + "." + f.getOldName() + " -> " + f.getNewName())));
		mappings.getMethods().forEach((owner, list) -> list.forEach(m ->
				lines.add("method " + owner + "." + m.getOldName() + m.getDesc() + " -> " + m.getNewName())));
		java.util.Collections.sort(lines);
		return McpSupport.page(lines.stream(), args, 200, com.google.gson.JsonPrimitive::new);
	}

	@Nonnull
	private MappingFileFormat format(@Nonnull String name) {
		MappingFileFormat format = formatManager.createFormatInstance(name);
		if (format == null)
			throw new McpToolException("Unknown mapping format '" + name + "'. Available: " + formatManager.getMappingFileFormats());
		return format;
	}

	@Nonnull
	private WorkspaceResource resource(@Nonnull String label) {
		Workspace workspace = support.workspace();
		for (WorkspaceResource resource : workspace.getAllResources(false)) {
			String actual = support.resourceLabel(resource);
			if (actual.equals(label) || actual.endsWith(":" + label))
				return resource;
		}
		throw new McpToolException("No resource '" + label + "'. Labels: " + workspace.getAllResources(false).stream()
				.map(support::resourceLabel).toList());
	}
}
