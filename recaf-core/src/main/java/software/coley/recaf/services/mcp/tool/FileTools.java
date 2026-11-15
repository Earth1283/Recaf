package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.workspace.io.InfoImporter;
import software.coley.recaf.util.io.ByteSources;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Tools for the non-class files in a workspace, such as manifests, configs and resources.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class FileTools implements McpToolProvider {
	private static final int DEFAULT_MAX_BYTES = 64 * 1024;
	private final McpSupport support;
	private final InfoImporter infoImporter;

	@Inject
	public FileTools(@Nonnull McpSupport support, @Nonnull InfoImporter infoImporter) {
		this.support = support;
		this.infoImporter = infoImporter;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("file_list",
				"List non-class files, sorted by path. Filter by substring of the path (or 're:<regex>').",
				McpAccess.READ,
				McpToolSchema.create()
						.string("filter", "Substring of the path, case-insensitive. Prefix with 're:' for a regex.")
						.bool("include_libraries", "Also list files in library resources.")
						.paging(),
				this::list);
		registry.add("file_read",
				"Read a file. Text files come back as text, anything else as hex unless a format is given.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("path", "Path of the file in the workspace.")
						.enumeration("format", "Force an encoding. Default: text for text files, hex otherwise.",
								List.of("text", "hex", "base64"))
						.integer("offset", "Byte offset to start at. Default 0.")
						.integer("max_bytes", "Maximum bytes to return. Default " + DEFAULT_MAX_BYTES + "."),
				this::read);
		registry.add("file_write",
				"Create or replace a file in the primary resource. Provide 'text' or 'base64'.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("path", "Path of the file in the workspace.")
						.string("text", "UTF-8 content.")
						.string("base64", "Binary content, base64 encoded."),
				this::write);
		registry.add("file_delete", "Remove a file from the workspace.", McpAccess.WRITE,
				McpToolSchema.create().requiredString("path", "Path of the file in the workspace."), this::delete);
	}

	@Nonnull
	private McpToolResult list(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		boolean libraries = args.bool("include_libraries", false);
		String filter = args.string("filter", null);
		java.util.function.Predicate<String> match = name -> true;
		if (filter != null && filter.startsWith("re:")) {
			java.util.regex.Pattern pattern;
			try {
				pattern = java.util.regex.Pattern.compile(filter.substring(3));
			} catch (RuntimeException ex) {
				throw new McpToolException("Invalid regex: " + ex.getMessage());
			}
			match = name -> pattern.matcher(name).find();
		} else if (filter != null && !filter.isEmpty()) {
			String lower = filter.toLowerCase(Locale.ROOT);
			match = name -> name.toLowerCase(Locale.ROOT).contains(lower);
		}
		java.util.function.Predicate<String> finalMatch = match;
		return McpToolResult.json(McpSupport.page(workspace.filesStream()
				.filter(node -> libraries || !support.isLibrary(node.getValueOfType(WorkspaceResource.class)))
				.filter(node -> finalMatch.test(node.getValue().getName()))
				.sorted(), args, 200, node -> {
			FileInfo info = node.getValue();
			JsonObject json = new JsonObject();
			json.addProperty("path", info.getName());
			json.addProperty("size", info.getRawContent().length);
			json.addProperty("type", info.isTextFile() ? "text" : info.getClass().getSimpleName()
					.replace("Basic", "").replace("FileInfo", "").toLowerCase(Locale.ROOT));
			return json;
		}));
	}

	@Nonnull
	private McpToolResult read(@Nonnull McpArgs args) {
		FileInfo info = requireFile(args.requireString("path"));
		byte[] content = info.getRawContent();
		int offset = Math.min(content.length, args.offset());
		int length = Math.min(content.length - offset, Math.max(1, args.integer("max_bytes", DEFAULT_MAX_BYTES)));
		byte[] slice = Arrays.copyOfRange(content, offset, offset + length);
		String format = args.string("format", info.isTextFile() ? "text" : "hex");
		JsonObject json = new JsonObject();
		json.addProperty("path", info.getName());
		json.addProperty("size", content.length);
		json.addProperty("offset", offset);
		json.addProperty("returnedBytes", length);
		json.addProperty("truncated", offset + length < content.length);
		json.addProperty("format", format);
		json.addProperty("content", switch (format) {
			case "text" -> info instanceof TextFileInfo text && offset == 0 && length == content.length
					? text.getText() : new String(slice, StandardCharsets.UTF_8);
			case "base64" -> Base64.getEncoder().encodeToString(slice);
			case "hex" -> HexFormat.of().formatHex(slice);
			default -> throw new McpToolException("Unknown format '" + format + "'");
		});
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult write(@Nonnull McpArgs args) throws IOException {
		Workspace workspace = support.workspace();
		String path = args.requireString("path");
		byte[] content;
		if (args.has("text")) {
			content = args.requireString("text").getBytes(StandardCharsets.UTF_8);
		} else if (args.has("base64")) {
			try {
				content = Base64.getDecoder().decode(args.requireString("base64"));
			} catch (IllegalArgumentException ex) {
				throw new McpToolException("'base64' is not valid base64");
			}
		} else {
			throw new McpToolException("Provide 'text' or 'base64'");
		}
		FilePathNode existing = workspace.findFile(path);
		FileBundle bundle = existing == null ? workspace.getPrimaryResource().getFileBundle()
				: existing.getValueOfType(FileBundle.class);
		if (bundle == null)
			throw new McpToolException("Could not find the bundle containing '" + path + "'");
		bundle.put((FileInfo) infoImporter.readInfo(path, ByteSources.wrap(content)));
		JsonObject json = new JsonObject();
		json.addProperty("path", path);
		json.addProperty("replaced", existing != null);
		json.addProperty("size", content.length);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult delete(@Nonnull McpArgs args) {
		String path = args.requireString("path");
		FilePathNode node = support.workspace().findFile(path);
		if (node == null)
			throw new McpToolException("No file '" + path + "'");
		FileBundle bundle = node.getValueOfType(FileBundle.class);
		if (bundle == null || bundle.remove(path) == null)
			throw new McpToolException("Could not remove '" + path + "'");
		return McpToolResult.text("Removed " + path);
	}

	@Nonnull
	private FileInfo requireFile(@Nonnull String path) {
		FilePathNode node = support.workspace().findFile(path);
		if (node == null)
			throw new McpToolException("No file '" + path + "'. Use file_list to find paths.");
		return node.getValue();
	}
}
