package software.coley.recaf.services.decompile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.services.transform.BuiltinTransformationPresets;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests verifying decompilation repair, obfuscated plugin cleanup,
 * and fingerprinting against local {@code real-jars/} fixtures when present.
 */
class RealJarsRegressionTest extends TestBase {
	private static final Path REAL_JARS = resolveRealJarsDir();
	private static DecompilerManager decompilerManager;
	private static TransformationApplierService applierService;
	private DecompilerManagerConfig previousConfig;

	@BeforeAll
	static void setup() {
		decompilerManager = recaf.get(DecompilerManager.class);
		applierService = recaf.get(TransformationApplierService.class);
	}

	@BeforeEach
	void configureDefaults() {
		DecompilerManagerConfig config = new DecompilerManagerConfig();
		config.getCacheDecompilations().setValue(false);
		assertDoesNotThrow(() -> {
			var field = DecompilerManager.class.getDeclaredField("config");
			Object target = unwrapProxy(decompilerManager);
			previousConfig = ReflectUtil.quietGet(target, field);
			ReflectUtil.quietSet(target, field, config);
		});
	}

	@AfterEach
	void restoreConfig() {
		if (previousConfig != null) {
			assertDoesNotThrow(() -> ReflectUtil.quietSet(unwrapProxy(decompilerManager),
					DecompilerManager.class.getDeclaredField("config"), previousConfig));
		}
	}

	@Test
	void altarSmpVineflowerFailuresEliminatedAndFingerprintedAsZelix() throws Exception {
		Path jar = REAL_JARS.resolve("AltarSMP v2.8.1.jar");
		assumeTrue(Files.isRegularFile(jar), "Missing fixture: " + jar);

		WorkspaceFileResource resource = loadJar(jar, null);
		Workspace workspace = new BasicWorkspace(resource);
		try {
			JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
			assertNotNull(vf);

			for (String targetName : List.of("pl/MarketplaceProvider", "pl/Main", "pl/a")) {
				JvmClassInfo cls = resource.getJvmClassBundle().get(targetName);
				assertNotNull(cls, "Missing expected class " + targetName);
				DecompileResult res = decompilerManager.decompile(vf, workspace, cls).get(15, TimeUnit.SECONDS);
				assertEquals(DecompileResult.ResultType.SUCCESS, res.getType());
				assertTrue(res.getDiagnostics().isEmpty(),
						() -> "Expected 0 VF diagnostics for " + targetName + " after repair, got: " + res.getDiagnostics());
			}

			int sampleFailures = 0;
			List<JvmClassInfo> topLevel = resource.getJvmClassBundle().values().stream()
					.filter(c -> !c.isInnerClass() && !c.getName().contains("$"))
					.sorted(Comparator.comparing(JvmClassInfo::getName))
					.limit(60)
					.toList();
			for (JvmClassInfo cls : topLevel) {
				DecompileResult res = decompilerManager.decompile(vf, workspace, cls).get(15, TimeUnit.SECONDS);
				sampleFailures += res.getDiagnostics().size();
			}
			assertEquals(0, sampleFailures, "Expected 0 VF diagnostics across first 60 AltarSMP classes");

			var fp = new ObfuscatorFingerprinting().scan(resource, () -> false);
			assertTrue(fp.confidenceScores().get(ObfuscatorFingerprinting.Family.ZELIX) >= 80,
					() -> "Expected AltarSMP to fingerprint as ZELIX, scores=" + fp.confidenceScores());
			var tech = new ObfuscationTechnologyDetector().scan(resource, () -> false);
			assertTrue(tech.matches().stream().anyMatch(m -> m.technology() == ObfuscationTechnologyDetector.Technology.FAKE_TRY_CATCH));
		} finally {
			workspace.close();
		}
	}

	@Test
	void vulcanVineflowerDiagnosticsDropDramaticallyAndFingerprintsAsZelix() throws Exception {
		Path jar = REAL_JARS.resolve("Vulcan-2.9.7.12.jar");
		assumeTrue(Files.isRegularFile(jar), "Missing fixture: " + jar);

		WorkspaceFileResource resource = loadJar(jar, null);
		Workspace workspace = new BasicWorkspace(resource);
		try {
			JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
			assertNotNull(vf);

			List<JvmClassInfo> sample = resource.getJvmClassBundle().values().stream()
					.filter(c -> !c.isInnerClass() && !c.getName().contains("$"))
					.sorted(Comparator.comparing(JvmClassInfo::getName))
					.limit(50)
					.toList();

			var testConfig = decompilerManager.getServiceConfig();
			testConfig.getFilterRepairTryCatch().setValue(false);
			int beforeDiagnostics = 0;
			for (JvmClassInfo cls : sample)
				beforeDiagnostics += decompilerManager.decompile(vf, workspace, cls).get(15, TimeUnit.SECONDS).getDiagnostics().size();

			testConfig.getFilterRepairTryCatch().setValue(true);
			int afterDiagnostics = 0;
			for (JvmClassInfo cls : sample)
				afterDiagnostics += decompilerManager.decompile(vf, workspace, cls).get(15, TimeUnit.SECONDS).getDiagnostics().size();

			assertTrue(beforeDiagnostics >= 50, "Expected high baseline VF diagnostics before repair, got " + beforeDiagnostics);
			assertTrue(afterDiagnostics * 10 <= beforeDiagnostics,
					"Expected >=90% drop in Vulcan VF diagnostics, before=" + beforeDiagnostics + ", after=" + afterDiagnostics);

			var fp = new ObfuscatorFingerprinting().scan(resource, () -> false);
			assertTrue(fp.confidenceScores().get(ObfuscatorFingerprinting.Family.ZELIX) >= 80,
					() -> "Expected Vulcan to fingerprint as ZELIX, scores=" + fp.confidenceScores());
		} finally {
			workspace.close();
		}
	}

