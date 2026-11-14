package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.BuiltinTransformationPresets;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.transform.TransformationPreset;
import software.coley.recaf.services.transform.TransformationParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Exercises the built-in bitwise profile against executable bytecode.
 */
class BitwiseSimplificationTest extends TransformerTestBase {
	@ParameterizedTest
	@CsvSource({"false,false,1", "false,true,1", "true,false,2", "true,true,3"})
	void optionalStepsRunInOrderWithoutCommitting(boolean foldConstants, boolean repeatBitwise, int steps) throws Exception {
		var original = assemble("""
				.method public static example ()I {
				    code: {
				    A:
				        iconst_1
				        invokestatic java/lang/Integer.reverse (I)I
				        iconst_1
				        iadd
				        ireturn
				    B:
				    }
				}
				""", false);
		var workspace = workspaceManager.getCurrent();
		var resource = workspace.getPrimaryResource();
		var bundle = resource.getJvmClassBundle();
		var transformer = new BitwiseFoldingTransformer(recaf.get(InheritanceGraphService.class));
		var parameters = new TransformationParameters(Map.of(
				BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING, foldConstants,
				BitwiseFoldingTransformer.KEY_REPEAT_BITWISE, repeatBitwise));
		var context = spy(new JvmTransformerContext(workspace, resource, List.of(transformer), parameters));
		List<List<Integer>> snapshots = new ArrayList<>();
		doAnswer(call -> {
			ClassNode node = (ClassNode) call.callRealMethod();
			snapshots.add(Stream.of(node.methods.getFirst().instructions.toArray())
					.map(insn -> insn.getOpcode()).filter(op -> op >= 0).toList());
			return node;
		}).when(context).getNode(bundle, original);
		transformer.setup(context, workspace);
		transformer.transform(context, workspace, resource, bundle, original);

		assertEquals(steps, snapshots.size());
		assertTrue(snapshots.getFirst().contains(INVOKESTATIC));
		assertTrue(snapshots.getFirst().contains(IADD));
		if (foldConstants) {
			assertFalse(snapshots.get(1).contains(INVOKESTATIC), "Bitwise folding must precede constant folding");
			assertTrue(snapshots.get(1).contains(IADD), "The bitwise pass must leave general arithmetic alone");
		}
		if (foldConstants && repeatBitwise)
			assertFalse(snapshots.get(2).contains(IADD), "The final bitwise pass must see constant folding's output");
		assertSame(original, bundle.get(CLASS_NAME), "Intermediate steps must not modify the workspace");
		assertTrue(disassemble(false).contains("Integer.reverse"));
	}

	@Test
	void focusedPassLeavesGeneralArithmeticAlone() {
		validateNoTransformation("""
				.method public static example ()I {
				    code: {
				    A:
				        iconst_2
				        iconst_3
				        iadd
				        ireturn
				    B:
				    }
				}
				""", List.of(BitwiseFoldingTransformer.class));
	}

