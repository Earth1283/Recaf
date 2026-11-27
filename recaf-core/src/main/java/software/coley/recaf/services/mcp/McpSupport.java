package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.Type;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lookups and result shaping shared by the tool providers.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class McpSupport {
	private final WorkspaceManager workspaceManager;

	@Inject
	public McpSupport(@Nonnull WorkspaceManager workspaceManager) {
		this.workspaceManager = workspaceManager;
	}

	/**
	 * @return Current workspace.
	 *
	 * @throws McpToolException
	 * 		When no workspace is open.
	 */
	@Nonnull
	public Workspace workspace() {
		if (!workspaceManager.hasCurrentWorkspace())
			throw new McpToolException("No workspace is open. Call workspace_open first.");
		return workspaceManager.getCurrent();
	}

	/**
	 * @return Workspace manager.
	 */
	@Nonnull
	public WorkspaceManager workspaceManager() {
		return workspaceManager;
	}

	/**
	 * @param name
	 * 		Class name in internal or source form, such as {@code com/example/Foo} or {@code com.example.Foo}.
	 *
	 * @return Internal name.
	 */
	@Nonnull
	public static String normalizeClassName(@Nonnull String name) {
		String normalized = name.trim();
		if (normalized.endsWith(".class"))
			normalized = normalized.substring(0, normalized.length() - 6);
		if (normalized.startsWith("L") && normalized.endsWith(";") && normalized.contains("/"))
			normalized = normalized.substring(1, normalized.length() - 1);
		return normalized.replace('.', '/');
	}

	/**
	 * @param name
	 * 		Class name.
	 *
	 * @return Path to the class in the current workspace.
	 *
	 * @throws McpToolException
	 * 		When there is no such class.
	 */
	@Nonnull
	public ClassPathNode requireClass(@Nonnull String name) {
		return requireClass(name, null);
	}

	/**
	 * @param name
	 * 		Class name.
	 * @param resourceLabel
	 * 		Optional resource label or embedded path to restrict lookup to, or {@code null} to search the whole workspace.
	 *
	 * @return Path to the class.
	 *
	 * @throws McpToolException
	 * 		When there is no such class or resource.
	 */
	@Nonnull
	public ClassPathNode requireClass(@Nonnull String name, @Nullable String resourceLabel) {
		Workspace workspace = workspace();
		String normalized = normalizeClassName(name);
		if (resourceLabel == null || resourceLabel.isBlank()) {
			ClassPathNode path = workspace.findClass(false, normalized);
			if (path == null)
				throw new McpToolException(notFoundMessage(workspace, normalized));
			return path;
		}
		WorkspaceResource target = resolveResource(resourceLabel);
		ClassPathNode path = findClassInResource(workspace, target, normalized);
		if (path == null)
			throw new McpToolException("No class named '" + normalized + "' in resource '" + resourceLabel + "'");
		return path;
	}

	/**
	 * @param name
	 * 		Class name.
	 *
	 * @return Class model, which must be a JVM class.
	 *
	 * @throws McpToolException
	 * 		When there is no such class, or it is not a JVM class.
	 */
	@Nonnull
	public JvmClassInfo requireJvmClass(@Nonnull String name) {
		return requireJvmClass(name, null);
	}

	/**
	 * @param name
	 * 		Class name.
	 * @param resourceLabel
	 * 		Optional resource label or embedded path to restrict lookup to.
	 *
	 * @return Class model, which must be a JVM class.
	 *
	 * @throws McpToolException
	 * 		When there is no such class, resource, or it is not a JVM class.
	 */
	@Nonnull
	public JvmClassInfo requireJvmClass(@Nonnull String name, @Nullable String resourceLabel) {
		ClassInfo info = requireClass(name, resourceLabel).getValue();
		if (!info.isJvmClass())
			throw new McpToolException("'" + info.getName() + "' is not a JVM class");
		return info.asJvmClass();
	}

	/**
	 * @param path
	 * 		File path in the workspace.
	 * @param resourceLabel
	 * 		Optional resource label or embedded path to restrict lookup to.
	 *
	 * @return Matching file info.
	 *
	 * @throws McpToolException
	 * 		When no such file or resource exists.
	 */
	@Nonnull
	public FileInfo requireFile(@Nonnull String path, @Nullable String resourceLabel) {
		Workspace workspace = workspace();
		if (resourceLabel == null || resourceLabel.isBlank()) {
			FilePathNode node = workspace.findFile(path);
			if (node == null)
				throw new McpToolException("No file '" + path + "'. Use file_list to find paths.");
			return node.getValue();
		}
		WorkspaceResource target = resolveResource(resourceLabel);
		FileInfo file = target.getFileBundle().get(path);
		if (file == null)
			throw new McpToolException("No file '" + path + "' in resource '" + resourceLabel + "'");
		return file;
	}

	/**
	 * Builds a text filter supporting case-insensitive substring or {@code re:<regex>}.
	 *
	 * @param filter
	 * 		Filter expression.
	 *
	 * @return Predicate, or {@code null} when filter is null or empty.
	 */
	@Nullable
	public static Predicate<String> textFilter(@Nullable String filter) {
		if (filter == null || filter.isEmpty())
			return null;
		if (filter.startsWith("re:")) {
			Pattern pattern;
			try {
				pattern = Pattern.compile(filter.substring(3));
			} catch (RuntimeException ex) {
				throw new McpToolException("Invalid regex: " + ex.getMessage());
			}
			return text -> pattern.matcher(text).find();
		}
		String lower = filter.toLowerCase(Locale.ROOT);
		return text -> text.toLowerCase(Locale.ROOT).contains(lower);
	}

	/**
	 * Builds a class name filter. When {@code filter} is not a regex and matches an exact class name in the current
	 * workspace, it matches only that exact class; otherwise it performs a case-insensitive substring match.
	 *
	 * @param filter
	 * 		Filter expression or exact class name.
	 *
	 * @return Predicate, or {@code null} when filter is null or empty.
	 */
	@Nullable
	public Predicate<String> classNameFilter(@Nullable String filter) {
		if (filter == null || filter.isEmpty())
			return null;
		if (!filter.startsWith("re:")) {
			String normalized = normalizeClassName(filter);
			if (workspace().findClass(false, normalized) != null)
				return name -> name.equals(normalized);
		}
		return textFilter(filter);
	}

	/**
	 * @param classPath
	 * 		Owning class path.
	 * @param methodName
	 * 		Method name.
	 * @param descriptor
	 * 		Optional method descriptor when overloaded.
	 *
	 * @return Path to the matching method.
	 *
	 * @throws McpToolException
	 * 		When no method matches or multiple overloads match without a descriptor.
	 */
	@Nonnull
	public ClassMemberPathNode requireMethod(@Nonnull ClassPathNode classPath, @Nonnull String methodName,
	                                         @Nullable String descriptor) {
		ClassInfo owner = classPath.getValue();
		List<MethodMember> candidates = owner.getMethods().stream()
				.filter(m -> m.getName().equals(methodName) && (descriptor == null || m.getDescriptor().equals(descriptor)))
				.toList();
		if (candidates.isEmpty())
			throw new McpToolException("No method '" + methodName + (descriptor == null ? "" : descriptor) +
					"' in " + owner.getName());
		if (candidates.size() > 1)
			throw new McpToolException("'" + methodName + "' is overloaded in " + owner.getName() +
					", pass 'descriptor'. Candidates: " +
					candidates.stream().map(MethodMember::getDescriptor).toList());
		return classPath.child(candidates.getFirst());
	}

	/**
	 * Resolves a primary, library, or embedded resource by label or embedded path.
	 *
	 * @param label
	 * 		Resource label (such as {@code primary:app.jar}, {@code library:lib.jar}, {@code app.jar},
	 * 		or an embedded archive path).
	 *
	 * @return Matching resource.
	 *
	 * @throws McpToolException
	 * 		When no resource matches.
	 */
	@Nonnull
	public WorkspaceResource resolveResource(@Nonnull String label) {
		Workspace workspace = workspace();
		List<String> available = new ArrayList<>();
		Set<WorkspaceResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Queue<Map.Entry<WorkspaceResource, String>> queue = new ArrayDeque<>();
		for (WorkspaceResource root : workspace.getAllResources(false))
			queue.add(Map.entry(root, resourceLabel(root)));
		while (!queue.isEmpty()) {
			var entry = queue.poll();
			WorkspaceResource resource = entry.getKey();
			if (!seen.add(resource))
				continue;
			String name = entry.getValue();
			String resLabel = resourceLabel(resource);
			available.add(name);
			if (resLabel.equals(label) || resLabel.endsWith(":" + label) || name.equals(label) || name.endsWith(":" + label)
					|| name.endsWith("/" + label) || name.endsWith(" > " + label))
				return resource;
			resource.getEmbeddedResources().forEach((embeddedName, embeddedResource) ->
					queue.add(Map.entry(embeddedResource, embeddedName)));
		}
		throw new McpToolException("No resource '" + label + "'. Available: " + available);
	}

	@Nullable
	private static ClassPathNode findClassInResource(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                                   @Nonnull String normalizedName) {
		for (JvmClassBundle bundle : resource.jvmClassBundles()) {
			JvmClassInfo info = bundle.get(normalizedName);
			if (info != null)
				return PathNodes.classPath(workspace, resource, bundle, info);
		}
		for (VersionedJvmClassBundle versioned : resource.getVersionedJvmClassBundles().descendingMap().values()) {
			JvmClassInfo info = versioned.get(normalizedName);
			if (info != null)
				return PathNodes.classPath(workspace, resource, versioned, info);
		}
		for (AndroidClassBundle android : resource.getAndroidClassBundles().values()) {
			var info = android.get(normalizedName);
			if (info != null)
				return PathNodes.classPath(workspace, resource, android, info);
		}
		return null;
	}

	/**
	 * @param classOrPackageName
	 * 		Internal class name or package name.
	 * @param isClassName
	 *        {@code true} when {@code classOrPackageName} is a class name rather than a package name.
	 * @param pkg
	 * 		Package filter (e.g. {@code com/example} or {@code com/example/*}).
	 *
	 * @return {@code true} when the class or package matches {@code pkg}.
	 */
	public static boolean matchesPackage(@Nonnull String classOrPackageName, boolean isClassName, @Nonnull String pkg) {
		String actual = classOrPackageName;
		if (isClassName) {
			int slash = classOrPackageName.lastIndexOf('/');
			actual = slash < 0 ? "" : classOrPackageName.substring(0, slash);
		}
		String normalizedPkg = normalizeClassName(pkg);
		if (normalizedPkg.endsWith("/*")) {
			String prefix = normalizedPkg.substring(0, normalizedPkg.length() - 2);
			return actual.equals(prefix) || actual.startsWith(prefix + "/");
		}
		return actual.equals(normalizedPkg.endsWith("/") ? normalizedPkg.substring(0, normalizedPkg.length() - 1) : normalizedPkg);
	}

	/**
	 * Replaces a class in whichever bundle it came from.
	 *
	 * @param path
	 * 		Path to the existing class.
	 * @param replacement
	 * 		New class model.
	 */
	@SuppressWarnings("unchecked")
	public void replaceClass(@Nonnull ClassPathNode path, @Nonnull ClassInfo replacement) {
		Bundle<ClassInfo> bundle = path.getValueOfType(Bundle.class);
		if (bundle == null)
			throw new McpToolException("Could not find the bundle containing '" + path.getValue().getName() + "'");
		bundle.put(replacement);
	}

	/**
	 * @param resource
	 * 		Resource in the current workspace.
	 *
	 * @return Short label for the resource.
	 */
	@Nonnull
	public String resourceLabel(@Nonnull WorkspaceResource resource) {
		Workspace workspace = workspace();
		String kind = resource == workspace.getPrimaryResource() ? "primary"
				: workspace.getSupportingResources().contains(resource) ? "library" : "embedded";
		if (resource instanceof WorkspaceFileResource file)
			return kind + ":" + file.getFileInfo().getName();
		return kind;
	}

	/**
	 * @param resource
	 * 		Resource in the current workspace.
	 *
	 * @return {@code true} when the resource is a library, or embedded in one, rather than part of the primary input.
	 */
	public boolean isLibrary(@Nonnull WorkspaceResource resource) {
		Workspace workspace = workspace();
		for (WorkspaceResource current = resource; current != null; current = current.getContainingResource())
			if (workspace.getSupportingResources().contains(current))
				return true;
		return false;
	}

	/**
	 * @param path
	 * 		Path to some workspace content, such as a search result location.
	 *
	 * @return JSON describing where in the workspace the path points.
	 */
	@Nonnull
	public static JsonObject describePath(@Nonnull software.coley.recaf.path.PathNode<?> path) {
		JsonObject json = new JsonObject();
		ClassInfo info = path.getValueOfType(ClassInfo.class);
		if (info != null)
			json.addProperty("class", info.getName());
		ClassMember member = path.getValueOfType(ClassMember.class);
		if (member != null) {
			json.addProperty("member", member.getName());
			json.addProperty("descriptor", member.getDescriptor());
		}
		software.coley.recaf.info.FileInfo file = path.getValueOfType(software.coley.recaf.info.FileInfo.class);
		if (file != null && info == null)
			json.addProperty("file", file.getName());
		if (path instanceof software.coley.recaf.path.JvmInstructionPathNode insn)
			json.addProperty("instruction", insn.getInstructionIndex());
		return json;
	}

	/**
	 * @param diagnostics
	 * 		Assembler or compiler diagnostics.
	 *
	 * @return JSON array of {@code "line:column message"} strings.
	 */
	@Nonnull
	public static JsonArray diagnostics(@Nonnull Iterable<? extends me.darknet.assembler.error.Diagnostic> diagnostics) {
		JsonArray array = new JsonArray();
		for (me.darknet.assembler.error.Diagnostic diagnostic : diagnostics) {
			var location = diagnostic.location();
			array.add((location == null ? "" : location.line() + ":" + location.column() + " ") + diagnostic.message());
		}
		return array;
	}

	/**
	 * @param type
	 * 		Kind of element the flags belong to.
	 * @param access
	 * 		Access mask.
	 *
	 * @return Flags as source-like text, such as {@code public static final}.
	 */
	@Nonnull
	public static String flags(@Nonnull AccessFlag.Type type, int access) {
		return AccessFlag.sortAndToString(type, access);
	}

	/**
	 * @param info
	 * 		Class model.
	 *
	 * @return Kind of class: {@code class}, {@code interface}, {@code enum}, {@code annotation} or {@code record}.
	 */
	@Nonnull
	public static String kind(@Nonnull ClassInfo info) {
		if (info.hasAnnotationModifier()) return "annotation";
		if (info.hasInterfaceModifier()) return "interface";
		if (info.hasEnumModifier()) return "enum";
		if (info.hasModuleModifier()) return "module";
		if ("java/lang/Record".equals(info.getSuperName())) return "record";
		return "class";
	}

	/**
	 * @param member
	 * 		Field or method.
	 *
	 * @return JSON summary.
	 */
	@Nonnull
	public static JsonObject describeMember(@Nonnull ClassMember member) {
		JsonObject json = new JsonObject();
		json.addProperty("name", member.getName());
		json.addProperty("descriptor", member.getDescriptor());
		json.addProperty("flags", flags(member.isMethod() ? AccessFlag.Type.METHOD : AccessFlag.Type.FIELD,
				member.getAccess()));
		if (member.getSignature() != null)
			json.addProperty("signature", member.getSignature());
		return json;
	}

	/**
	 * @param descriptor
	 * 		Method descriptor.
	 *
	 * @return Human readable form, such as {@code (int, String) -> void}.
	 */
	@Nonnull
	public static String prettyMethod(@Nonnull String descriptor) {
		Type type = Type.getMethodType(descriptor);
		StringBuilder sb = new StringBuilder("(");
		Type[] args = type.getArgumentTypes();
		for (int i = 0; i < args.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(args[i].getClassName());
		}
		return sb.append(") -> ").append(type.getReturnType().getClassName()).toString();
	}

	/**
	 * @param items
	 * 		Items to page. Only those inside the requested window are mapped.
	 * @param args
	 * 		Arguments carrying {@code offset} and {@code limit}.
	 * @param defaultLimit
	 * 		Page size when the client did not pass a limit.
	 * @param mapper
	 * 		Item to JSON.
	 * @param <T>
	 * 		Item type.
	 *
	 * @return Object with the window of items and the total count.
	 */
	@Nonnull
	public static <T> JsonObject page(@Nonnull Stream<T> items, @Nonnull McpArgs args, int defaultLimit,
	                                  @Nonnull Function<T, JsonElement> mapper) {
		int offset = args.offset();
		int limit = args.limit(defaultLimit);
		JsonArray array = new JsonArray();
		int total = 0;
		try (items) {
			Iterator<T> iterator = items.iterator();
			while (iterator.hasNext()) {
				T item = iterator.next();
				if (total >= offset && array.size() < limit)
					array.add(mapper.apply(item));
				total++;
			}
		}
		JsonObject json = new JsonObject();
		json.addProperty("total", total);
		json.addProperty("offset", offset);
		json.addProperty("returned", array.size());
		json.addProperty("truncated", offset + array.size() < total);
		json.add("items", array);
		return json;
	}

	/**
	 * @param text
	 * 		Text to bound.
	 * @param maxChars
	 * 		Maximum length.
	 *
	 * @return Object holding the possibly shortened text and whether it was cut.
	 */
	@Nonnull
	public static JsonObject bounded(@Nonnull String text, int maxChars) {
		JsonObject json = new JsonObject();
		boolean cut = text.length() > maxChars;
		json.addProperty("truncated", cut);
		json.addProperty("totalChars", text.length());
		json.addProperty("text", cut ? text.substring(0, maxChars) : text);
		return json;
	}

	/**
	 * Slices text by 1-based {@code start_line}, {@code max_lines}, {@code max_chars}, and {@code line_numbers}.
	 *
	 * @param text
	 * 		Full text to window.
	 * @param args
	 * 		Arguments carrying optional line/character paging options.
	 * @param defaultMaxChars
	 * 		Default character cap when {@code max_chars} is omitted.
	 *
	 * @return JSON object with {@code truncated}, {@code totalChars}, {@code totalLines},
	 * {@code startLine}, {@code endLine}, and {@code text}.
	 */
	@Nonnull
	public static JsonObject boundedLines(@Nonnull String text, @Nonnull McpArgs args, int defaultMaxChars) {
		int startLine = Math.max(1, args.integer("start_line", 1));
		boolean hasMaxLines = args.has("max_lines");
		int maxLines = hasMaxLines ? Math.max(1, args.integer("max_lines", Integer.MAX_VALUE)) : Integer.MAX_VALUE;
		int maxChars = Math.max(1, args.integer("max_chars", defaultMaxChars));
		boolean lineNumbers = args.bool("line_numbers", false);

		List<String> lines = text.lines().toList();
		int totalLines = lines.size();
		if (totalLines == 0 || startLine > totalLines) {
			JsonObject json = new JsonObject();
			json.addProperty("truncated", false);
			json.addProperty("totalChars", text.length());
			json.addProperty("totalLines", totalLines);
			json.addProperty("startLine", startLine);
			json.addProperty("endLine", Math.min(totalLines, startLine - 1));
			json.addProperty("text", "");
			return json;
		}

		int firstIdx = startLine - 1;
		int limitIdx = (int) Math.min(totalLines, (long) firstIdx + maxLines);
		StringBuilder sb = new StringBuilder();
		int endLine = firstIdx;
		boolean charCut = false;
		for (int i = firstIdx; i < limitIdx; i++) {
			String rendered = lineNumbers ? (i + 1) + ": " + lines.get(i) : lines.get(i);
			int needed = (sb.isEmpty() ? 0 : 1) + rendered.length();
			if (sb.length() + needed > maxChars) {
				if (sb.isEmpty()) {
					sb.append(rendered, 0, Math.min(rendered.length(), maxChars));
					endLine = i + 1;
				}
				charCut = true;
				break;
			}
			if (!sb.isEmpty())
				sb.append('\n');
			sb.append(rendered);
			endLine = i + 1;
		}

		JsonObject json = new JsonObject();
		json.addProperty("truncated", charCut || endLine < totalLines);
		json.addProperty("totalChars", text.length());
		json.addProperty("totalLines", totalLines);
		json.addProperty("startLine", startLine);
		json.addProperty("endLine", endLine);
		json.addProperty("text", sb.toString());
		return json;
	}

	/**
	 * Searches text line-by-line for {@code grep} (substring or {@code re:<regex>}) and returns merged hunks with
	 * {@code context} surrounding lines.
	 *
	 * @param text
	 * 		Full text to search.
	 * @param args
	 * 		Arguments carrying {@code grep}, optional {@code context} (default 3), {@code max_chars}, and
	 *        {@code line_numbers} (default true).
	 * @param defaultMaxChars
	 * 		Default character cap when {@code max_chars} is omitted.
	 *
	 * @return JSON object with {@code grep}, {@code matches}, {@code hunks}, {@code totalLines}, {@code totalChars},
	 * {@code truncated}, and {@code text}.
	 */
	@Nonnull
	public static JsonObject grepLines(@Nonnull String text, @Nonnull McpArgs args, int defaultMaxChars) {
		String query = args.requireString("grep");
		if (query.isEmpty())
			throw new McpToolException("'grep' must not be empty");
		Predicate<String> matcher = Objects.requireNonNull(textFilter(query));
		int context = Math.max(0, Math.min(50, args.integer("context", 3)));
		int maxChars = Math.max(1, args.integer("max_chars", defaultMaxChars));
		boolean lineNumbers = args.bool("line_numbers", true);

		List<String> lines = text.lines().toList();
		int totalLines = lines.size();
		List<Integer> matchIndices = new ArrayList<>();
		for (int i = 0; i < totalLines; i++) {
			if (matcher.test(lines.get(i)))
				matchIndices.add(i);
		}

		List<int[]> hunks = new ArrayList<>();
		for (int idx : matchIndices) {
			int start = Math.max(0, idx - context);
			int end = Math.min(totalLines - 1, idx + context);
			if (!hunks.isEmpty() && start <= hunks.getLast()[1] + 1) {
				hunks.getLast()[1] = Math.max(hunks.getLast()[1], end);
			} else {
				hunks.add(new int[]{start, end});
			}
		}

		StringBuilder sb = new StringBuilder();
		boolean charCut = false;
		for (int h = 0; h < hunks.size() && !charCut; h++) {
			if (h > 0) {
				if (sb.length() + 3 > maxChars) {
					charCut = true;
					break;
				}
				sb.append("\n--");
			}
			int[] range = hunks.get(h);
			for (int i = range[0]; i <= range[1]; i++) {
				String rendered = lineNumbers ? (i + 1) + ": " + lines.get(i) : lines.get(i);
				int needed = (sb.isEmpty() ? 0 : 1) + rendered.length();
				if (sb.length() + needed > maxChars) {
					if (sb.isEmpty())
						sb.append(rendered, 0, Math.min(rendered.length(), maxChars));
					charCut = true;
					break;
				}
				if (!sb.isEmpty())
					sb.append('\n');
				sb.append(rendered);
			}
		}

		JsonObject json = new JsonObject();
		json.addProperty("grep", query);
		json.addProperty("matches", matchIndices.size());
		json.addProperty("hunks", hunks.size());
		json.addProperty("totalLines", totalLines);
		json.addProperty("totalChars", text.length());
		json.addProperty("truncated", charCut);
		json.addProperty("text", sb.toString());
		return json;
	}

	@Nonnull
	private static String notFoundMessage(@Nonnull Workspace workspace, @Nonnull String name) {
		String simple = name.substring(name.lastIndexOf('/') + 1);
		StringBuilder sb = new StringBuilder("No class named '").append(name).append("' in the workspace.");
		int[] found = {0};
		workspace.classesStream(false)
				.map(n -> n.getValue().getName())
				.filter(n -> n.equals(simple) || n.endsWith("/" + simple))
				.limit(5)
				.forEach(n -> sb.append(found[0]++ == 0 ? " Similar: " : ", ").append(n));
		return sb.toString();
	}

	/**
	 * @param value
	 * 		Possibly null string.
	 *
	 * @return Value, or empty string.
	 */
	@Nonnull
	public static String nonNull(@Nullable String value) {
		return Objects.requireNonNullElse(value, "");
	}
}
