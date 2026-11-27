package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.error.Outcome;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.decompile.DecompileDiagnostic;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Tools for browsing, decompiling and editing classes.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class ClassTools implements McpToolProvider {
	private static final int DEFAULT_MAX_CHARS = 60_000;
	private static final Set<String> COMMON_JDK_CLASSES = Set.of(
			"java/lang/Object", "java/lang/String", "java/lang/StringBuilder", "java/lang/StringBuffer",
			"java/util/Objects", "java/util/Iterator", "java/lang/Integer", "java/lang/Long",
			"java/lang/Boolean", "java/lang/Double", "java/lang/Float", "java/lang/Byte",
			"java/lang/Short", "java/lang/Character", "java/lang/Math", "java/lang/Enum", "java/lang/Record"
	);
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
		registry.add("package_list",
				"Summarize packages in the workspace with class, field and method counts. Useful for mapping large or shaded JARs.",
				McpAccess.READ,
				McpToolSchema.create()
						.string("prefix", "Only include classes under this package prefix, e.g. 'com/example'.")
						.integer("depth", "Group packages to at most this many path segments (>= 1).")
						.string("resource", "Optional resource label or embedded path (e.g. 'agent.jar').")
						.bool("include_libraries", "Also include classes from library resources.")
						.enumeration("sort", "Sort order. Default classes.", List.of("classes", "methods", "name"))
						.paging(),
				this::packages);
		registry.add("class_list",
				"List classes in the workspace, sorted by name. Filter by name substring (or 're:<regex>'), package, kind, " +
						"superclass, or resource. Libraries are excluded unless include_libraries or resource is set.",
				McpAccess.READ,
				McpToolSchema.create()
						.string("filter", "Substring of the class name to match, case-insensitive. Prefix with 're:' for a regex.")
						.string("package", "Only classes directly in this package, such as 'com/example'. Use 'com/example/*' for sub-packages too.")
						.enumeration("kind", "Only this kind of class.", List.of("class", "interface", "enum", "annotation", "record"))
						.string("extends", "Only classes with this direct superclass or interface (internal name).")
						.string("resource", "Optional resource label or embedded path (e.g. 'agent.jar').")
						.bool("include_libraries", "Also list classes from library resources.")
						.paging(),
				this::list);
		registry.add("class_info",
				"Structure of a class: flags, hierarchy, annotations, inner classes, fields (with constant values) and methods (with instruction counts).",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name, internal form (com/example/Foo).")
						.string("resource", "Optional resource label or embedded path when the class exists in multiple resources.")
						.string("member_filter", "Filter fields/methods by substring (case-insensitive) or 're:<regex>' against name+descriptor.")
						.bool("include_fields", "Include the fields array. Default true.")
						.bool("include_methods", "Include the methods array. Default true."),
				this::info);
		registry.add("class_summary",
				"Fast single-class fingerprint without decompiling: string literals, external API calls, external field accesses, " +
						"custom invokedynamic bootstraps, and referenced classes.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name, internal form (com/example/Foo).")
						.string("resource", "Optional resource label or embedded path.")
						.bool("include_common_jdk", "Include routine Object/String/StringBuilder/boxed-type calls. Default false.")
						.integer("max_strings", "Maximum distinct string literals to return (1..500). Default 100.")
						.integer("max_calls", "Maximum external call/field entries to return (1..500). Default 100."),
				this::summary);
		registry.add("class_decompile",
				"Decompile a class to Java source with one of Recaf's decompilers (see decompiler_list). Supports line pagination and grep.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.string("decompiler", "Decompiler name. Defaults to the configured one.")
						.string("resource", "Optional resource label or embedded path.")
						.string("grep", "Search decompiled lines (case-insensitive substring or 're:<regex>') and return matching hunks with context.")
						.integer("context", "Context lines around each grep match. Default 3.")
						.integer("start_line", "1-based line number to start from. Default 1.")
						.integer("max_lines", "Maximum number of lines to return.")
						.integer("max_chars", "Maximum characters of source to return. Default " + DEFAULT_MAX_CHARS + ".")
						.bool("line_numbers", "Prefix lines with 1-based line numbers. Default false (true when grep is set)."),
				this::decompile);
		registry.add("decompiler_list", "List the available decompilers and which one is the default.",
				McpAccess.READ, McpToolSchema.create(), this::decompilers);
		registry.add("class_disassemble",
				"Disassemble a class, or a single method or field, into Recaf's JASM assembly text. " +
						"The text can be edited and passed back to class_assemble. Supports line pagination and grep.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.string("member", "Method or field name to limit the output to.")
						.string("descriptor", "Descriptor of the member, to tell overloads apart.")
						.string("resource", "Optional resource label or embedded path.")
						.string("grep", "Search assembly lines (case-insensitive substring or 're:<regex>') and return matching hunks with context.")
						.integer("context", "Context lines around each grep match. Default 3.")
						.integer("start_line", "1-based line number to start from. Default 1.")
						.integer("max_lines", "Maximum number of lines to return.")
						.integer("max_chars", "Maximum characters to return. Default " + DEFAULT_MAX_CHARS + ".")
						.bool("line_numbers", "Prefix lines with 1-based line numbers. Default false (true when grep is set)."),
				this::disassemble);
		registry.add("class_bytes", "Raw class file bytes, hex or base64 encoded.", McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Class name.")
						.string("resource", "Optional resource label or embedded path.")
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
	private McpToolResult packages(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource targetResource = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: null;
		boolean libraries = args.bool("include_libraries", false);
		String rawPrefix = args.string("prefix", null);
		String prefix = null;
		if (rawPrefix != null && !rawPrefix.isBlank()) {
			prefix = McpSupport.normalizeClassName(rawPrefix);
			if (prefix.endsWith("/*"))
				prefix = prefix.substring(0, prefix.length() - 2);
			while (prefix.endsWith("/"))
				prefix = prefix.substring(0, prefix.length() - 1);
		}
		Integer depth = args.has("depth") ? Math.max(1, args.requireInteger("depth")) : null;
		String sort = args.string("sort", "classes");

		Map<String, PackageStats> stats = new HashMap<>();
		String finalPrefix = prefix;
		workspace.classesStream(false).forEach(node -> {
			WorkspaceResource res = node.getValueOfType(WorkspaceResource.class);
			if (targetResource != null) {
				if (res != targetResource)
					return;
			} else if (!libraries && support.isLibrary(res)) {
				return;
			}
			ClassInfo info = node.getValue();
			String pkg = info.getPackageName();
			String rawPkg = pkg == null ? "" : pkg;
			if (finalPrefix != null && !rawPkg.equals(finalPrefix) && !rawPkg.startsWith(finalPrefix + "/"))
				return;
			String bucket;
			if (rawPkg.isEmpty()) {
				bucket = "(default)";
			} else if (depth != null) {
				bucket = truncateDepth(rawPkg, depth);
			} else {
				bucket = rawPkg;
			}
			stats.computeIfAbsent(bucket, PackageStats::new).add(info);
		});

		Comparator<PackageStats> comparator = switch (sort) {
			case "classes" -> Comparator.comparingInt((PackageStats s) -> s.classes).reversed()
					.thenComparing(s -> s.name);
			case "methods" -> Comparator.comparingInt((PackageStats s) -> s.methods).reversed()
					.thenComparing(s -> s.name);
			case "name" -> Comparator.comparing(s -> s.name);
			default -> throw new McpToolException("Unknown sort '" + sort + "'");
		};

		return McpToolResult.json(McpSupport.page(stats.values().stream().sorted(comparator), args, 100, s -> {
			JsonObject json = new JsonObject();
			json.addProperty("package", s.name);
			json.addProperty("classes", s.classes);
			json.addProperty("fields", s.fields);
			json.addProperty("methods", s.methods);
			JsonArray samples = new JsonArray();
			s.samples.forEach(samples::add);
			json.add("sampleClasses", samples);
			return json;
		}));
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private McpToolResult list(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource targetResource = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: null;
		boolean libraries = args.bool("include_libraries", false);
		Predicate<String> nameFilter = McpSupport.textFilter(args.string("filter", null));
		String pkg = args.string("package", null);
		String kind = args.string("kind", null);
		String parent = args.string("extends", null);
		Stream<ClassPathNode> stream = workspace.classesStream(false).filter(node -> {
			WorkspaceResource res = node.getValueOfType(WorkspaceResource.class);
			if (targetResource != null) {
				if (res != targetResource)
					return false;
			} else if (!libraries && support.isLibrary(res)) {
				return false;
			}
			ClassInfo info = node.getValue();
			if (nameFilter != null && !nameFilter.test(info.getName()))
				return false;
			if (pkg != null && !McpSupport.matchesPackage(info.getName(), true, pkg))
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
		ClassPathNode path = support.requireClass(args.requireString("name"), args.string("resource", null));
		ClassInfo info = path.getValue();
		Predicate<String> memberFilter = McpSupport.textFilter(args.string("member_filter", null));
		boolean includeFields = args.bool("include_fields", true);
		boolean includeMethods = args.bool("include_methods", true);

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
		json.addProperty("fieldCount", info.getFields().size());
		json.addProperty("methodCount", info.getMethods().size());
		if (includeFields) {
			List<FieldMember> fields = memberFilter == null ? info.getFields()
					: info.getFields().stream()
					.filter(f -> memberFilter.test(f.getName() + f.getDescriptor()))
					.toList();
			json.add("fields", describeFields(fields));
		}
		if (includeMethods) {
			List<MethodMember> methods = memberFilter == null ? info.getMethods()
					: info.getMethods().stream()
					.filter(m -> memberFilter.test(m.getName() + m.getDescriptor()))
					.toList();
			Map<String, Integer> insnCounts = info.isJvmClass() ? countMethodInstructions(info.asJvmClass()) : Map.of();
			json.add("methods", describeMethods(methods, insnCounts));
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult summary(@Nonnull McpArgs args) {
		ClassPathNode path = support.requireClass(args.requireString("name"), args.string("resource", null));
		ClassInfo info = path.getValue();
		if (!info.isJvmClass())
			throw new McpToolException("'" + info.getName() + "' is not a JVM class");
		JvmClassInfo jvm = info.asJvmClass();
		boolean includeCommonJdk = args.bool("include_common_jdk", false);
		int maxStrings = Math.max(1, Math.min(500, args.integer("max_strings", 100)));
		int maxCalls = Math.max(1, Math.min(500, args.integer("max_calls", 100)));

		String className = jvm.getName();
		Set<String> stringLiterals = new LinkedHashSet<>();
		Map<String, CallStats> externalCalls = new LinkedHashMap<>();
		Map<String, Integer> externalFields = new LinkedHashMap<>();
		Map<String, Integer> customBootstraps = new LinkedHashMap<>();
		Set<String> referencedClasses = new TreeSet<>();
		int[] standardDynamic = {0};

		jvm.getClassReader().accept(new ClassVisitor(RecafConstants.getAsmVersion()) {
			private void recordRef(@Nullable String internalName) {
				if (internalName == null || internalName.equals(className) || internalName.startsWith("java/lang/"))
					return;
				referencedClasses.add(internalName);
			}

			private void recordType(@Nonnull Type type) {
				while (type.getSort() == Type.ARRAY)
					type = type.getElementType();
				if (type.getSort() == Type.OBJECT)
					recordRef(type.getInternalName());
			}

			private void recordString(@Nonnull String value) {
				stringLiterals.add(value.length() <= 300 ? value : value.substring(0, 300) + "...");
			}

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				recordRef(superName);
				if (interfaces != null) {
					for (String itf : interfaces)
						recordRef(itf);
				}
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				recordType(Type.getType(descriptor));
				if (value instanceof String s)
					recordString(s);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String mName, String mDesc, String signature, String[] exceptions) {
				Type methodType = Type.getMethodType(mDesc);
				recordType(methodType.getReturnType());
				for (Type arg : methodType.getArgumentTypes())
					recordType(arg);
				if (exceptions != null) {
					for (String ex : exceptions)
						recordRef(ex);
				}
				String caller = mName + mDesc;
				return new MethodVisitor(RecafConstants.getAsmVersion()) {
					@Override
					public void visitLdcInsn(Object value) {
						switch (value) {
							case String s -> recordString(s);
							case Type t -> recordType(t);
							case ConstantDynamic cd -> {
								String bsmKey = cd.getBootstrapMethod().getOwner() + "." +
										cd.getBootstrapMethod().getName() + cd.getBootstrapMethod().getDesc();
								customBootstraps.merge(bsmKey, 1, Integer::sum);
								for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
									if (cd.getBootstrapMethodArgument(i) instanceof String s)
										recordString(s);
								}
							}
							default -> {}
						}
					}

					@Override
					public void visitTypeInsn(int opcode, String type) {
						if (type.startsWith("["))
							recordType(Type.getType(type));
						else
							recordRef(type);
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
						recordType(Type.getType(descriptor));
						if (owner.equals(className))
							return;
						recordRef(owner);
						externalFields.merge(owner + "." + name + " " + descriptor, 1, Integer::sum);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
						if (owner.startsWith("[")) {
							recordType(Type.getType(owner));
							return;
						}
						recordRef(owner);
						if (owner.equals(className))
							return;
						if (!includeCommonJdk && COMMON_JDK_CLASSES.contains(owner))
							return;
						externalCalls.computeIfAbsent(owner + "." + name + descriptor, _ -> new CallStats()).add(caller);
					}

					@Override
					public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
						if ("java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())
								|| "java/lang/invoke/StringConcatFactory".equals(bsm.getOwner())) {
							standardDynamic[0]++;
						} else {
							String bsmKey = bsm.getOwner() + "." + bsm.getName() + bsm.getDesc();
							customBootstraps.merge(bsmKey, 1, Integer::sum);
						}
						if (bsmArgs != null) {
							for (Object arg : bsmArgs) {
								if (arg instanceof String s)
									recordString(s);
								else if (arg instanceof Handle h && !h.getOwner().equals(className))
									recordRef(h.getOwner());
							}
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		JsonObject json = new JsonObject();
		json.addProperty("name", className);
		json.addProperty("resource", support.resourceLabel(path.getValueOfType(WorkspaceResource.class)));

		json.addProperty("totalStrings", stringLiterals.size());
		JsonArray stringsArray = new JsonArray();
		stringLiterals.stream().limit(maxStrings).forEach(stringsArray::add);
		json.add("strings", stringsArray);

		json.addProperty("totalExternalCalls", externalCalls.size());
		JsonArray callsArray = new JsonArray();
		externalCalls.entrySet().stream()
				.sorted(Comparator.<Map.Entry<String, CallStats>>comparingInt(e -> e.getValue().count).reversed()
						.thenComparing(Map.Entry::getKey))
				.limit(maxCalls)
				.forEach(entry -> {
					JsonObject item = new JsonObject();
					item.addProperty("target", entry.getKey());
					item.addProperty("count", entry.getValue().count);
					JsonArray callers = new JsonArray();
					entry.getValue().callers.forEach(callers::add);
					item.add("callers", callers);
					callsArray.add(item);
				});
		json.add("externalCalls", callsArray);

		json.addProperty("totalExternalFields", externalFields.size());
		JsonArray fieldsArray = new JsonArray();
		externalFields.entrySet().stream()
				.sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
						.thenComparing(Map.Entry::getKey))
				.limit(maxCalls)
				.forEach(entry -> {
					JsonObject item = new JsonObject();
					item.addProperty("target", entry.getKey());
					item.addProperty("count", entry.getValue());
					fieldsArray.add(item);
				});
		json.add("externalFields", fieldsArray);

		json.addProperty("standardDynamicCalls", standardDynamic[0]);
		JsonArray bootstrapsArray = new JsonArray();
		customBootstraps.entrySet().stream()
				.sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
						.thenComparing(Map.Entry::getKey))
				.forEach(entry -> {
					JsonObject item = new JsonObject();
					item.addProperty("bootstrap", entry.getKey());
					item.addProperty("count", entry.getValue());
					bootstrapsArray.add(item);
				});
		json.add("customBootstraps", bootstrapsArray);

		JsonArray refsArray = new JsonArray();
		referencedClasses.stream().limit(100).forEach(refsArray::add);
		json.add("referencedClasses", refsArray);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult decompile(@Nonnull McpArgs args) throws Exception {
		Workspace workspace = support.workspace();
		JvmClassInfo info = support.requireJvmClass(args.requireString("name"), args.string("resource", null));
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
			StringBuilder msg = new StringBuilder("Decompilation ")
					.append(result.getType().name().toLowerCase(Locale.ROOT));
			if (error != null)
				msg.append(": ").append(error);
			if (!result.getDiagnostics().isEmpty()) {
				msg.append(". Diagnostics: ").append(result.getDiagnostics().stream()
						.map(DecompileDiagnostic::message).limit(5).toList());
			}
			msg.append(" (try another decompiler or method_bytecode)");
			throw new McpToolException(msg.toString());
		}
		String fullText = McpSupport.nonNull(result.getText());
		JsonObject json = args.has("grep")
				? McpSupport.grepLines(fullText, args, DEFAULT_MAX_CHARS)
				: McpSupport.boundedLines(fullText, args, DEFAULT_MAX_CHARS);
		json.addProperty("decompiler", decompiler.getName() + " " + decompiler.getVersion());
		if (!result.getDiagnostics().isEmpty()) {
			JsonArray diagnostics = new JsonArray();
			for (DecompileDiagnostic d : result.getDiagnostics()) {
				JsonObject dj = new JsonObject();
				if (d.method() != null)
					dj.addProperty("method", d.method().owner() + "." + d.method().name() + d.method().descriptor());
				dj.addProperty("message", d.message());
				if (d.cause() != null)
					dj.addProperty("cause", String.valueOf(d.cause()));
				diagnostics.add(dj);
			}
			json.add("diagnostics", diagnostics);
		}
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
		ClassPathNode path = support.requireClass(args.requireString("name"), args.string("resource", null));
		AssemblerPipeline<?, ?, ?> pipeline = pipelineManager.getPipeline(path);
		Outcome<String> outcome;
		String member = args.string("member", null);
		if (member == null) {
			outcome = pipeline.disassemble(path);
		} else {
			String descriptor = args.string("descriptor", null);
			List<ClassMember> matches = path.getValue().fieldAndMethodStream()
					.filter(m -> m.getName().equals(member) && (descriptor == null || m.getDescriptor().equals(descriptor)))
					.toList();
			if (matches.isEmpty())
				throw new McpToolException("No member '" + member +
						(descriptor == null ? "" : descriptor) + "' in " + path.getValue().getName());
			if (matches.size() > 1)
				throw new McpToolException("'" + member + "' is overloaded in " + path.getValue().getName() +
						", pass 'descriptor'. Candidates: " +
						matches.stream().map(ClassMember::getDescriptor).toList());
			ClassMemberPathNode memberPath = path.child(matches.getFirst());
			outcome = pipeline.disassemble(memberPath);
		}
		if (!outcome.hasValue())
			throw new McpToolException("Disassembly failed: " + McpSupport.diagnostics(outcome.diagnostics()));
		String fullText = outcome.requireValue();
		JsonObject json = args.has("grep")
				? McpSupport.grepLines(fullText, args, DEFAULT_MAX_CHARS)
				: McpSupport.boundedLines(fullText, args, DEFAULT_MAX_CHARS);
		if (!outcome.diagnostics().isEmpty())
			json.add("diagnostics", McpSupport.diagnostics(outcome.diagnostics()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult bytes(@Nonnull McpArgs args) {
		byte[] bytecode = support.requireJvmClass(args.requireString("name"), args.string("resource", null)).getBytecode();
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
			WorkspaceResource target = args.has("resource")
					? support.resolveResource(args.requireString("resource"))
					: workspace.getPrimaryResource();
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

	@Nonnull
	private static String truncateDepth(@Nonnull String pkg, int depth) {
		int segments = 0;
		for (int i = 0; i < pkg.length(); i++) {
			if (pkg.charAt(i) == '/' && ++segments >= depth)
				return pkg.substring(0, i);
		}
		return pkg;
	}

	@Nonnull
	private static Map<String, Integer> countMethodInstructions(@Nonnull JvmClassInfo info) {
		Map<String, Integer> counts = new HashMap<>();
		try {
			info.getClassReader().accept(new ClassVisitor(RecafConstants.getAsmVersion()) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					return new MethodVisitor(RecafConstants.getAsmVersion()) {
						private int count;

						@Override public void visitInsn(int opcode) { count++; }
						@Override public void visitIntInsn(int opcode, int operand) { count++; }
						@Override public void visitVarInsn(int opcode, int varIndex) { count++; }
						@Override public void visitTypeInsn(int opcode, String type) { count++; }
						@Override public void visitFieldInsn(int opcode, String owner, String name, String desc) { count++; }
						@Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) { count++; }
						@Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) { count++; }
						@Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { count++; }
						@Override public void visitLdcInsn(Object value) { count++; }
						@Override public void visitIincInsn(int varIndex, int increment) { count++; }
						@Override public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) { count++; }
						@Override public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) { count++; }
						@Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { count++; }

						@Override
						public void visitEnd() {
							counts.put(name + descriptor, count);
						}
					};
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException ignored) {
		}
		return counts;
	}

	@Nonnull
	private static JsonArray describeFields(@Nonnull List<FieldMember> fields) {
		JsonArray array = new JsonArray();
		for (FieldMember field : fields) {
			JsonObject json = McpSupport.describeMember(field);
			Object value = field.getDefaultValue();
			if (value != null) {
				if (value instanceof Number n)
					json.addProperty("value", n);
				else if (value instanceof Boolean b)
					json.addProperty("value", b);
				else if (value instanceof Character c)
					json.addProperty("value", c);
				else
					json.addProperty("value", String.valueOf(value));
			}
			addAnnotations(json, field.getAnnotations());
			array.add(json);
		}
		return array;
	}

	@Nonnull
	private static JsonArray describeMethods(@Nonnull List<MethodMember> methods, @Nonnull Map<String, Integer> insnCounts) {
		JsonArray array = new JsonArray();
		for (MethodMember method : methods) {
			JsonObject json = McpSupport.describeMember(method);
			Integer insns = insnCounts.get(method.getName() + method.getDescriptor());
			if (insns != null)
				json.addProperty("instructions", insns);
			if (!method.getThrownTypes().isEmpty())
				json.add("exceptions", strings(method.getThrownTypes()));
			addAnnotations(json, method.getAnnotations());
			array.add(json);
		}
		return array;
	}

	private static void addAnnotations(@Nonnull JsonObject json, @Nonnull List<AnnotationInfo> annotations) {
		if (annotations.isEmpty())
			return;
		JsonArray array = new JsonArray();
		for (AnnotationInfo a : annotations)
			array.add(a.getDescriptor());
		json.add("annotations", array);
	}

	@Nonnull
	private static JsonArray strings(@Nonnull List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static final class CallStats {
		private int count;
		private final Set<String> callers = new LinkedHashSet<>();

		void add(@Nonnull String caller) {
			count++;
			if (callers.size() < 3)
				callers.add(caller);
		}
	}

	private static final class PackageStats {
		private final String name;
		private int classes;
		private int fields;
		private int methods;
		private final TreeSet<String> samples = new TreeSet<>();

		private PackageStats(@Nonnull String name) {
			this.name = name;
		}

		void add(@Nonnull ClassInfo info) {
			classes++;
			fields += info.getFields().size();
			methods += info.getMethods().size();
			if (samples.size() < 3 || info.getName().compareTo(samples.last()) < 0) {
				samples.add(info.getName());
				if (samples.size() > 3)
					samples.pollLast();
			}
		}
	}
}
