package software.coley.recaf.ui.pane.analysis;

import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Definition;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Instruction;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Result;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.ValueState;

import java.util.List;

final class MethodAnalysisText {
	private MethodAnalysisText() {}

	static String format(ClassMemberPathNode method, Result analysis, int index) {
		Instruction selected = analysis.instructions().get(index);
		var summary = analysis.summary();
		var report = new StringBuilder(method.getParent().getValue().getName()).append('.').append(method.getValue().getName())
				.append(method.getValue().getDescriptor()).append("\n\nMethod summary\n")
				.append(summary.reachableInstructions()).append('/').append(analysis.instructions().size()).append(" reachable instructions\n")
				.append("Return sites  ").append(summary.returns()).append(" · abstract values  ").append(summary.returnValues()).append('\n')
				.append("Return slice entry definitions  ").append(summary.returnSlice().entryDefinitions()).append('\n');
		if (!summary.explicitThrows().isEmpty()) report.append("Explicit throws  ").append(summary.explicitThrows()).append('\n');
		if (!summary.escapingExceptionSites().isEmpty()) report.append("Possible escaping exceptions  ").append(summary.escapingExceptionSites()).append('\n');
		if (analysis.nonTerminatingInstructions() > 0)
			report.append(analysis.nonTerminatingInstructions()).append(" reachable instructions have no modeled path to exit\n");
		appendItems(report, "Fields read", summary.fieldReads());
		appendItems(report, "Fields written", summary.fieldWrites());
		appendItems(report, "Calls", summary.calls());
		appendItems(report, "Allocations", summary.allocations());
		if (summary.arrayWrites() > 0) report.append("Array writes  ").append(summary.arrayWrites()).append('\n');
		if (summary.monitorOperations() > 0) report.append("Monitor operations  ").append(summary.monitorOperations()).append('\n');
		report.append("\n@ ").append(index).append("  ").append(selected.opcode()).append('\n');
		if (!selected.reachable()) return report.append("Unreachable from method entry").toString();
		report.append("\nControl flow\nNormal successors  ").append(selected.successors())
				.append("\nImmediate dominator  ").append(parent(selected.immediateDominator()))
				.append("\nImmediate postdominator  ").append(parent(selected.immediatePostDominator()))
				.append("\nControl dependencies  ").append(selected.controlDependencies()).append('\n');
		for (var handler : selected.handlers()) report.append("Handler @").append(handler.target()).append("  ").append(handler.catchType()).append('\n');
		if (selected.mayEscape()) report.append("May exit through an uncaught exception\n");
		report.append("\nDef-use chains\nDefinitions consumed  ").append(selected.inputs())
				.append("\nUsers of this definition  ").append(analysis.uses().getOrDefault(new Definition(MethodAnalysis.DefinitionKind.INSTRUCTION, index), List.of())).append('\n')
				.append("\nReaching definitions and abstract values before this instruction\n");
		appendStates(report, "Local", selected.locals());
		appendStates(report, "Stack", selected.stack());
		var slice = analysis.backwardSlice(index);
		report.append("\nBackward slice\nEntry definitions  ").append(slice.entryDefinitions()).append('\n');
		for (int instruction : slice.instructions())
			report.append('@').append(instruction).append("  ").append(analysis.instructions().get(instruction).opcode()).append('\n');
		return report.toString();
	}

	private static void appendStates(StringBuilder report, String kind, List<ValueState> states) {
		for (ValueState value : states)
			report.append(kind).append(' ').append(value.slot()).append("  ").append(value.value()).append("  ").append(value.definitions()).append('\n');
	}

	private static void appendItems(StringBuilder report, String label, List<String> items) {
		if (items.isEmpty()) return;
		report.append(label).append('\n');
		for (String item : items) report.append("  ").append(item).append('\n');
	}

	private static String parent(int index) {
		return switch (index) {
			case MethodAnalysis.METHOD_EXIT -> "method exit";
			case MethodAnalysis.NO_PARENT -> "—";
			default -> "@" + index;
		};
	}
}
