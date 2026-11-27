package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.analysis.bytecode.ReadableWorkspaceService;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.services.workspace.io.WorkspaceCompressType;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tools for opening, inspecting and exporting workspaces.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class WorkspaceTools implements McpToolProvider {
	private final McpSupport support;
	private final ResourceImporter importer;
	private final ReadableWorkspaceService readableWorkspaceService;

	@Inject
	public WorkspaceTools(@Nonnull McpSupport support, @Nonnull ResourceImporter importer,
	                      @Nonnull ReadableWorkspaceService readableWorkspaceService) {
		this.support = support;
		this.importer = importer;
		this.readableWorkspaceService = readableWorkspaceService;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("workspace_open",
				"Open a jar, zip, class, apk, dex, jmod or directory as the current workspace, replacing any open one. " +
						"Optional library paths are added as supporting resources so inheritance and decompilation can resolve them.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("path", "Path of the file or directory to open.")
						.stringArray("libraries", "Paths of supporting jars or directories to add.")
						.bool("force", "Replace the current workspace even if something blocks closing it."),
				this::open);
		registry.add("workspace_add_library",
				"Add a jar or directory to the current workspace as a supporting (library) resource.",
				McpAccess.WRITE,
				McpToolSchema.create().requiredString("path", "Path of the library."),
				this::addLibrary);
		registry.add("workspace_info",
				"Summarize the current workspace: its primary resource, libraries, embedded archives and content counts.",
				McpAccess.READ, McpToolSchema.create(), this::info);
		registry.add("workspace_close", "Close the current workspace.", McpAccess.WRITE,
				McpToolSchema.create().bool("force", "Close even if something blocks closing it."), this::close);
		registry.add("workspace_export",
				"Write the current workspace, including all edits, to a file (or a directory tree).",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("path", "Destination path.")
						.bool("directory", "Write a directory tree instead of an archive.")
						.enumeration("compression", "Entry compression. Default SMART.",
								List.of("MATCH_ORIGINAL", "SMART", "ALWAYS", "NEVER"))
						.bool("bundle_libraries", "Include supporting resources in the output."),
				this::export);
		registry.add("workspace_readable_copy",
				"Build an isolated readable analysis copy of the current workspace (folding constants, decoding literals, " +
						"simplifying control flow, and creating synthetic readable aliases for illegal names) while keeping " +
						"original classes as a supporting resource.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.integer("max_steps", "Evaluator instruction budget (100..1000000). Default 50000.")
						.bool("aliases", "Create synthetic readable aliases for non-ASCII/illegal names. Default true.")
						.string("resource", "Resource label or embedded path to copy. Defaults to the primary resource.")
						.bool("force", "Replace the current workspace even if something blocks closing it."),
				this::readableCopy);
	}

	@Nonnull
	private McpToolResult open(@Nonnull McpArgs args) throws IOException {
		Path path = Path.of(args.requireString("path"));
		if (!Files.exists(path))
			throw new McpToolException("No such file or directory: " + path);
		WorkspaceResource primary = importer.importResource(path);
		List<WorkspaceResource> libraries = new ArrayList<>();
		for (String library : args.strings("libraries")) {
			Path libraryPath = Path.of(library);
			if (!Files.exists(libraryPath))
				throw new McpToolException("No such library: " + libraryPath);
			libraries.add(importer.importResource(libraryPath));
		}
		Workspace workspace = new BasicWorkspace(primary, libraries);
		if (args.bool("force", false)) {
			support.workspaceManager().setCurrentIgnoringConditions(workspace);
		} else if (!support.workspaceManager().setCurrent(workspace)) {
			throw new McpToolException("The current workspace could not be closed, something blocked it " +
					"(for example unsaved changes in the UI). Retry with force=true to replace it.");
		}
		return McpToolResult.json(describe(workspace));
	}

	@Nonnull
	private McpToolResult addLibrary(@Nonnull McpArgs args) throws IOException {
		Workspace workspace = support.workspace();
		Path path = Path.of(args.requireString("path"));
		if (!Files.exists(path))
			throw new McpToolException("No such file or directory: " + path);
		workspace.addSupportingResource(importer.importResource(path));
		return McpToolResult.json(describe(workspace));
	}

	@Nonnull
	private McpToolResult info(@Nonnull McpArgs args) {
		return McpToolResult.json(describe(support.workspace()));
	}

	@Nonnull
	private McpToolResult close(@Nonnull McpArgs args) {
		if (!support.workspaceManager().hasCurrentWorkspace())
			return McpToolResult.text("No workspace was open.");
		if (args.bool("force", false)) {
			support.workspaceManager().setCurrentIgnoringConditions(null);
		} else if (!support.workspaceManager().setCurrent(null)) {
			throw new McpToolException("Something blocked closing the workspace. Retry with force=true.");
		}
		return McpToolResult.text("Closed.");
	}

	@Nonnull
	private McpToolResult export(@Nonnull McpArgs args) throws IOException {
		Workspace workspace = support.workspace();
		Path path = Path.of(args.requireString("path")).toAbsolutePath();
		boolean directory = args.bool("directory", false);
		WorkspaceCompressType compression;
		try {
			compression = WorkspaceCompressType.valueOf(args.string("compression", "SMART").toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new McpToolException("Unknown compression '" + args.string("compression", "") + "'");
		}
		if (path.getParent() != null)
			Files.createDirectories(path.getParent());
		try (PathWorkspaceExportConsumer consumer = new PathWorkspaceExportConsumer(path,
				directory ? PathWorkspaceExportConsumer.Mode.MIRROR_DIRECTORY : PathWorkspaceExportConsumer.Mode.ATOMIC_FILE)) {
			WorkspaceExportOptions options = new WorkspaceExportOptions(compression,
					directory ? WorkspaceOutputType.DIRECTORY : WorkspaceOutputType.FILE, consumer);
			options.setBundleSupporting(args.bool("bundle_libraries", false));
			options.create().export(workspace);
		}
		JsonObject json = new JsonObject();
		json.addProperty("written", path.toString());
		if (!directory)
			json.addProperty("bytes", Files.size(path));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult readableCopy(@Nonnull McpArgs args) throws TransformationException {
		Workspace workspace = support.workspace();
		WorkspaceResource selected = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: workspace.getPrimaryResource();
		int maxSteps = Math.max(100, Math.min(1_000_000, args.integer("max_steps", 50_000)));
		boolean aliases = args.bool("aliases", true);
		ReadableWorkspaceService.Result result = readableWorkspaceService.build(
				workspace, selected, maxSteps, aliases, () -> false, msg -> {});
		Workspace copy = result.workspace();
		if (args.bool("force", false)) {
			support.workspaceManager().setCurrentIgnoringConditions(copy);
		} else if (!support.workspaceManager().setCurrent(copy)) {
			copy.close();
			throw new McpToolException("The current workspace could not be closed, something blocked it " +
					"(for example unsaved changes in the UI). Retry with force=true to replace it.");
		}
		JsonObject json = new JsonObject();
		json.addProperty("dynamicStringsBefore", result.dynamicStringsBefore());
		json.addProperty("dynamicStringsAfter", result.dynamicStringsAfter());
		json.addProperty("helperCallsBefore", result.helperCallsBefore());
		json.addProperty("helperCallsAfter", result.helperCallsAfter());
		json.addProperty("classesAliased", result.classAliases().size());
		json.addProperty("report", result.report());
		json.add("workspace", describe(copy));
		return McpToolResult.json(json);
	}

	@Nonnull
	private JsonObject describe(@Nonnull Workspace workspace) {
		JsonObject json = new JsonObject();
		json.add("primary", describeResource(workspace.getPrimaryResource()));
		JsonArray libraries = new JsonArray();
		for (WorkspaceResource library : workspace.getSupportingResources())
			libraries.add(describeResource(library));
		json.add("libraries", libraries);
		json.addProperty("totalClasses", workspace.classesStream(false).count());
		json.addProperty("totalFiles", workspace.filesStream().count());
		return json;
	}

	@Nonnull
	private JsonObject describeResource(@Nonnull WorkspaceResource resource) {
		JsonObject json = new JsonObject();
		json.addProperty("label", support.resourceLabel(resource));
		json.addProperty("type", resource.getClass().getSimpleName());
		json.addProperty("jvmClasses", resource.getJvmClassBundle().size());
		json.addProperty("versionedJvmClasses", resource.getVersionedJvmClassBundles().values().stream()
				.mapToInt(java.util.Map::size).sum());
		json.addProperty("androidClasses", resource.getAndroidClassBundles().values().stream()
				.mapToInt(java.util.Map::size).sum());
		json.addProperty("files", resource.getFileBundle().size());
		JsonArray embedded = new JsonArray();
		resource.getEmbeddedResources().keySet().forEach(embedded::add);
		json.add("embeddedResources", embedded);
		return json;
	}
}
