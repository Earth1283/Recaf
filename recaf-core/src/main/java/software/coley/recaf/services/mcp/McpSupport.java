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
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
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
		Workspace workspace = workspace();
		String normalized = normalizeClassName(name);
		ClassPathNode path = workspace.findClass(false, normalized);
		if (path == null)
			throw new McpToolException(notFoundMessage(workspace, normalized));
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
		ClassInfo info = requireClass(name).getValue();
		if (!info.isJvmClass())
			throw new McpToolException("'" + info.getName() + "' is not a JVM class");
		return info.asJvmClass();
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