	@Test
	void cfrRecoversStringSwitchFailuresInRealPlugins() throws Exception {
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(cfr);

		int checked = 0;
		checked += checkCfrClean(cfr, "nLogin.jar", "com/nickuc/login/loader/plugin.jar",
				"com/nickuc/login/\u0393\u03a6\u03c2\u0393\u03b1\u03c5\u039b\u03ba\u03a8\u03ba\u03b8\u03a0\u03b3\u03c9");
		checked += checkCfrClean(cfr, "nAntiBot.jar", "com/nickuc/antibot/loader/plugin.jar",
				"com/nickuc/antibot/\u03a0\u03b6\u03a3\u03a8\u03b1\u03a9\u03c8\u03bf\u03b7\u03b9\u03b7\u0393\u0393");
		checked += checkCfrClean(cfr, "LiteBans.jar", null, "litebans/cv");
		assumeTrue(checked > 0, "No matching fixtures in " + REAL_JARS);
	}

	@Test
	void ultraProtectCleanupSimplifiesPredicatesInlinesStringsAndPrunesDecoys() throws Exception {
		Path jar = REAL_JARS.resolve("UltraProtect.jar");
		assumeTrue(Files.isRegularFile(jar), "Missing fixture: " + jar);

		WorkspaceFileResource resource = loadJar(jar, null);
		Workspace workspace = new BasicWorkspace(resource);
		try {
			var techBefore = new ObfuscationTechnologyDetector().scan(resource, () -> false);
			assertTrue(techBefore.matches().stream().anyMatch(m ->
					m.technology() == ObfuscationTechnologyDetector.Technology.ALGEBRAIC_OPAQUE_PREDICATES));

			JvmClassInfo q0Before = resource.getJvmClassBundle().get("net/bundle/pantry/q0");
			assertNotNull(q0Before);
			assertNotNull(q0Before.getDeclaredMethod("z4", "()Ljava/lang/String;"));

			TransformationApplier applier = applierService.newApplier(workspace);
			applier.setDropFaultyClasses(true);
			applier.setMaxPasses(BuiltinTransformationPresets.OBFUSCATED_PLUGIN_CLEANUP.maxPasses());

			List<Class<? extends JvmClassTransformer>> transformers = new ArrayList<>();
			for (var tp : BuiltinTransformationPresets.OBFUSCATED_PLUGIN_CLEANUP.transformers()) {
				@SuppressWarnings("unchecked")
				Class<? extends JvmClassTransformer> cls = (Class<? extends JvmClassTransformer>) Class.forName(tp.transformerClassName());
				transformers.add(cls);
			}
			Map<String, Object> paramsMap = new LinkedHashMap<>();
			for (var tp : BuiltinTransformationPresets.OBFUSCATED_PLUGIN_CLEANUP.transformers())
				paramsMap.putAll(tp.parameters());

			JvmTransformResult result = applier.transformJvm(transformers, new TransformationParameters(paramsMap));
			result.apply();

			JvmClassInfo q0After = resource.getJvmClassBundle().get("net/bundle/pantry/q0");
			assertNotNull(q0After);
			// String wrappers z4() etc. should have been inlined into callers and pruned as dead private static methods
			assertNull(q0After.getDeclaredMethod("z4", "()Ljava/lang/String;"),
					"Expected z4() string wrapper to be inlined and pruned");

			JvmClassInfo decoyHostAfter = resource.getJvmClassBundle().values().stream()
					.filter(c -> c.getMethods().stream().anyMatch(m -> m.getName().equals("run")))
					.findFirst().orElse(null);
			if (decoyHostAfter != null) {
				assertNull(decoyHostAfter.getDeclaredMethod("disassemblyRestrictedByLicense", "(Ljava/lang/String;)V"),
						"Expected unreferenced decoy method to be pruned");
			}

			JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
			assertNotNull(vf);
			DecompileResult decompiled = decompilerManager.decompile(vf, workspace, q0After).get(15, TimeUnit.SECONDS);
			assertEquals(DecompileResult.ResultType.SUCCESS, decompiled.getType());
			assertNotNull(decompiled.getText());
			assertFalse(decompiled.getText().contains("z4()"));
		} finally {
			workspace.close();
		}
	}

