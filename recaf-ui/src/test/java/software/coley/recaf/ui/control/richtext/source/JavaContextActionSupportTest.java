package software.coley.recaf.ui.control.richtext.source;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.source.AstService;
import software.coley.recaf.services.source.ResolverAdapter;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithInner;
import software.coley.recaf.test.dummy.ClassWithLambda;
import software.coley.recaf.test.dummy.LambdaCalcExample;
import software.coley.recaf.test.dummy.LambdaExample;
import software.coley.recaf.util.Handles;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Tests for {@link JavaContextActionSupport}, including source resolution and inner-class declaration lookup.
 */
class JavaContextActionSupportTest extends TestBase {
	private static final String SRC_LambdaCalcExample = readSrc(LambdaCalcExample.class);
	private static final String SRC_LambdaExample = readSrc(LambdaExample.class);
	private static final String SRC_ClassWithLambda = readSrc(ClassWithLambda.class);

	@Test
	void findsInnerClassModelForSelection() throws IOException {
		// Set up a workspace that has a class with an inner.
		JvmClassInfo outerInfo = TestClassUtils.fromRuntimeClass(ClassWithInner.class);
		JvmClassInfo innerInfo = TestClassUtils.fromRuntimeClass(ClassWithInner.TheInner.class);
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(outerInfo, innerInfo));
		ClassPathNode classPath = workspace.findClass(outerInfo.getName());
		assertNotNull(classPath);

		InnerClassInfo innerClass = outerInfo.getInnerClasses().stream()
				.filter(candidate -> candidate.getInnerClassName().equals(innerInfo.getName()))
				.findFirst().orElseThrow();

		// Parse the source and resolve the inner class declaration to its workspace model.
		AstService astService = recaf.get(AstService.class);
		String source = readSrc(ClassWithInner.class);
		CompilationUnitModel unit;
		synchronized (astService.getSharedJavaParser()) {
			unit = astService.getSharedJavaParser().parse(source);
		}