	@ParameterizedTest
	@MethodSource("expressions")
	void foldsKnownExpressions(String instructions, Number expected) {
		boolean wide = expected instanceof Long;
		assemble("""
				.method public static example ()%s {
				    code: {
				    A:
				        %s
				        %sreturn
				    B:
				    }
				}
				""".formatted(wide ? "J" : "I", instructions, wide ? "l" : "i"), false);
		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));

		applyProfile();

		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		// A folded expression contains only a constant push and its return, besides labels/debug info.
		ClassNode node = new ClassNode();
		get(CLASS_NAME).getClassReader().accept(node, 0);
		long instructionCount = Stream.of(node.methods.getFirst().instructions.toArray())
				.filter(insn -> insn.getOpcode() >= 0).count();
		assertEquals(2, instructionCount, () -> disassemble(false));
	}

	static Stream<Arguments> expressions() {
		return Stream.of(
				Arguments.of("ldc 305419896\ninvokestatic java/lang/Integer.reverse (I)I", Integer.reverse(0x12345678)),
				Arguments.of("ldc -81985529216486896L\ninvokestatic java/lang/Long.reverse (J)J", Long.reverse(0xfedcba9876543210L)),
				Arguments.of("ldc 305419896\ninvokestatic java/lang/Integer.reverseBytes (I)I", Integer.reverseBytes(0x12345678)),
				Arguments.of("ldc -81985529216486896L\ninvokestatic java/lang/Long.reverseBytes (J)J", Long.reverseBytes(0xfedcba9876543210L)),
				Arguments.of("ldc 305419896\nbipush -7\ninvokestatic java/lang/Integer.rotateLeft (II)I", Integer.rotateLeft(0x12345678, -7)),
				Arguments.of("ldc 305419896\nbipush 39\ninvokestatic java/lang/Integer.rotateRight (II)I", Integer.rotateRight(0x12345678, 39)),
				Arguments.of("ldc -81985529216486896L\nbipush -7\ninvokestatic java/lang/Long.rotateLeft (JI)J", Long.rotateLeft(0xfedcba9876543210L, -7)),
				Arguments.of("ldc -81985529216486896L\nbipush 71\ninvokestatic java/lang/Long.rotateRight (JI)J", Long.rotateRight(0xfedcba9876543210L, 71)),
				Arguments.of("ldc 305419896\ninvokestatic java/lang/Integer.bitCount (I)I", Integer.bitCount(0x12345678)),
				Arguments.of("ldc -81985529216486896L\ninvokestatic java/lang/Long.bitCount (J)I", Long.bitCount(0xfedcba9876543210L)),
				Arguments.of("ldc 305419896\ninvokestatic java/lang/Integer.numberOfLeadingZeros (I)I", Integer.numberOfLeadingZeros(0x12345678)),
				Arguments.of("ldc -81985529216486896L\ninvokestatic java/lang/Long.numberOfTrailingZeros (J)I", Long.numberOfTrailingZeros(0xfedcba9876543210L)),
				Arguments.of("ldc 305419896\nbipush 35\nishl", 0x12345678 << 35),
				Arguments.of("ldc -305419896\nbipush -3\nishr", -0x12345678 >> -3),
				Arguments.of("ldc -305419896\niconst_3\niushr", -0x12345678 >>> 3),
				Arguments.of("ldc -81985529216486896L\nbipush 67\nlshl", 0xfedcba9876543210L << 67),
				Arguments.of("ldc -81985529216486896L\nbipush -3\nlshr", 0xfedcba9876543210L >> -3),
				Arguments.of("ldc -81985529216486896L\niconst_3\nlushr", 0xfedcba9876543210L >>> 3),
				Arguments.of("ldc 305419896\nldc 255\niand\nldc 256\nior\nldc 85\nixor", ((0x12345678 & 255) | 256) ^ 85),
				Arguments.of("ldc -81985529216486896L\nldc 255L\nland\nldc 256L\nlor\nldc 85L\nlxor", ((0xfedcba9876543210L & 255L) | 256L) ^ 85L));
	}

	@Test
	void foldsChainThroughStaticFieldAndLocal() {
		compile("""
				private static int seed = 0x12345678;
				public static long example() {
				    int reversed = Integer.reverse(seed);
				    long rotated = Long.rotateRight((long) reversed ^ 0xfedcba9876543210L, 71);
				    return (Long.reverse(rotated) >>> 5) & 0xffffL;
				}
				""");
		long expected = (Long.reverse(Long.rotateRight((long) Integer.reverse(0x12345678) ^ 0xfedcba9876543210L, 71)) >>> 5) & 0xffffL;
		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));

		applyProfile();

		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		String disassembly = disassemble(false);
		assertFalse(disassembly.contains("invokestatic"), disassembly);
		assertFalse(disassembly.contains("getstatic"), disassembly);
		assertFalse(disassembly.contains("lushr"), disassembly);
		assertFalse(disassembly.contains("land"), disassembly);
	}

	@Test
	void preservesUnknownInput() {
		validateNoTransformation("""
				.method public static example (J)J {
				    parameters: { x },
				    code: {
				    A:
				        lload x
				        invokestatic java/lang/Long.reverse (J)J
				        iconst_3
				        lushr
				        lreturn
				    B:
				    }
				}
				""", profileTransformers());
	}

	private void applyProfile() {
		TransformationPreset preset = BuiltinTransformationPresets.BITWISE_SIMPLIFICATION;
		TransformationApplier applier = newApplier(preset.maxPasses());
		applier.setDropFaultyClasses(preset.dropFaultyClasses());
		JvmTransformResult result = assertDoesNotThrow(() -> applier.transformJvm(profileTransformers()));
		assertTrue(result.getTransformerFailures().isEmpty(), () -> result.getTransformerFailures().toString());
		assertFalse(result.getTransformedClasses().isEmpty(), "Expected the profile to simplify the expression");
		result.apply();
	}

	private List<Class<? extends JvmClassTransformer>> profileTransformers() {
		var registered = recaf.get(TransformationManager.class).getJvmClassTransformers();
		return BuiltinTransformationPresets.BITWISE_SIMPLIFICATION.transformers().stream()
				.<Class<? extends JvmClassTransformer>>map(entry -> registered.stream()
						.filter(type -> type.getName().equals(entry.transformerClassName()))
						.findFirst().orElseThrow())
				.toList();
	}
}
