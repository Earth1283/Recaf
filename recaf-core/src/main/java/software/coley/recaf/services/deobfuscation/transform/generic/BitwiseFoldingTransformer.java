package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.List;

/**
 * Folds bitwise expressions, optionally followed by general constant folding and another bitwise pass.
 * All steps share a context, so previews never need to commit intermediate results to the workspace.
 */
@Dependent
@Typed({BitwiseFoldingTransformer.class, JvmClassTransformer.class})
public class BitwiseFoldingTransformer extends OpaqueConstantFoldingTransformer {
	public static final String IDENTIFIER = "peephole.data.bitwisefold";
	public static final String KEY_CONSTANT_FOLDING = IDENTIFIER + ".constant-folding";
	public static final String KEY_REPEAT_BITWISE = IDENTIFIER + ".repeat-bitwise";
	private final OpaqueConstantFoldingTransformer constantFolder;
	private boolean constantFolding;
	private boolean repeatBitwise;

	@Inject
	public BitwiseFoldingTransformer(@Nonnull InheritanceGraphService graphService) {
		super(graphService, true);
		constantFolder = new OpaqueConstantFoldingTransformer(graphService);
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		super.setup(context, workspace);
		constantFolding = context.getParameters().getBoolean(KEY_CONSTANT_FOLDING, false);
		repeatBitwise = context.getParameters().getBoolean(KEY_REPEAT_BITWISE, false);
		if (constantFolding)
			constantFolder.setup(context, workspace);
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		super.transform(context, workspace, resource, bundle, initialClassState);
		if (constantFolding) {
			constantFolder.transform(context, workspace, resource, bundle, initialClassState);
			if (repeatBitwise)
				super.transform(context, workspace, resource, bundle, initialClassState);
		}
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}

	@Nonnull
	@Override
	public String beforeExample() {
		return "int value = Integer.reverse(0x12345678) >>> 4;";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return "int value = 0x01e6a2c4;";
	}

	@Nonnull
	@Override
	public List<TransformationParameter<?>> getParameterDefinitions() {
		return List.of(new TransformationParameter<>(KEY_CONSTANT_FOLDING, boolean.class, false),
				new TransformationParameter<>(KEY_REPEAT_BITWISE, boolean.class, false));
	}
}
