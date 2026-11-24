package software.coley.recaf.services.mcp.tool;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArguments;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tools for working with classes as Java source.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class SourceTools implements McpToolProvider {
	private final McpSupport support;
	private final JavacCompiler compiler;
	private final DecompilerManager decompilerManager;
	private final AssemblerPipelineManager pipelineManager;

	@Inject
	public SourceTools(@Nonnull McpSupport support, @Nonnull JavacCompiler compiler,
	                   @Nonnull DecompilerManager decompilerManager, @Nonnull AssemblerPipelineManager pipelineManager) {
		this.support = support;
		this.compiler = compiler;
		this.decompilerManager = decompilerManager;
		this.pipelineManager = pipelineManager;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("java_compile",
				"Compile Java source against the workspace and put the resulting classes in it, replacing existing " +
						"ones. Missing references are stubbed automatically. Use this to patch a decompiled class.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("name", "Internal name of the class declared in the source (com/example/Foo).")
						.requiredString("source", "Java source of the class.")
						.integer("target_version", "Java release to target, such as 8 or 17. Default: the class being replaced, or the compiler's own."),
				this::compile);
		registry.add("class_diff",
				"Unified diff between two classes, or between a class and given text, as decompiled source or JASM assembly.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("a", "First class name.")
						.requiredString("b", "Second class name.")
						.enumeration("view", "What to compare. Default source.", List.of("source", "assembly"))
						.integer("context", "Lines of context. Default 3."),
				this::diff);
		registry.add("decompile_export",
				"Decompile every class matching a filter to .java files under a directory, so they can be searched " +
						"with ordinary tools. Returns counts and failures.",
				McpAccess.WRITE,
				McpToolSchema.create()
						.requiredString("directory", "Directory to write to, created if needed.")
						.string("package", "Only classes in this package or below, such as 'com/example'. Default: all primary classes.")
						.integer("max_classes", "Safety cap. Default 5000."),
				this::export);
	}

	@Nonnull
	private McpToolResult compile(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		String name = McpSupport.normalizeClassName(args.requireString("name"));
		ClassPathNode existing = workspace.findClass(false, name);
		JavacArgumentsBuilder builder = new JavacArgumentsBuilder()
				.withClassName(name)
				.withClassSource(args.requireString("source"));
		if (args.has("target_version")) {
			builder.withVersionTarget(args.integer("target_version", 0));
		} else if (existing != null && existing.getValue().isJvmClass()) {
			builder.withVersionTarget(Math.max(JavacCompiler.getMinTargetVersion(),
					existing.getValue().asJvmClass().getVersion() - 44));
		}
		JavacArguments arguments = builder.build();
		CompilerResult result = compiler.compile(arguments, workspace, null);
		if (!result.wasSuccess()) {
			JsonObject json = new JsonObject();
			json.addProperty("compiled", false);
			if (result.getException() != null)
				json.addProperty("error", String.valueOf(result.getException()));
			JsonArray diagnostics = new JsonArray();
			for (CompilerDiagnostic diagnostic : result.getDiagnostics())
				diagnostics.add(diagnostic.line() + ":" + diagnostic.column() + " " + diagnostic.level() + " " + diagnostic.message());
			json.add("diagnostics", diagnostics);
			return McpToolResult.json(json);
		}
		JsonArray written = new JsonArray();
		for (Map.Entry<String, byte[]> entry : result.getCompilations().entrySet()) {
			JvmClassInfo info = new JvmClassInfoBuilder(entry.getValue()).build();
			ClassPathNode current = workspace.findJvmClass(false, info.getName());
			if (current != null)
				support.replaceClass(current, info);
			else
				workspace.getPrimaryResource().getJvmClassBundle().put(info);
			written.add(info.getName());
		}
		JsonObject json = new JsonObject();
		json.addProperty("compiled", true);
		json.add("classes", written);
		JsonArray warnings = new JsonArray();
		for (CompilerDiagnostic diagnostic : result.getDiagnostics())
			warnings.add(diagnostic.line() + ":" + diagnostic.column() + " " + diagnostic.level() + " " + diagnostic.message());
		json.add("diagnostics", warnings);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult diff(@Nonnull McpArgs args) throws Exception {
		ClassPathNode a = support.requireClass(args.requireString("a"));
		ClassPathNode b = support.requireClass(args.requireString("b"));
		boolean assembly = "assembly".equals(args.string("view", "source"));
		List<String> left = text(a, assembly);
		List<String> right = text(b, assembly);
		Patch<String> patch = DiffUtils.diff(left, right);
		List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(a.getValue().getName(), b.getValue().getName(),
				left, patch, Math.max(0, args.integer("context", 3)));
		JsonObject json = McpSupport.bounded(String.join("\n", unified), 80_000);
		json.addProperty("identical", patch.getDeltas().isEmpty());
		return McpToolResult.json(json);
	}

	@Nonnull
	private List<String> text(@Nonnull ClassPathNode path, boolean assembly) throws Exception {
		if (assembly) {
			AssemblerPipeline<?, ?, ?> pipeline = pipelineManager.getPipeline(path);
			var outcome = pipeline.disassemble(path);
			if (!outcome.hasValue())
				throw new McpToolException("Disassembly of " + path.getValue().getName() + " failed");
			return outcome.requireValue().lines().toList();
		}
		JvmClassInfo info = support.requireJvmClass(path.getValue().getName());
		DecompileResult result = decompile(info);
		return result.getText().lines().toList();
	}

	@Nonnull
	private DecompileResult decompile(@Nonnull JvmClassInfo info) throws Exception {
		try {
			DecompileResult result = decompilerManager.decompile(support.workspace(), info).get(2, TimeUnit.MINUTES);
			if (result.getType() != DecompileResult.ResultType.SUCCESS)
				throw new McpToolException("Could not decompile " + info.getName() + ": " + result.getException());
			return result;
		} catch (TimeoutException ex) {
			throw new McpToolException("Decompiling " + info.getName() + " timed out");
		} catch (ExecutionException ex) {
			throw new McpToolException("Decompiling " + info.getName() + " failed: " + ex.getCause());
		}
	}

	@Nonnull
	private McpToolResult export(@Nonnull McpArgs args) throws Exception {
		Workspace workspace = support.workspace();
		Path directory = Path.of(args.requireString("directory")).toAbsolutePath().normalize();
		Files.createDirectories(directory);
		String pkg = args.string("package", null);
		String prefix = pkg == null ? null : McpSupport.normalizeClassName(pkg).replaceAll("/+$", "") + "/";
		int max = Math.max(1, args.integer("max_classes", 5000));
		List<JvmClassInfo> classes = workspace.jvmClassesStream(false)
				.filter(path -> !support.isLibrary(path.getValueOfType(software.coley.recaf.workspace.model.resource.WorkspaceResource.class)))
				.map(path -> path.getValue().asJvmClass())
				.filter(info -> prefix == null || info.getName().startsWith(prefix))
				.sorted(java.util.Comparator.comparing(JvmClassInfo::getName))
				.limit(max)
				.toList();
		AtomicInteger written = new AtomicInteger();
		JsonArray failures = new JsonArray();
		CompletableFuture<Void> batch = decompilerManager.decompileBatch(
				decompilerManager.getTargetJvmDecompiler(), workspace, classes, Duration.ofMinutes(2), (info, result) -> {
					String name = info.getName();
					if (result.getType() != DecompileResult.ResultType.SUCCESS || result.getText() == null) {
						Throwable error = result.getException();
						failures.add(name + ": " + (error == null ? "No source returned" : error));
						return;
					}
					try {
						Path file = directory.resolve(name + ".java").normalize();
						if (!file.startsWith(directory)) {
							failures.add(name + ": Output path is outside the export directory");
							return;
						}
						Files.createDirectories(file.getParent());
						Files.writeString(file, result.getText());
						written.incrementAndGet();
					} catch (IOException | InvalidPathException ex) {
						failures.add(name + ": " + ex);
					}
				});
		try {
			batch.get(30, TimeUnit.MINUTES);
		} catch (InterruptedException | TimeoutException ex) {
			batch.cancel(false);
			throw ex;
		}
		JsonObject json = new JsonObject();
		json.addProperty("directory", directory.toString());
		json.addProperty("classesSelected", classes.size());
		json.addProperty("filesWritten", written.get());
		json.add("failures", failures);
		return McpToolResult.json(json);
	}
}
