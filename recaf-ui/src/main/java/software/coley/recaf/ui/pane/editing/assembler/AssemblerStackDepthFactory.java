package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.ast.primitive.ASTLabel;
import me.darknet.assembler.ast.specific.ASTClass;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.backend.jvm.compile.JavaCompileResult;
import me.darknet.assembler.backend.jvm.compile.analysis.MethodAnalysisResult;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.Frame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.TypedFrame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.ValuedFrame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.WideValue;
import me.darknet.assembler.backend.jvm.util.JvmTypeUtils;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.util.Location;
import org.objectweb.asm.tree.AbstractInsnNode;
import software.coley.recaf.services.assembler.JvmAssemblerPipelineConfig;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.linegraphics.AbstractLineGraphicFactory;
import software.coley.recaf.ui.control.richtext.linegraphics.LineContainer;
import software.coley.recaf.ui.control.richtext.linegraphics.LineGraphicFactory;
import software.coley.recaf.util.FxThreadUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gutter factory displaying JVM operand stack depth alongside assembler instructions.
 */
public class AssemblerStackDepthFactory extends AbstractLineGraphicFactory {
	private final JvmAssemblerPipelineConfig config;
	private volatile Map<Integer, Integer> lineToDepth = Collections.emptyMap();
	private Editor editor;

	/**
	 * @param config
	 * 		JVM assembler config controlling visibility.
	 */
	public AssemblerStackDepthFactory(@Nonnull JvmAssemblerPipelineConfig config) {
		super(LineGraphicFactory.P_LINE_NUMBERS + 10);
		this.config = config;
	}

	@Override
	public void install(@Nonnull Editor editor) {
		this.editor = editor;
		config.getShowStackDepthGutter().addChangeListener((ob, old, cur) -> FxThreadUtil.run(editor::redrawParagraphGraphics));
	}

	@Override
	public void uninstall(@Nonnull Editor editor) {
		this.editor = null;
	}

	/**
	 * Clear all cached stack depth badges.
	 */
	public void clear() {
		if (!lineToDepth.isEmpty()) {
			lineToDepth = Collections.emptyMap();
			redraw();
		}
	}

	/**
	 * Recompute per-line stack depths from a successful JVM compilation result.
	 */
	public void update(@Nullable List<ASTElement> ast, @Nullable ClassResult<?> result) {
		if (ast == null || !(result instanceof JavaCompileResult jvm)) {
			clear();
			return;
		}
		Map<Integer, Integer> computed = new HashMap<>();
		for (ASTElement root : ast) {
			if (root instanceof ASTMethod method) {
				collectMethod(method, jvm, computed);
			} else if (root instanceof ASTClass astClass) {
				for (ASTElement child : astClass.children()) {
					if (child instanceof ASTMethod method)
						collectMethod(method, jvm, computed);
				}
			}
		}
		lineToDepth = computed.isEmpty() ? Collections.emptyMap() : Map.copyOf(computed);
		redraw();
	}

	private static void collectMethod(@Nonnull ASTMethod method,
	                                  @Nonnull JavaCompileResult jvm,
	                                  @Nonnull Map<Integer, Integer> out) {
		if (method.getCode() == null)
			return;
		var lookup = jvm.analysisLookup().results(method.getName().literal(), method.getDescriptor().literal());
		if (!(lookup instanceof MethodAnalysisResult analysis))
			return;
		var frames = analysis.frames();
		if (frames.isEmpty())
			return;

		for (ASTInstruction insn : method.getCode()) {
			if (insn instanceof ASTLabel)
				continue;
			Location location = insn.location();
			if (location == null)
				continue;
			AbstractInsnNode asmInsn = analysis.getAstToExecutableInstructionMap().get(insn);
			Integer idx = asmInsn == null ? null : analysis.getInstructionIndex(asmInsn);
			if (idx == null)
				continue;
			var postEntry = frames.higherEntry(idx);
			Frame frame = postEntry != null ? postEntry.getValue() : frames.get(idx);
			int depth = stackDepth(frame);
			if (depth >= 0)
				out.put(location.line() - 1, depth);
		}
	}

	private static int stackDepth(@Nullable Frame frame) {
		return switch (frame) {
			case ValuedFrame valued -> (int) valued.getStack().stream().filter(v -> v != WideValue.INSTANCE).count();
			case TypedFrame typed -> (int) typed.getStack().stream().filter(t -> !JvmTypeUtils.isTop(t)).count();
			case null, default -> -1;
		};
	}

	private void redraw() {
		Editor current = editor;
		if (current != null)
			FxThreadUtil.run(current::redrawParagraphGraphics);
	}

	@Override
	public void apply(@Nonnull LineContainer container, int paragraph) {
		if (!config.getShowStackDepthGutter().getValue())
			return;
		Integer depth = lineToDepth.get(paragraph);
		if (depth == null)
			return;
		Label badge = new Label("S:" + depth);
		badge.getStyleClass().add("mono-text");
		badge.setPadding(new Insets(0, 4, 0, 4));
		badge.setOpacity(depth == 0 ? 0.35 : 0.75);
		badge.setTooltip(new Tooltip("Stack depth after instruction: " + depth));
		container.addHorizontal(badge);
	}
}
