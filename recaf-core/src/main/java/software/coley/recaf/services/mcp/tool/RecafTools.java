package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.RecafBuildConfig;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.comment.ClassComments;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.mcp.McpValues;
import software.coley.recaf.services.phantom.GeneratedPhantomWorkspaceResource;
import software.coley.recaf.services.phantom.PhantomGenerationException;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.plugin.PluginContainer;
import software.coley.recaf.services.plugin.PluginManager;
import software.coley.recaf.workspace.model.Workspace;

/**
 * Tools for Recaf itself: version, plugins, comments and phantom class generation.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class RecafTools implements McpToolProvider {
	private final McpSupport support;
	private final PluginManager pluginManager;
	private final CommentManager commentManager;
	private final PhantomGenerator phantomGenerator;

	@Inject
	public RecafTools(@Nonnull McpSupport support, @Nonnull PluginManager pluginManager,
	                  @Nonnull CommentManager commentManager, @Nonnull PhantomGenerator phantomGenerator) {
		this.support = support;
		this.pluginManager = pluginManager;
		this.commentManager = commentManager;
		this.phantomGenerator = phantomGenerator;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("recaf_info", "Recaf version and build.", McpAccess.READ, McpToolSchema.create(), args -> {
			JsonObject json = new JsonObject();
			json.addProperty("version", RecafBuildConfig.VERSION);
			json.addProperty("gitSha", RecafBuildConfig.GIT_SHA);
			json.addProperty("gitBranch", RecafBuildConfig.GIT_BRANCH);
			json.addProperty("java", System.getProperty("java.version"));
			return McpToolResult.json(json);
		});
		registry.add("recaf_plugins", "Recaf plugins that are loaded.", McpAccess.READ, McpToolSchema.create(), args -> {
			JsonArray array = new JsonArray();
			for (PluginContainer<?> plugin : pluginManager.getPlugins())
				array.add(McpValues.toJson(plugin.info()));
			return McpToolResult.json(array);
		});
		registry.add("comment_get",
				"Read the comments attached to a class, and to its fields and methods.",
				McpAccess.READ,
				McpToolSchema.create().requiredString("class", "Class name."), this::getComments);
		registry.add("comment_set",
				"Set or clear (empty text) a comment on a class, field or method. Comments show in decompiled output.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("class", "Class name.")
						.string("member", "Field or method name. Omit to comment the class itself.")
						.string("descriptor", "Member descriptor.")
						.requiredString("text", "Comment text. Empty removes it."),
				this::setComment);
		registry.add("phantom_generate",
				"Generate stub classes for everything the workspace references but does not contain, so that " +
						"decompilation and recompilation can resolve types. Adds them as a library resource.",
				McpAccess.WRITE, McpToolSchema.create(), this::phantoms);
	}

	@Nonnull
	private McpToolResult getComments(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		ClassPathNode path = support.requireClass(args.requireString("class"));
		ClassComments comments = commentManager.getOrCreateWorkspaceComments(workspace).getClassComments(path);
		JsonObject json = new JsonObject();
		json.addProperty("class", path.getValue().getName());
		if (comments == null || !comments.hasComments())
			return McpToolResult.json(json);
		if (comments.getClassComment() != null)
			json.addProperty("comment", comments.getClassComment());
		JsonArray members = new JsonArray();
		ClassInfo info = path.getValue();
		for (ClassMember member : (Iterable<ClassMember>) info.fieldAndMethodStream()::iterator) {
			String comment = member.isMethod() ? comments.getMethodComment(member.getName(), member.getDescriptor()) : comments.getFieldComment(member.getName(), member.getDescriptor());
			if (comment == null)
				continue;
			JsonObject entry = new JsonObject();
			entry.addProperty("name", member.getName());
			entry.addProperty("descriptor", member.getDescriptor());
			entry.addProperty("comment", comment);
			members.add(entry);
		}
		json.add("members", members);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult setComment(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		ClassPathNode path = support.requireClass(args.requireString("class"));
		ClassComments comments = commentManager.getOrCreateWorkspaceComments(workspace).getOrCreateClassComments(path);
		String text = args.requireString("text");
		String value = text.isEmpty() ? null : text;
		String memberName = args.string("member", null);
		if (memberName == null) {
			comments.setClassComment(value);
		} else {
			String descriptor = args.string("descriptor", null);
			ClassMember member = path.getValue().fieldAndMethodStream()
					.filter(m -> m.getName().equals(memberName) && (descriptor == null || m.getDescriptor().equals(descriptor)))
					.findFirst().orElseThrow(() -> new McpToolException("No member '" + memberName + "' in " + path.getValue().getName()));
			if (member.isMethod())
				comments.setMethodComment(member.getName(), member.getDescriptor(), value);
			else
				comments.setFieldComment(member.getName(), member.getDescriptor(), value);
		}
		return McpToolResult.text(value == null ? "Comment removed." : "Comment set.");
	}

	@Nonnull
	private McpToolResult phantoms(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		try {
			GeneratedPhantomWorkspaceResource generated = phantomGenerator.createPhantomsForWorkspace(workspace);
			PhantomGenerator.installGeneratedResourceIfAbsent(workspace, generated);
			JsonObject json = new JsonObject();
			json.addProperty("classesGenerated", generated.getJvmClassBundle().size());
			return McpToolResult.json(json);
		} catch (PhantomGenerationException ex) {
			throw new McpToolException("Phantom generation failed: " + ex.getMessage());
		}
	}
}