	@Test
	void cleanPluginsHaveZeroFalsePositiveFingerprintsAndCleanDecompilation() throws Exception {
		JvmDecompiler vf = decompilerManager.getJvmDecompiler(VineflowerDecompiler.NAME);
		JvmDecompiler cfr = decompilerManager.getJvmDecompiler(CfrDecompiler.NAME);
		assertNotNull(vf);
		assertNotNull(cfr);

		int checked = 0;
		for (String cleanJar : List.of("ChatPlugin-1.0.0.jar", "AntiAttackRL-428.jar", "KazeDL-1.0.0.jar")) {
			Path jar = REAL_JARS.resolve(cleanJar);
			if (!Files.isRegularFile(jar))
				continue;
			checked++;
			WorkspaceFileResource resource = loadJar(jar, null);
			Workspace workspace = new BasicWorkspace(resource);
			try {
				var fp = new ObfuscatorFingerprinting().scan(resource, () -> false);
				assertTrue(fp.matches().isEmpty(), () -> "False positive fingerprint on " + cleanJar + ": " + fp.matches());

				List<JvmClassInfo> sample = resource.getJvmClassBundle().values().stream()
						.filter(c -> !c.isInnerClass() && !c.getName().contains("$"))
						.sorted(Comparator.comparing(JvmClassInfo::getName))
						.limit(20)
						.toList();
				for (JvmClassInfo cls : sample) {
					DecompileResult vfRes = decompilerManager.decompile(vf, workspace, cls).get(15, TimeUnit.SECONDS);
					assertTrue(vfRes.getDiagnostics().isEmpty(), () -> cleanJar + " VF diagnostic on " + cls.getName());
					DecompileResult cfrRes = decompilerManager.decompile(cfr, workspace, cls).get(15, TimeUnit.SECONDS);
					assertTrue(cfrRes.getDiagnostics().isEmpty(), () -> cleanJar + " CFR diagnostic on " + cls.getName());
				}
			} finally {
				workspace.close();
			}
		}
		assumeTrue(checked > 0, "No matching fixtures in " + REAL_JARS);
	}

	private static int checkCfrClean(JvmDecompiler cfr, String jarName, String nestedJar, String className) throws Exception {
		Path jar = REAL_JARS.resolve(jarName);
		if (!Files.isRegularFile(jar))
			return 0;
		WorkspaceFileResource resource = loadJar(jar, nestedJar);
		Workspace workspace = new BasicWorkspace(resource);
		try {
			JvmClassInfo cls = resource.getJvmClassBundle().get(className);
			assertNotNull(cls, "Missing class " + className + " in " + jarName);
			DecompileResult res = decompilerManager.decompile(cfr, workspace, cls).get(15, TimeUnit.SECONDS);
			assertEquals(DecompileResult.ResultType.SUCCESS, res.getType());
			assertTrue(res.getDiagnostics().isEmpty(), () -> "Unexpected CFR diagnostics for " + className + ": " + res.getDiagnostics());
			assertFalse(res.getText().contains("This method has failed to decompile"));
		} finally {
			workspace.close();
		}
		return 1;
	}

	private static WorkspaceFileResource loadJar(Path jarPath, String nestedJarEntry) throws IOException {
		byte[] archive = Files.readAllBytes(jarPath);
		if (nestedJarEntry != null) {
			try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
				for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
					if (e.getName().equals(nestedJarEntry)) {
						archive = zis.readAllBytes();
						break;
					}
				}
			}
		}
		BasicJvmClassBundle classes = new BasicJvmClassBundle();
		BasicFileBundle files = new BasicFileBundle();
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
			for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
				if (e.isDirectory())
					continue;
				byte[] bytes = zis.readAllBytes();
				if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) {
					try {
						classes.initialPut(new JvmClassInfoBuilder(bytes).build());
					} catch (Throwable ignored) {}
				} else {
					files.initialPut(new FileInfoBuilder<>().withName(e.getName()).withRawContent(bytes).build());
				}
			}
		}
		return new WorkspaceFileResourceBuilder()
				.withFileInfo(new FileInfoBuilder<>().withName(jarPath.getFileName().toString()).withRawContent(archive).build())
				.withJvmClassBundle(classes)
				.withFileBundle(files)
				.build();
	}

	private static Path resolveRealJarsDir() {
		Path local = Path.of("real-jars");
		if (Files.isDirectory(local))
			return local;
		return Path.of("..", "real-jars");
	}
}
