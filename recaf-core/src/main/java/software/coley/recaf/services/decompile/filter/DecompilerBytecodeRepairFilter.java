package software.coley.recaf.services.decompile.filter;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.util.bytecode.TryCatchRepairUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.util.function.Supplier;

/**
 * Bytecode filter that repairs obfuscated/degenerate try-catch blocks and malformed local-variable debug ranges
 * before passing class bytes to a decompiler.
 */
public class DecompilerBytecodeRepairFilter implements JvmBytecodeFilter {
	private static final Logger logger = Logging.get(DecompilerBytecodeRepairFilter.class);
	private final Supplier<DecompilerManagerConfig> configSupplier;

	/**
	 * @param config
	 * 		Decompiler manager config controlling repair toggles.
	 */
	public DecompilerBytecodeRepairFilter(@Nonnull DecompilerManagerConfig config) {
		this(() -> config);
	}

	/**
	 * @param configSupplier
	 * 		Supplier providing the active decompiler manager config controlling repair toggles.
	 */
	public DecompilerBytecodeRepairFilter(@Nonnull Supplier<DecompilerManagerConfig> configSupplier) {
		this.configSupplier = configSupplier;
	}

	@Nonnull
	@Override
	public byte[] filter(@Nonnull Workspace workspace, @Nonnull JvmClassInfo initialClassInfo, @Nonnull byte[] bytecode) {
		DecompilerManagerConfig config = configSupplier.get();
		boolean repairTryCatch = config.getFilterRepairTryCatch().getValue();
		boolean repairDebugRanges = config.getFilterRepairDebugRanges().getValue();
		if (!repairTryCatch && !repairDebugRanges)
			return bytecode;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytecode).accept(node, 0);

			boolean modified = false;
			if (repairTryCatch)
				modified |= TryCatchRepairUtil.repairTryCatches(node);
			if (repairDebugRanges)
				modified |= TryCatchRepairUtil.repairLocalVariables(node);

			if (!modified)
				return bytecode;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			logger.debug("Skipping pre-decompile bytecode repair for '{}': {}", initialClassInfo.getName(), t.getMessage());
			return bytecode;
		}
	}
}