		// The inner class declaration is at the index of "class TheInner" in the source.
		ResolverAdapter resolver = astService.newJavaResolver(workspace, classPath, unit);
		ClassModel innerModel = JavaContextActionSupport.findInnerClassModel(unit, resolver, innerClass);
		assertNotNull(innerModel, "Inner class source declaration was not resolved to its workspace class");
		assertEquals("TheInner", innerModel.getName());
		assertTrue(innerModel.getRange().isWithin(source.indexOf("class TheInner")));
	}

	@Test
	void resolvesEachLambdaBodyAfterAnEarlierLambdaAndAMethodReference() throws IOException {
		JvmClassInfo classInfo = TestClassUtils.fromRuntimeClass(LambdaCalcExample.class);
		assertLambdaTarget(SRC_LambdaCalcExample, classInfo, "a + b", "main", 1);
		assertLambdaTarget(SRC_LambdaCalcExample, classInfo, "a * b", "main", 2);
	}

	@Test
	void resolvesStatementLambdaBodyFromReportedExample() throws IOException {
		// We should be able to resolve the 'Runnable' lambda method at the 'println' position.
		//
		// public static void main(String[] args) {
		//   Runnable runnable = () -> System.out.println("lambda");
		//   runnable.run();
		// }
		assertLambdaTarget(SRC_LambdaExample, TestClassUtils.fromRuntimeClass(LambdaExample.class), "println", "main", 0);
	}

	@Test
	void resolvesLambdaMethodsWithoutEnclosingMethodName() {
		// Hacky mock setup that reproduces a sample where we had what seemed like a clean javac class
		// but the synthetic lambda method was missing the enclosing method portion of its name.
		JvmClassInfo classInfo = TestClassUtils.createClass("software/coley/recaf/test/dummy/UnnamedLambdaExample",
				node -> {
					MethodNode lambdaMethod = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$0", "()V", null, null);
					lambdaMethod.instructions.add(new InsnNode(RETURN));
					node.methods.add(lambdaMethod);
				});

		// Same source + test idea as the other test, but we will use the class definition above that has the 'lambda$0'.
		String source = """
				package software.coley.recaf.test.dummy;
				
				public class UnnamedLambdaExample {
				    public static void main(String[] args) {
				        Runnable runnable = () -> System.out.println("lambda");
				        runnable.run();
				    }
				}
				""";
		ClassMemberPathNode path = resolveLambdaPath(
				source, classInfo,
				"software/coley/recaf/test/dummy/UnnamedLambdaExample", "println");
		assertEquals("lambda$0", path.getValue().getName());
	}

	@Test
	void resolvesObfuscatedLambdaTargetFromInvokeDynamic() {
		// Similar mock setup as the previous test, but this time we are modeling the case where javac isn't used
		// or there is obfuscation that changes the lambda method name to break the normal 'lambda$<enclosing>$<index>' pattern.
		// When this occurs, we should fall back to reading the method bytecode and resolving the lambda target from the invokedynamic args.
		String owner = "software/coley/recaf/test/dummy/ObfuscatedLambdaExample";
		JvmClassInfo classInfo = TestClassUtils.createClass(owner, node -> {
			// Just call it "a" instead of the normal pattern.
			Handle implementation = new Handle(H_INVOKESTATIC, owner, "a", "()V", false);

			MethodNode main = new MethodNode(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
			main.visitCode();
			main.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", Handles.META_FACTORY, Type.getMethodType("()V"), implementation, Type.getMethodType("()V"));
			main.visitInsn(POP);
			main.visitInsn(RETURN);
			main.visitMaxs(1, 1);
			main.visitEnd();
			node.methods.add(main);

			MethodNode lambdaMethod = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "a", "()V", null, null);
			lambdaMethod.instructions.add(new InsnNode(RETURN));
			node.methods.add(lambdaMethod);
		});

		// Again, same source but mocked name for the sake of binding to the mocked class model.
		String source = """
				package software.coley.recaf.test.dummy;
				
				public class ObfuscatedLambdaExample {
				    public static void main(String[] args) {
				        Runnable runnable = () -> System.out.println("lambda");
				        runnable.run();
				    }
				}
				""";
		ClassMemberPathNode path = resolveLambdaPath(source, classInfo, owner, "println");
		assertEquals("a", path.getValue().getName());
	}

	@Test
	void resolvesLambdasInDifferentMethodsRegardlessOfNumbering() throws IOException {
		JvmClassInfo classInfo = TestClassUtils.fromRuntimeClass(ClassWithLambda.class);
		assertLambdaTarget(SRC_ClassWithLambda, classInfo, "System.out.println(\"foo\")", "runnable", 0);
		assertLambdaTarget(SRC_ClassWithLambda, classInfo, "s.isBlank()", "predicate", 0);
	}

	@Test
	void resolvesConstantsFromSource() {
		String source = """
				class ConstantValues {
					void values() {
						String text = "string";
						int integer = 42;
						long longValue = 42L;
						float floatValue = 1.5F;
						double doubleValue = 2.5;
					}
				}
				""";
		AstService astService = recaf.get(AstService.class);
		CompilationUnitModel unit;
		synchronized (astService.getSharedJavaParser()) {
			unit = astService.getSharedJavaParser().parse(source);
		}

		assertEquals("string", JavaContextActionSupport.findSearchableLiteralValue(unit, source.indexOf("\"string\"")));
		assertEquals(42, JavaContextActionSupport.findSearchableLiteralValue(unit, source.indexOf("42;")));
		assertEquals(42L, JavaContextActionSupport.findSearchableLiteralValue(unit, source.indexOf("42L")));
		assertEquals(1.5F, JavaContextActionSupport.findSearchableLiteralValue(unit, source.indexOf("1.5F")));
		assertEquals(2.5D, JavaContextActionSupport.findSearchableLiteralValue(unit, source.indexOf("2.5")));
	}

	@Test
	void findsLocalReferencesWithoutMixingSameNamedFieldsMethodsOrSiblingLocals() {
		String source = """
				class Example {
				    String value;
				    void value(String text) {}
				    void find() {
				        {
				            String /* value */ value = "first";
				            value = value.trim();
				            value(value);
				        }
				        {
				            String value = "second";
				            value(value);
				        }
				        value(this.value);
				        value(value);
				    }
				}
				""";
		CompilationUnitModel unit = parse(source);
		int declaration = source.indexOf("value = \"first\"");
		int assignment = source.indexOf("value = value.trim");
		int argument = source.indexOf("value(value)") + "value(".length();
		var variable = JavaVariableUsages.resolveAt(unit, declaration);
		assertNotNull(variable);
		var usages = JavaVariableUsages.find(variable);
		assertEquals(List.of(assignment, assignment + "value = ".length(), argument),
				usages.stream().map(range -> range.begin()).toList());
		assertSame(variable, JavaVariableUsages.resolveAt(unit, argument));
		assertNull(JavaVariableUsages.resolveAt(unit, source.indexOf("value(value)")));
		assertNull(JavaVariableUsages.resolveAt(unit, source.lastIndexOf("value(value)") + "value(".length()));
		assertNull(JavaVariableUsages.resolveAt(unit, source.indexOf("this.value") + "this.".length()));
	}

	@Test
	void findsParameterReferencesIncludingLambdaCaptures() {
		String source = """
				class Example {
				    void find(String text) {
				        Runnable child = () -> System.out.println(text);
				        text.trim();
				    }
				}
				""";
		CompilationUnitModel unit = parse(source);
		var parameter = JavaVariableUsages.resolveAt(unit, source.indexOf("text)"));
		assertNotNull(parameter);
		var usages = JavaVariableUsages.find(parameter);
		assertEquals(List.of(source.lastIndexOf("text)"), source.indexOf("text.trim")),
				usages.stream().map(range -> range.begin()).toList());
	}

	@Test
	void findsUsagesOfAnImplicitLambdaParameter() {
		String source = """
				class Example {
				    void find() {
				        java.util.function.Function<String, String> trim = value -> value.trim();
				    }
				}
				""";
		CompilationUnitModel unit = parse(source);
		var parameter = JavaVariableUsages.resolveAt(unit, source.indexOf("value ->"));
		assertNotNull(parameter);
		assertSame(parameter, JavaVariableUsages.resolveAt(unit, source.indexOf("value.trim")));
		assertEquals(1, JavaVariableUsages.find(parameter).size());
	}

	private static CompilationUnitModel parse(String source) {
		var parser = recaf.get(AstService.class).getSharedJavaParser();
		synchronized (parser) {
			return parser.parse(source);
		}
	}

	private void assertLambdaTarget(String source, JvmClassInfo classInfo, String lambdaBody, String callerName, int siteIndex) {
		ClassNode node = new ClassNode();
		classInfo.getClassReader().accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		MethodNode caller = node.methods.stream().filter(method -> method.name.equals(callerName)).findFirst().orElseThrow();
		Handle expected = StreamSupport.stream(caller.instructions.spliterator(), false)
				.filter(InvokeDynamicInsnNode.class::isInstance)
				.map(InvokeDynamicInsnNode.class::cast)
				.filter(site -> Handles.META_FACTORY.equals(site.bsm))
				.skip(siteIndex)
				.map(site -> (Handle) site.bsmArgs[1])
				.findFirst().orElseThrow();
		ClassMemberPathNode path = resolveLambdaPath(source, classInfo, classInfo.getName(), lambdaBody);
		assertEquals(expected.getName(), path.getValue().getName());
		assertEquals(expected.getDesc(), path.getValue().getDescriptor());
	}

	private ClassMemberPathNode resolveLambdaPath(String source, JvmClassInfo classInfo,
	                                              String className, String lambdaBody) {
		// Build the workspace from compiled bytecode so synthetic methods and descriptors are available.
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(classInfo));
		ClassPathNode classPath = workspace.findClass(className);
		assertNotNull(classPath, "Failed to find class path for: " + className);

		// Parse the decompiler-style source and resolve the class context against its workspace path.
		AstService astService = recaf.get(AstService.class);
		CompilationUnitModel unit;
		synchronized (astService.getSharedJavaParser()) {
			unit = astService.getSharedJavaParser().parse(source);
		}
		ResolverAdapter resolver = astService.newJavaResolver(workspace, classPath, unit);
		ClassModel classModel = unit.getDeclaredClasses().stream()
				.filter(model -> model.getName().equals(classPath.getValue().getName().substring(className.lastIndexOf('/') + 1)))
				.findFirst()
				.orElseThrow();

		ClassMemberPathNode lambdaPath = JavaContextActionSupport.getEnclosingLambdaPath(resolver, classModel, source.indexOf(lambdaBody), classPath);
		assertNotNull(lambdaPath, "Failed to resolve lambda path for body: " + lambdaBody);
		return lambdaPath;
	}

	private static String readSrc(Class<?> clazz) {
		return readSrc(clazz.getName().replace('.', '/'));
	}

	private static String readSrc(String name) {
		try {
			Path path = Paths.get("src/testFixtures/java/" + name + ".java");
			if (Files.exists(path))
				return Files.readString(path);
			path = Paths.get("../recaf-core").toAbsolutePath().normalize().resolve("src/testFixtures/java/" + name + ".java");
			return Files.readString(path);
		} catch (IOException ex) {
			fail("Failed to read input : " + name, ex);
			throw new IllegalStateException();
		}
	}
}
