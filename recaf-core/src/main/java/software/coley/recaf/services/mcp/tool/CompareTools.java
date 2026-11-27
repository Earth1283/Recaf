package software.coley.recaf.services.mcp.tool;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.compare.JarComparison;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tools for comparing two JAR/ZIP archives on disk and diffing changed entries or members.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class CompareTools implements McpToolProvider {
	private static final int DEFAULT_DIFF_MAX_CHARS = 80_000;
	private static final int INLINE_MEMBER_CAP = 30;
	private final DecompilerManager decompilerManager;

	@Inject
	public CompareTools(@Nonnull DecompilerManager decompilerManager) {
		this.decompilerManager = decompilerManager;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("jar_compare",
				"Compare two JAR/ZIP files on disk (does not require opening them). Reports added, removed and changed " +
						"classes, resources, and changed fields/methods.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("before", "Path to the baseline JAR/ZIP.")
						.requiredString("after", "Path to the updated JAR/ZIP.")
						.bool("strict", "Compare JVM string literals strictly. Default true.")
						.enumeration("kind", "Filter by entry kind. Default all.", List.of("all", "CLASS", "RESOURCE"))
						.enumeration("status", "Filter by change status. Default all.", List.of("all", "ADDED", "REMOVED", "CHANGED"))
						.string("filter", "Only include paths matching this substring (case-insensitive) or 're:<regex>'.")
						.string("exclude", "Exclude paths matching this substring (case-insensitive) or 're:<regex>' (e.g. 'META-INF/maven').")
						.string("package", "Only include classes in this package (e.g. 'com/example' or 'com/example/*').")
						.bool("include_members", "Include changed field/method lists per class (capped at 30). Default true.")
						.paging(),
				this::compare);
		registry.add("jar_compare_diff",
				"Unified diff of one entry (or one field/method) between two JAR/ZIP files, as bytecode trace or decompiled Java source.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("before", "Path to the baseline JAR/ZIP.")
						.requiredString("after", "Path to the updated JAR/ZIP.")
						.requiredString("path", "Archive entry path (e.g. com/example/Foo.class or plugin.yml) or internal class name.")
						.string("member", "Optional field or method name to restrict a bytecode diff to.")
						.string("descriptor", "Member descriptor, needed if multiple changed members share the name.")
						.enumeration("view", "Diff view. Default bytecode.", List.of("bytecode", "source"))
						.string("decompiler", "Decompiler name when view=source. Defaults to the configured one.")
						.integer("context", "Unified diff context lines. Default 3.")
						.integer("max_chars", "Maximum characters to return. Default " + DEFAULT_DIFF_MAX_CHARS + "."),
				this::diff);
	}

	@Nonnull
	private McpToolResult compare(@Nonnull McpArgs args) throws IOException {
		Path before = requireFile(args.requireString("before"));
		Path after = requireFile(args.requireString("after"));
		boolean strict = args.bool("strict", true);
		String kindFilter = args.string("kind", "all");
		String statusFilter = args.string("status", "all");
		Predicate<String> pathFilter = McpSupport.textFilter(args.string("filter", null));
		Predicate<String> excludeFilter = McpSupport.textFilter(args.string("exclude", null));
		String pkgFilter = args.string("package", null);
		boolean includeMembers = args.bool("include_members", true);

		List<JarComparison.EntryChange> allChanges = JarComparison.compare(before, after, strict);
		long added = 0, removed = 0, changed = 0, classes = 0, resources = 0;
		for (JarComparison.EntryChange c : allChanges) {
			switch (c.status()) {
				case ADDED -> added++;
				case REMOVED -> removed++;
				case CHANGED -> changed++;
			}
			if (c.kind() == JarComparison.Kind.CLASS)
				classes++;
			else
				resources++;
		}

		JsonObject json = McpSupport.page(allChanges.stream()
						.filter(c -> "all".equals(kindFilter) || c.kind().name().equals(kindFilter))
						.filter(c -> "all".equals(statusFilter) || c.status().name().equals(statusFilter))
						.filter(c -> pathFilter == null || pathFilter.test(c.path()))
						.filter(c -> excludeFilter == null || !excludeFilter.test(c.path()))
						.filter(c -> {
							if (pkgFilter == null)
								return true;
							if (c.kind() != JarComparison.Kind.CLASS || !c.path().endsWith(".class"))
								return false;
							String className = c.path().substring(0, c.path().length() - 6);
							return McpSupport.matchesPackage(className, true, pkgFilter);
						}),
				args, 100, entry -> {
					JsonObject obj = new JsonObject();
					obj.addProperty("path", entry.path());
					obj.addProperty("kind", entry.kind().name());
					obj.addProperty("status", entry.status().name());
					if (entry.before() != null)
						obj.addProperty("beforeSize", entry.before().size());
					if (entry.after() != null)
						obj.addProperty("afterSize", entry.after().size());
					if (entry.kind() == JarComparison.Kind.CLASS)
						obj.addProperty("memberCount", entry.members().size());
					if (includeMembers && !entry.members().isEmpty()) {
						JsonArray members = new JsonArray();
						for (JarComparison.MemberChange m : entry.members().stream().limit(INLINE_MEMBER_CAP).toList()) {
							JsonObject mj = new JsonObject();
							mj.addProperty("kind", m.kind().name());
							mj.addProperty("name", m.name());
							mj.addProperty("descriptor", m.descriptor());
							mj.addProperty("status", m.status().name());
							members.add(mj);
						}
						obj.add("members", members);
						if (entry.members().size() > INLINE_MEMBER_CAP)
							obj.addProperty("membersTruncated", true);
					}
					return obj;
				});

		JsonObject summary = new JsonObject();
		summary.addProperty("totalChanges", allChanges.size());
		summary.addProperty("added", added);
		summary.addProperty("removed", removed);
		summary.addProperty("changed", changed);
		summary.addProperty("classes", classes);
		summary.addProperty("resources", resources);
		json.add("summary", summary);
		json.addProperty("before", before.toString());
		json.addProperty("after", after.toString());
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult diff(@Nonnull McpArgs args) throws Exception {
		Path before = requireFile(args.requireString("before"));
		Path after = requireFile(args.requireString("after"));
		String rawPath = args.requireString("path").trim();
		String entryPath = resolveEntryPath(before, after, rawPath);
		String view = args.string("view", "bytecode");
		int context = Math.max(0, args.integer("context", 3));
		int maxChars = Math.max(1, args.integer("max_chars", DEFAULT_DIFF_MAX_CHARS));

		if ("source".equals(view)) {
			if (!entryPath.endsWith(".class"))
				throw new McpToolException("view='source' only applies to .class entries (got '" + entryPath + "')");
			String decompilerName = args.string("decompiler", null);
			JvmDecompiler decompiler = decompilerName == null ? decompilerManager.getTargetJvmDecompiler()
					: decompilerManager.getJvmDecompiler(decompilerName);
			if (decompiler == null)
				throw new McpToolException("Unknown decompiler '" + decompilerName + "'. See decompiler_list.");
			List<String> left = decompileFromZip(before, entryPath, decompiler);
			List<String> right = decompileFromZip(after, entryPath, decompiler);
			return unifiedDiffResult(entryPath, before, after, view, left, right, context, maxChars);
		}
		if (!"bytecode".equals(view))
			throw new McpToolException("Unknown view '" + view + "'");

		List<JarComparison.EntryChange> changes = JarComparison.compare(before, after, true);
		JarComparison.EntryChange change = changes.stream()
				.filter(c -> c.path().equals(entryPath))
				.findFirst()
				.orElse(null);
		if (change == null) {
			JsonObject json = McpSupport.bounded("", maxChars);
			json.addProperty("path", entryPath);
			json.addProperty("before", before.toString());
			json.addProperty("after", after.toString());
			json.addProperty("view", view);
			json.addProperty("identical", true);
			return McpToolResult.json(json);
		}

		JarComparison.MemberChange memberChange = null;
		String memberName = args.string("member", null);
		if (memberName != null) {
			if (change.kind() != JarComparison.Kind.CLASS)
				throw new McpToolException("'member' only applies to class entries");
			String desc = args.string("descriptor", null);
			List<JarComparison.MemberChange> matches = change.members().stream()
					.filter(m -> m.name().equals(memberName) && (desc == null || m.descriptor().equals(desc)))
					.toList();
			if (matches.isEmpty()) {
				throw new McpToolException("No changed member '" + memberName + (desc == null ? "" : desc) +
						"' in " + entryPath + ". Changed members: " +
						change.members().stream().map(m -> m.name() + m.descriptor()).toList());
			}
			if (matches.size() > 1) {
				throw new McpToolException("Multiple changed members named '" + memberName +
						"', pass 'descriptor'. Candidates: " +
						matches.stream().map(JarComparison.MemberChange::descriptor).toList());
			}
			memberChange = matches.getFirst();
		}

		JarComparison.Preview leftPreview = JarComparison.preview(before, change, memberChange, true);
		JarComparison.Preview rightPreview = JarComparison.preview(after, change, memberChange, false);
		if ((leftPreview != null && leftPreview.text() == null) || (rightPreview != null && rightPreview.text() == null)) {
			JsonObject json = new JsonObject();
			json.addProperty("path", entryPath);
			json.addProperty("before", before.toString());
			json.addProperty("after", after.toString());
			json.addProperty("view", view);
			json.addProperty("identical", false);
			json.addProperty("binary", true);
			if (change.before() != null)
				json.addProperty("beforeSize", change.before().size());
			if (change.after() != null)
				json.addProperty("afterSize", change.after().size());
			return McpToolResult.json(json);
		}

		List<String> left = leftPreview == null ? List.of() : leftPreview.text().lines().toList();
		List<String> right = rightPreview == null ? List.of() : rightPreview.text().lines().toList();
		String label = memberChange == null ? entryPath : entryPath + "#" + memberChange.name() + memberChange.descriptor();
		return unifiedDiffResult(label, before, after, view, left, right, context, maxChars);
	}

	@Nonnull
	private McpToolResult unifiedDiffResult(@Nonnull String label, @Nonnull Path before, @Nonnull Path after,
	                                        @Nonnull String view, @Nonnull List<String> left, @Nonnull List<String> right,
	                                        int context, int maxChars) {
		Patch<String> patch = DiffUtils.diff(left, right);
		List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("before/" + label, "after/" + label,
				left, patch, context);
		JsonObject json = McpSupport.bounded(String.join("\n", unified), maxChars);
		json.addProperty("path", label);
		json.addProperty("before", before.toString());
		json.addProperty("after", after.toString());
		json.addProperty("view", view);
		json.addProperty("identical", patch.getDeltas().isEmpty());
		return McpToolResult.json(json);
	}

	@Nonnull
	private List<String> decompileFromZip(@Nonnull Path jar, @Nonnull String entryPath,
	                                      @Nonnull JvmDecompiler decompiler) throws Exception {
		BasicJvmClassBundle bundle = new BasicJvmClassBundle();
		String targetInternal = entryPath.substring(0, entryPath.length() - 6);
		String innerPrefix = targetInternal + "$";
		JvmClassInfo targetInfo = null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry direct = zip.getEntry(entryPath);
			if (direct != null) {
				targetInfo = new JvmClassInfoBuilder(readEntry(zip, direct)).build();
				bundle.initialPut(targetInfo);
				var entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();
					if (!e.isDirectory() && e.getName().startsWith(innerPrefix) && e.getName().endsWith(".class")) {
						try {
							bundle.initialPut(new JvmClassInfoBuilder(readEntry(zip, e)).build());
						} catch (RuntimeException ignored) {
						}
					}
				}
			}
		}
		if (targetInfo == null)
			return List.of();
		WorkspaceResource resource = new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build();
		Workspace temp = new BasicWorkspace(resource);
		try {
			DecompileResult result = decompilerManager.decompile(decompiler, temp, targetInfo).get(2, TimeUnit.MINUTES);
			if (result.getType() != DecompileResult.ResultType.SUCCESS || result.getText() == null)
				throw new McpToolException("Could not decompile " + targetInfo.getName() + " from " + jar +
						(result.getException() == null ? "" : ": " + result.getException()));
			return result.getText().lines().toList();
		} catch (TimeoutException ex) {
			throw new McpToolException("Decompilation of " + targetInfo.getName() + " timed out");
		} catch (ExecutionException ex) {
			throw new McpToolException("Decompilation of " + targetInfo.getName() + " failed: " + ex.getCause());
		} finally {
			temp.close();
		}
	}

	@Nonnull
	private static byte[] readEntry(@Nonnull ZipFile zip, @Nonnull ZipEntry entry) throws IOException {
		try (InputStream stream = zip.getInputStream(entry)) {
			return stream.readAllBytes();
		}
	}

	@Nonnull
	private static String resolveEntryPath(@Nonnull Path before, @Nonnull Path after, @Nonnull String rawPath) throws IOException {
		if (zipContains(before, rawPath) || zipContains(after, rawPath))
			return rawPath;
		if (!rawPath.endsWith(".class")) {
			String asClass = McpSupport.normalizeClassName(rawPath) + ".class";
			if (zipContains(before, asClass) || zipContains(after, asClass))
				return asClass;
		}
		throw new McpToolException("Entry '" + rawPath + "' was not found in either archive");
	}

	private static boolean zipContains(@Nonnull Path jar, @Nonnull String entryPath) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getEntry(entryPath) != null;
		}
	}

	@Nonnull
	private static Path requireFile(@Nonnull String pathText) {
		Path path = Path.of(pathText);
		if (!Files.isRegularFile(path))
			throw new McpToolException("No such archive file: " + path);
		return path;
	}
}
