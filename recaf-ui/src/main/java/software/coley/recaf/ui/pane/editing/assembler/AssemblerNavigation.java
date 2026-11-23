package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTIdentifier;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.ast.primitive.ASTLabel;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.descriptor.Descriptors;
import me.darknet.assembler.instructions.Instruction;
import me.darknet.assembler.query.AssemblyQueries;
import me.darknet.assembler.query.AssemblyUtils;
import me.darknet.assembler.query.MemberReference;
import me.darknet.assembler.query.SwitchTarget;
import me.darknet.assembler.query.resolution.ClassExtends;
import me.darknet.assembler.query.resolution.ClassImplements;
import me.darknet.assembler.query.resolution.InstructionResolution;
import me.darknet.assembler.query.resolution.LabelReferenceResolution;
import me.darknet.assembler.query.resolution.Resolution;
import me.darknet.assembler.query.resolution.TypeReferenceResolution;
import me.darknet.assembler.target.TargetContext;
import me.darknet.assembler.util.Location;
import me.darknet.assembler.util.Range;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;

/**
 * Resolves and executes go-to-definition navigation targets within JASM assembler source.
 */
public final class AssemblerNavigation {
	private static final Logger logger = Logging.get(AssemblerNavigation.class);

	private AssemblerNavigation() {}

	/**
	 * Navigation target resolved at a source offset.
	 */
	public sealed interface Target permits LabelTarget, TypeTarget, MemberTarget {}

	/**
	 * Target referencing a label within a method.
	 */
	public record LabelTarget(@Nonnull ASTMethod method, @Nonnull String labelName) implements Target {}

	/**
	 * Target referencing a JVM/Android internal class name.
	 */
	public record TypeTarget(@Nonnull String internalName) implements Target {}

	/**
	 * Target referencing a class field or method.
	 */
	public record MemberTarget(@Nonnull String owner, @Nonnull String name, @Nonnull String descriptor, boolean isMethod) implements Target {}

	/**
	 * Resolve a navigation target at {@code offset} inside {@code ast}.
	 *
	 * @param ast
	 * 		Parsed concrete AST.
	 * @param targetContext
	 * 		JVM or Dalvik target context.
	 * @param offset
	 * 		Caret offset in the source document.
	 *
	 * @return Resolved navigation target, or {@code null} if nothing navigable is under {@code offset}.
	 */
	@Nullable
	public static Target resolve(@Nonnull List<ASTElement> ast, @Nonnull TargetContext targetContext, int offset) {
		Resolution resolution = AssemblyQueries.resolveAt(ast, offset, targetContext);
		return switch (resolution) {
			case LabelReferenceResolution labelRef -> {
				String content = labelRef.reference().content();
				yield content == null ? null : new LabelTarget(labelRef.method(), content);
			}
			case ClassExtends ext -> new TypeTarget(ext.superName().literal());
			case ClassImplements impl -> new TypeTarget(impl.implemented().literal());
			case TypeReferenceResolution typeRef -> {
				String raw = typeRef.type().literal();
				String name = raw.startsWith("[") || (raw.startsWith("L") && raw.endsWith(";"))
						? Descriptors.internalName(raw) : raw;
				yield name == null || name.isEmpty() ? null : new TypeTarget(name);
			}
			case InstructionResolution insnRes -> resolveInstruction(insnRes.method(), insnRes.instruction(), targetContext, offset);
			default -> null;
		};
	}

	@Nullable
	private static Target resolveInstruction(@Nonnull ASTMethod method,
	                                         @Nonnull ASTInstruction instruction,
	                                         @Nonnull TargetContext targetContext,
	                                         int offset) {
		ASTIdentifier identifier = instruction.identifier();
		String instructionName = identifier == null ? null : identifier.content();
		if (instructionName == null)
			return null;

		MemberReference memberRef = AssemblyUtils.resolveMemberReference(targetContext, instruction);
		if (memberRef != null)
			return new MemberTarget(memberRef.owner(), memberRef.name(), memberRef.descriptor(), memberRef.isMethod());

		if (AssemblyUtils.isTypeReferenceInstruction(targetContext, instructionName)) {
			Instruction<?> definition = targetContext.instructions().get(instructionName);
			if (definition != null) {
				int argIndex = definition.typeReferenceOperandIndex();
				if (argIndex >= 0 && argIndex < instruction.arguments().size()) {
					String raw = instruction.arguments().get(argIndex).content();
					String typeName = raw == null ? null : Descriptors.internalName(raw);
					if (typeName != null && !typeName.isEmpty())
						return new TypeTarget(typeName);
				}
			}
		}

		ASTElement selected = instruction.pick(offset);
		List<SwitchTarget> switchTargets = AssemblyUtils.resolveSwitchTargets(targetContext, instruction);
		if (!switchTargets.isEmpty() && selected != null) {
			for (SwitchTarget target : switchTargets) {
				if (selected == target.contextElement() || selected == target.labelElement()
						|| target.labelName().equals(selected.content())) {
					return new LabelTarget(method, target.labelName());
				}
			}
		}

		if (AssemblyUtils.isBranchInstruction(targetContext, instructionName) && !instruction.arguments().isEmpty()) {
			String labelName = instruction.arguments().getLast().content();
			if (labelName != null && !labelName.isEmpty())
				return new LabelTarget(method, labelName);
		}

		return null;
	}

	/**
	 * Execute navigation to {@code target}.
	 *
	 * @param target
	 * 		Resolved target.
	 * @param workspace
	 * 		Current workspace.
	 * @param actions
	 * 		Navigation actions service.
	 * @param area
	 * 		Assembler editor code area.
	 *
	 * @return {@code true} if navigation was performed.
	 */
	public static boolean navigate(@Nonnull Target target,
	                               @Nonnull Workspace workspace,
	                               @Nonnull Actions actions,
	                               @Nonnull CodeArea area) {
		switch (target) {
			case LabelTarget(ASTMethod method, String labelName) -> {
				ASTLabel label = AssemblyUtils.findLabelDeclaration(method, labelName);
				if (label == null)
					return false;
				Range range = label.range();
				Location location = label.location();
				if (location == null)
					return false;
				area.selectRange(range.start(), range.end());
				area.showParagraphAtCenter(area.getCurrentParagraph());
				return true;
			}
			case TypeTarget(String internalName) -> {
				ClassPathNode path = workspace.findClass(internalName);
				if (path == null)
					return false;
				try {
					actions.gotoDeclaration(path);
					return true;
				} catch (IncompletePathException ex) {
					logger.error("Cannot navigate to class '{}'", internalName, ex);
					return false;
				}
			}
			case MemberTarget(String owner, String name, String descriptor, boolean isMethod) -> {
				ClassPathNode ownerPath = workspace.findClass(owner);
				if (ownerPath == null)
					return false;
				ClassMemberPathNode memberPath = ownerPath.child(name, descriptor);
				if (memberPath == null)
					return false;
				try {
					actions.gotoDeclaration(memberPath);
					return true;
				} catch (IncompletePathException ex) {
					logger.error("Cannot navigate to member '{}.{}{}'", owner, name, descriptor, ex);
					return false;
				}
			}
		}
	}
}
