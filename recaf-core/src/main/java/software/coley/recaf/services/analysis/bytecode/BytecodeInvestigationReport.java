package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.services.callgraph.MethodRef;

import java.util.List;

/** Immutable, resource-aware inventory of methods and obfuscation-related call sites. */
public record BytecodeInvestigationReport(int classes, @Nonnull List<MethodProfile> methods,
                                          @Nonnull List<CallSite> callSites,
                                          @Nonnull List<BootstrapGroup> bootstraps,
                                          @Nonnull List<String> failures,
                                          @Nonnull List<NativeMethod> nativeMethods,
                                          @Nonnull List<String> nativeLibraries) {
	public static final BytecodeInvestigationReport EMPTY = new BytecodeInvestigationReport(0, List.of(), List.of(), List.of(), List.of());

	public BytecodeInvestigationReport(int classes, @Nonnull List<MethodProfile> methods,
	                                  @Nonnull List<CallSite> callSites, @Nonnull List<BootstrapGroup> bootstraps,
	                                  @Nonnull List<String> failures) {
		this(classes, methods, callSites, bootstraps, failures, List.of(), List.of());
	}

	/** Native declarations and their exact-resource direct callers; native bodies are never executed. */
	public record NativeMethod(@Nonnull MethodProfile method, @Nonnull List<NativeCaller> callers) {}
	public record NativeCaller(@Nonnull MethodProfile method, int instruction) {}

	/** Counts describe bytecode features, not a verdict about maliciousness. */
	public record MethodProfile(@Nonnull ClassMemberPathNode path, @Nonnull String resource,
	                            int instructions, int branches, int handlers, int bitwise,
	                            int dynamicCalls, int encodedStrings, int cryptoCalls,
	                            int reflectionCalls, int loadingCalls, boolean stringHelper) {
		public int score() {
			return dynamicCalls * 4 + bitwise + encodedStrings * 2 + cryptoCalls * 8
					+ reflectionCalls * 2 + loadingCalls * 4 + handlers * 2;
		}

		@Nonnull
		public String methodName() {
			return path.getParent().getValue().getName() + "." + path.getValue().getName() + path.getValue().getDescriptor();
		}
	}

	/** The instruction ordinal counts real opcodes, excluding labels, frames, and line numbers. */
	public record CallSite(@Nonnull MethodProfile method, int instruction, @Nonnull String name,
	                       @Nonnull String descriptor, @Nonnull MethodRef target,
	                       @Nonnull List<String> bootstrapArguments, boolean dynamic, boolean standardBootstrap) {
		@Nonnull
		public String targetName() {
			return target.owner() + "." + target.name() + target.desc();
		}
	}

	public record BootstrapGroup(@Nonnull MethodRef target, @Nonnull String resource,
	                             @Nonnull List<CallSite> sites, @Nullable ClassMemberPathNode declaration) {}

	/** Evaluation is an interpreter result; it never commits changes to classes or resources. */
	public record Evaluation(@Nonnull Status status, @Nonnull List<String> arguments,
	                         @Nullable String value, @Nonnull String explanation, int instructions) {
		public enum Status { VALUE, UNKNOWN_INPUTS, UNSUPPORTED, FAILED, STALE }
	}
}
