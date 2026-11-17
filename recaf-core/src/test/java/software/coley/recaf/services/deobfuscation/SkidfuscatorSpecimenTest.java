package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationConfig;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationService;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.specific.skid.SkidXorInliningTransformer;
import software.coley.recaf.services.transform.*;

import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Optional local corpus checks. No class or native library from a specimen is loaded or executed. */
@EnabledIfEnvironmentVariable(named = "RECAF_SKID_SPECIMENS", matches = ".+")
class SkidfuscatorSpecimenTest extends TransformerTestBase {
	@Test
	void recoversReferencesAndXorHelpersInObfuscatedEvaluator() throws Exception {
		load("evaluator-obf.jar");
		var workspace = workspaceManager.getCurrent();
		var bundle = workspace.getPrimaryResource().getJvmClassBundle();
		Map<String, JvmClassInfo> originals = new HashMap<>(bundle);
		Set<String> verificationFailures = verificationFailures(bundle.values());
		long before = customIndys(bundle.values());
		long standardBefore = standardIndys(bundle.values());
		assertTrue(before > 0);
		var result = newApplier().transformJvm(List.of(SkidXorInliningTransformer.class, InvokeDynamicInliningTransformer.class));
		assertTrue(result.getTransformerFailures().isEmpty(), result.getTransformerFailures().toString());
		originals.forEach((name, info) -> assertSame(info, bundle.get(name)));
		result.apply();
		long after = customIndys(bundle.values());
		assertEquals(0, after, "All supplied reference bootstrap sites should be recovered");
		assertEquals(standardBefore, standardIndys(bundle.values()), "JDK bootstrap sites must remain intact");
		Set<String> newFailures = verificationFailures(bundle.values()); newFailures.removeAll(verificationFailures);
		assertTrue(newFailures.isEmpty(), newFailures.toString());
		var report = new BytecodeInvestigationService(new BytecodeInvestigationConfig()).analyze(workspace, workspace.getPrimaryResource(), () -> false);
		assertEquals(4, report.nativeMethods().size());
		assertTrue(report.nativeLibraries().stream().anyMatch(name -> name.endsWith("lock/driver-macos.skid")));
		assertTrue(report.nativeMethods().stream().anyMatch(method -> method.method().methodName().contains("Driver.get") && method.callers().size() == 15));
		System.out.printf("SKID SPECIMEN: %d classes, custom indys %d -> %d, %d changed classes, native declarations %d%n",
				bundle.size(), before, after, result.getTransformedClasses().size(), report.nativeMethods().size());
		bundle.values().stream().map(SkidfuscatorSpecimenTest::read).flatMap(c -> c.methods.stream())
				.flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof InvokeDynamicInsnNode indy && !indy.bsm.getOwner().startsWith("java/"))
				.map(i -> (InvokeDynamicInsnNode) i).map(i -> i.desc + " " + Arrays.toString(i.bsmArgs)).distinct()
				.forEach(site -> System.out.println("SKID UNRESOLVED " + site));
	}

	@Test
	void cleanupPresetProcessesBothEvaluatorSpecimensWithoutNewVerifierFailures() throws Exception {
		for (String jar : List.of("evaluator-unobf.jar", "evaluator-obf.jar")) {
			load(jar);
			var bundle = workspaceManager.getCurrent().getPrimaryResource().getJvmClassBundle();
			int classes = bundle.size();
			long before = instructions(bundle.values()), indyBefore = customIndys(bundle.values());
			Set<String> initialFailures = verificationFailures(bundle.values());
			TransformationPreset preset = BuiltinTransformationPresets.SKIDFUSCATOR_SIMPLIFICATION;
			List<Class<? extends JvmClassTransformer>> transformers = new ArrayList<>();
			Map<String, Object> parameters = new HashMap<>();
			for (var entry : preset.transformers()) {
				transformers.add(recaf.get(TransformationManager.class).getJvmClassTransformers().stream()
						.filter(type -> type.getName().equals(entry.transformerClassName())).findFirst().orElseThrow());
				parameters.putAll(entry.parameters());
			}
			var result = newApplier(preset.maxPasses()).transformJvm(transformers, new TransformationParameters(parameters));
			assertTrue(result.getTransformerFailures().isEmpty(), result.getTransformerFailures().toString());
			result.apply();
			assertEquals(classes, bundle.size());
			Set<String> failures = verificationFailures(bundle.values()); failures.removeAll(initialFailures);
			assertTrue(failures.isEmpty(), failures.toString());
			System.out.printf("SKID PRESET %s: instructions %d -> %d, custom indys %d -> %d, %d changed classes%n",
					jar, before, instructions(bundle.values()), indyBefore, customIndys(bundle.values()), result.getTransformedClasses().size());
		}
	}

	private void load(String jar) throws Exception {
		clearClasses();
		var resource = workspaceManager.getCurrent().getPrimaryResource();
		resource.getFileBundle().clear();
		try (ZipFile zip = new ZipFile(Path.of(System.getenv("RECAF_SKID_SPECIMENS"), jar).toFile())) {
			for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList()) {
				try (var stream = zip.getInputStream(entry)) {
					byte[] bytes = stream.readAllBytes();
					if (entry.getName().endsWith(".class")) resource.getJvmClassBundle().put(new JvmClassInfoBuilder(bytes).build());
					else resource.getFileBundle().put(new FileInfoBuilder<>().withName(entry.getName()).withRawContent(bytes).build());
				}
			}
		}
	}

	private static Set<String> verificationFailures(Collection<JvmClassInfo> classes) {
		Set<String> failures = new TreeSet<>();
		for (JvmClassInfo info : classes) {
			ClassNode node = read(info);
			for (MethodNode method : node.methods) {
				if (method.instructions.size() == 0) continue;
				try { new Analyzer<>(new BasicVerifier()).analyze(node.name, method); }
				catch (Exception error) { failures.add(node.name + "." + method.name + method.desc); }
			}
		}
		return failures;
	}
	private static long instructions(Collection<JvmClassInfo> classes) {
		return classes.stream().map(SkidfuscatorSpecimenTest::read).flatMap(c -> c.methods.stream())
				.flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i.getOpcode() >= 0).count();
	}
	private static long customIndys(Collection<JvmClassInfo> classes) {
		return classes.stream().map(SkidfuscatorSpecimenTest::read).flatMap(c -> c.methods.stream())
				.flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof InvokeDynamicInsnNode indy && !indy.bsm.getOwner().startsWith("java/")).count();
	}
	private static long standardIndys(Collection<JvmClassInfo> classes) {
		return classes.stream().map(SkidfuscatorSpecimenTest::read).flatMap(c -> c.methods.stream())
				.flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().startsWith("java/")).count();
	}
	private static ClassNode read(JvmClassInfo info) { ClassNode node = new ClassNode(); info.getClassReader().accept(node, ClassReader.SKIP_DEBUG); return node; }
}
