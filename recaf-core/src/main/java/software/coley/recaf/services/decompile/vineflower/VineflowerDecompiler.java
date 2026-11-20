package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.AbstractJvmDecompiler;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.workspace.model.Workspace;

import java.util.function.UnaryOperator;

/**
 * Vineflower decompiler implementation.
 *
 * @author therathatter
 */
@ApplicationScoped
public class VineflowerDecompiler extends AbstractJvmDecompiler {
	public static final String NAME = "Vineflower";
	private final VineflowerConfig config;
	private final IResultSaver dummySaver = new DummyResultSaver();

	/**
	 * New Vineflower decompiler instance.
	 *
	 * @param config
	 * 		Decompiler configuration.
	 */
	@Inject
	public VineflowerDecompiler(@Nonnull VineflowerConfig config) {
		super(NAME, ConsoleDecompiler.version(), config);
		this.config = config;
	}

	@Nonnull
	@Override
	public DecompileResult decompileInternal(@Nonnull Workspace workspace, @Nonnull JvmClassInfo info) {
		VineflowerLogger logger = new VineflowerLogger(config);
		Fernflower fernflower = new Fernflower(dummySaver, config.getFernflowerProperties(), logger);

		try {
			UnaryOperator<JvmClassInfo> nestedView = nested -> nestedClassView(workspace, info.getName(), nested);
			ClassSource source = new ClassSource(workspace, info, nestedView);
			fernflower.addSource(source);
			fernflower.addLibrary(new LibrarySource(workspace, info, nestedView));
			fernflower.decompileContext();

			String decompiled = source.getSink().getDecompiledOutput().get();
			if (decompiled == null || decompiled.isEmpty())
				return new DecompileResult(new IllegalStateException("Missing decompilation output"), config.getHash())
						.withDiagnostics(logger.getDiagnostics());

			return new DecompileResult(decompiled, config.getHash()).withDiagnostics(logger.getDiagnostics());
		} catch (Exception e) {
			return new DecompileResult(e, config.getHash()).withDiagnostics(logger.getDiagnostics());
		} finally {
			fernflower.clearContext();
		}
	}
}
