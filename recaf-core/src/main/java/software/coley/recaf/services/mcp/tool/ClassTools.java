package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.error.Outcome;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
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
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tools for browsing, decompiling and editing classes.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class ClassTools implements McpToolProvider {
	private static final int DEFAULT_MAX_CHARS = 60_000;
	private final McpSupport support;
	private final DecompilerManager decompilerManager;
	private final AssemblerPipelineManager pipelineManager;

	@Inject
	public ClassTools(@Nonnull McpSupport support, @Nonnull DecompilerManager decompilerManager,
	                  @Nonnull AssemblerPipelineManager pipelineManager) {
		this.support = support;
		this.decompilerManager = decompilerManager;
		this.pipelineManager = pipelineManager;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("class_list",
				"List classes in the workspace, sorted by name. Filter by name substring (or 're:<regex>'), package, kind " +
						"or superclass. Libraries are excluded unless include_libraries is set.",
				McpAccess.READ,
				McpToolSchema.create()
						.string("filter", "Substring of the class name to match, case-insensitive. Prefix with 're:' for a regex.")
						.string("package", "Only classes directly in this package, such as 'com/example'. Use 'com/example/*' for sub-packages too.")
						.enumeration("kind", "Only this kind of class.", List.of("class", "interface", "enum", "annotation", "record"))
						.string("extends", "Only classes with this direct superclass or interface (internal name).")
						.bool("include_libraries", "Also list classes from library resources.")
						.paging(),
				this::list);
		registry.add("class_info",
				"Structure of a class: flags, hierarchy, annotations, inner classes, fields and methods (no code).",
				McpAccess.READ,
				McpToolSchema.create().requiredString("name", "Class name, internal form (com/example/Foo)."),
				this::info);
		registry.add("class_decompile",
				"Decompile a class to Java source with one of Recaf's decompilers (see decompiler_list).",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.string("decompiler", "Decompiler name. Defaults to the configured one.")
						.integer("max_chars", "Maximum characters of source to return. Default " + DEFAULT_MAX_CHARS + "."),
				this::decompile);
		registry.add("decompiler_list", "List the available decompilers and which one is the default.",
				McpAccess.READ, McpToolSchema.create(), this::decompilers);
		registry.add("class_disassemble",
				"Disassemble a class, or a single method or field, into Recaf's JASM assembly text. " +
						"The text can be edited and passed back to class_assemble.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.string("member", "Method or field name to limit the output to.")
						.string("descriptor", "Descriptor of the member, to tell overloads apart.")
						.integer("max_chars", "Maximum characters to return. Default " + DEFAULT_MAX_CHARS + "."),
				this::disassemble);
		registry.add("class_bytes", "Raw class file bytes, hex or base64 encoded.", McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.enumeration("format", "Encoding. Default hex.", List.of("hex", "base64")),
				this::bytes);
		registry.add("class_assemble",
				"Replace a class with the result of assembling JASM text, as produced by class_disassemble. " +
						"The class name cannot change, use mapping tools to rename. On errors nothing is changed.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("name", "Name of the class being replaced.")
						.requiredString("source", "Full JASM source of the class."),
				this::assemble);
		registry.add("class_put_bytes",
				"Add or replace a class from raw class file bytes.", McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("data", "Class file bytes, base64 encoded.")
						.string("resource", "Library to add it to. Defaults to the primary resource."),
				this::putBytes);
		registry.add("class_delete", "Remove a class from the workspace.", McpAccess.WRITE,
				McpToolSchema.create().requiredString("name", "Class name."), this::delete);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private McpToolResult list(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		boolean libraries = args.bool("include_libraries", false);
		String filter = args.string("filter", null);
		Predicate<String> nameFilter = null;
		if (filter != null && !filter.isEmpty()) {
			if (filter.startsWith("re:")) {
				Pattern pattern = compile(filter.substring(3));
				nameFilter = name -> pattern.matcher(name).find();
			} else {
				String lower = filter.toLowerCase(Locale.ROOT);
				nameFilter = name -> name.toLowerCase(Locale.ROOT).contains(lower);
			}
		}
		String pkg = args.string("package", null);
		String kind = args.string("kind", null);
		String parent = args.string("extends", null);
		Predicate<String> finalNameFilter = nameFilter;
		Stream<ClassPathNode> stream = workspace.classesStream(false).filter(node -> {
			ClassInfo info = node.getValue();
			if (!libraries && support.isLibrary(node.getValueOfType(WorkspaceResource.class)))
				return false;
			if (finalNameFilter != null && !finalNameFilter.test(info.getName()))
				return false;
			if (pkg != null && !matchesPackage(info, pkg))
				return false;
			if (kind != null && !McpSupport.kind(info).equals(kind))
				return false;
			return parent == null || parent.equals(info.getSuperName()) || info.getInterfaces().contains(parent);
		});
		return McpToolResult.json(McpSupport.page(stream.sorted(), args, 200, node -> {
			ClassInfo info = node.getValue();
			JsonObject json = new JsonObject();
			json.addProperty("name", info.getName());
			json.addProperty("kind", McpSupport.kind(info));
			json.addProperty("super", info.getSuperName());
			json.addProperty("fields", info.getFields().size());
			json.addProperty("methods", info.getMethods().size());
			json.addProperty("resource", support.resourceLabel(node.getValueOfType(WorkspaceResource.class)));
			return json;
		}));
	}

	@Nonnull
	private McpToolResult info(@Nonnull McpArgs args) {
		ClassPathNode path = support.requireClass(args.requireString("name"));
		ClassInfo info = path.getValue();
		JsonObject json = new JsonObject();
		json.addProperty("name", info.getName());
		json.addProperty("kind", McpSupport.kind(info));
		json.addProperty("flags", McpSupport.flags(AccessFlag.Type.CLASS, info.getAccess()));
		json.addProperty("super", info.getSuperName());
		json.add("interfaces", strings(info.getInterfaces()));
		if (info.getSignature() != null)
			json.addProperty("signature", info.getSignature());
		if (info.getSourceFileName() != null)
			json.addProperty("sourceFile", info.getSourceFileName());
		if (info.isJvmClass())
			json.addProperty("classVersion", info.asJvmClass().getVersion());
		if (info.getOuterClassName() != null)
			json.addProperty("outerClass", info.getOuterClassName());
		json.addProperty("resource", support.resourceLabel(path.getValueOfType(WorkspaceResource.class)));
		JsonArray annotations = new JsonArray();
		for (AnnotationInfo annotation : info.getAnnotations())
			annotations.add(annotation.getDescriptor());
		json.add("annotations", annotations);
		JsonArray inners = new JsonArray();
		for (InnerClassInfo inner : info.getInnerClasses())
			inners.add(inner.getInnerClassName());
		json.add("innerClasses", inners);
		json.add("fields", members(info.getFields()));
		json.add("methods", members(info.getMethods()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult decompile(@Nonnull McpArgs args) throws Exception {
		Workspace workspace = support.workspace();
		JvmClassInfo info = support.requireJvmClass(args.requireString("name"));
		String decompilerName = args.string("decompiler", null);
		JvmDecompiler decompiler = decompilerName == null ? decompilerManager.getTargetJvmDecompiler()
				: decompilerManager.getJvmDecompiler(decompilerName);
		if (decompiler == null)
			throw new McpToolException("Unknown decompiler '" + decompilerName + "'. See decompiler_list.");
		DecompileResult result;
		try {
			result = decompilerManager.decompile(decompiler, workspace, info).get(2, TimeUnit.MINUTES);
		} catch (TimeoutException ex) {
			throw new McpToolException("Decompilation timed out after 2 minutes");
		} catch (ExecutionException ex) {
			throw new McpToolException("Decompilation failed: " + ex.getCause());
		}
		if (result.getType() != DecompileResult.ResultType.SUCCESS) {
			Throwable error = result.getException();
			throw new McpToolException("Decompilation " + result.getType().name().toLowerCase(Locale.ROOT) +
					(error == null ? "" : ": " + error));
		}
		JsonObject json = McpSupport.bounded(result.getText(), args.integer("max_chars", DEFAULT_MAX_CHARS));
		json.addProperty("decompiler", decompiler.getName() + " " + decompiler.getVersion());
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult decompilers(@Nonnull McpArgs args) {
		JsonArray array = new JsonArray();
		JvmDecompiler target = decompilerManager.getTargetJvmDecompiler();
		for (JvmDecompiler decompiler : decompilerManager.getJvmDecompilers()) {
			JsonObject json = new JsonObject();
			json.addProperty("name", decompiler.getName());
			json.addProperty("version", decompiler.getVersion());
			json.addProperty("default", decompiler == target);
			array.add(json);
		}
		return McpToolResult.json(array);
	}

	@Nonnull
	private McpToolResult disassemble(@Nonnull McpArgs args) {
		ClassPathNode path = support.requireClass(args.requireString("name"));
		AssemblerPipeline<?, ?, ?> pipeline = pipelineManager.getPipeline(path);
		Outcome<String> outcome;
		String member = args.string("member", null);
		if (member == null) {
			outcome = pipeline.disassemble(path);
		} else {
			String descriptor = args.string("descriptor", null);
			ClassMember found = path.getValue().fieldAndMethodStream()
					.filter(m -> m.getName().equals(member) && (descriptor == null || m.getDescriptor().equals(descriptor)))
					.findFirst().orElseThrow(() -> new McpToolException("No member '" + member +
							(descriptor == null ? "" : descriptor) + "' in " + path.getValue().getName()));
			ClassMemberPathNode memberPath = path.child(found);
			outcome = pipeline.disassemble(memberPath);
		}
		if (!outcome.hasValue())
			throw new McpToolException("Disassembly failed: " + McpSupport.diagnostics(outcome.diagnostics()));
		JsonObject json = McpSupport.bounded(outcome.requireValue(), args.integer("max_chars", DEFAULT_MAX_CHARS));
		if (!outcome.diagnostics().isEmpty())
			json.add("diagnostics", McpSupport.diagnostics(outcome.diagnostics()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult bytes(@Nonnull McpArgs args) {
		byte[] bytecode = support.requireJvmClass(args.requireString("name")).getBytecode();
		boolean base64 = "base64".equals(args.string("format", "hex"));
		JsonObject json = new JsonObject();
		json.addProperty("length", bytecode.length);
		json.addProperty("data", base64 ? Base64.getEncoder().encodeToString(bytecode) : HexFormat.of().formatHex(bytecode));
		return McpToolResult.json(json);
	}

	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private McpToolResult assemble(@Nonnull McpArgs args) {
		ClassPathNode path = support.requireClass(args.requireString("name"));
		AssemblerPipeline pipeline = pipelineManager.getPipeline(path);
		Outcome<? extends ClassResult<?>> outcome = pipeline.assembleSource(args.requireString("source"), "<mcp>", path);
		if (!outcome.isSuccess() || !outcome.hasValue()) {
			JsonObject json = new JsonObject();
			json.addProperty("assembled", false);
			json.add("diagnostics", McpSupport.diagnostics(outcome.diagnostics()));
			return McpToolResult.json(json);
		}
		Object representation = outcome.requireValue().representation();
		if (representation == null)
			throw new McpToolException("The assembler produced no class");
		ClassInfo assembled = (ClassInfo) pipeline.getClassInfo(representation);
		if (!assembled.getName().equals(path.getValue().getName()))
			throw new McpToolException("The source declares '" + assembled.getName() + "' but the class being replaced is '" +
					path.getValue().getName() + "'. Class names cannot change here, use mapping tools to rename.");
		support.replaceClass(path, assembled);
		JsonObject json = new JsonObject();
		json.addProperty("assembled", true);
		json.addProperty("name", assembled.getName());
		json.add("diagnostics", McpSupport.diagnostics(outcome.diagnostics()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult putBytes(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		byte[] data;
		try {
			data = Base64.getDecoder().decode(args.requireString("data"));
		} catch (IllegalArgumentException ex) {
			throw new McpToolException("'data' is not valid base64");
		}
		JvmClassInfo info;
		try {
			info = new software.coley.recaf.info.builder.JvmClassInfoBuilder(data).build();
		} catch (RuntimeException ex) {
			throw new McpToolException("Not a valid class file: " + ex.getMessage());
		}
		ClassPathNode existing = workspace.findJvmClass(false, info.getName());
		if (existing != null) {
			support.replaceClass(existing, info);
		} else {
			WorkspaceResource target = workspace.getPrimaryResource();
			String resource = args.string("resource", null);
			if (resource != null)
				target = workspace.getAllResources(false).stream()
						.filter(r -> support.resourceLabel(r).equals(resource) || support.resourceLabel(r).endsWith(":" + resource))
						.findFirst().orElseThrow(() -> new McpToolException("No resource '" + resource + "'"));
			target.getJvmClassBundle().put(info);
		}
		JsonObject json = new JsonObject();
		json.addProperty("name", info.getName());
		json.addProperty("replaced", existing != null);
		return McpToolResult.json(json);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private McpToolResult delete(@Nonnull McpArgs args) {
		ClassPathNode path = support.requireClass(args.requireString("name"));
		software.coley.recaf.workspace.model.bundle.Bundle<ClassInfo> bundle =
				path.getValueOfType(software.coley.recaf.workspace.model.bundle.Bundle.class);
		if (bundle == null || bundle.remove(path.getValue().getName()) == null)
			throw new McpToolException("Could not remove '" + path.getValue().getName() + "'");
		return McpToolResult.text("Removed " + path.getValue().getName());
	}

	private static boolean matchesPackage(@Nonnull ClassInfo info, @Nonnull String pkg) {
		String actual = info.getPackageName() == null ? "" : info.getPackageName();
		if (pkg.endsWith("/*")) {
			String prefix = pkg.substring(0, pkg.length() - 2);
			return actual.equals(prefix) || actual.startsWith(prefix + "/");
		}
		return actual.equals(pkg.endsWith("/") ? pkg.substring(0, pkg.length() - 1) : pkg);
	}

	@Nonnull
	private static Pattern compile(@Nonnull String regex) {
		try {
			return Pattern.compile(regex);
		} catch (RuntimeException ex) {
			throw new McpToolException("Invalid regex: " + ex.getMessage());
		}
	}

	@Nonnull
	private static JsonArray strings(@Nonnull List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	@Nonnull
	private static JsonArray members(@Nonnull List<? extends ClassMember> members) {
		JsonArray array = new JsonArray();
		for (ClassMember member : members)
			array.add(McpSupport.describeMember(member));
		return array;
	}
}
