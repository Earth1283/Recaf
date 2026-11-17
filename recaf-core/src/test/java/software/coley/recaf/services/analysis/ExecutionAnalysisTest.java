package software.coley.recaf.services.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysis;
import software.coley.recaf.services.analysis.flow.FlowGraph.*;
import software.coley.recaf.test.CompilerTestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionAnalysisTest extends CompilerTestBase {
	@BeforeEach void api() {
		compileFull("org/java_websocket/client/WebSocketClient", """
				package org.java_websocket.client;
				public abstract class WebSocketClient { public abstract void onMessage(String message); }
				""");
		compileFull("org/bukkit/entity/Player", "package org.bukkit.entity; public interface Player { void setOp(boolean value); }");
		compileFull("org/bukkit/scheduler/BukkitScheduler", "package org.bukkit.scheduler; public interface BukkitScheduler { void runTask(Object plugin, Runnable task); }");
		compileFull("org/bukkit/Bukkit", """
				package org.bukkit;
				public class Bukkit {
				  public static native org.bukkit.entity.Player getPlayer(String name);
				  public static native org.bukkit.scheduler.BukkitScheduler getScheduler();
				}
				""");
		compileFull("com/google/gson/Gson", "package com.google.gson; public class Gson { public native <T> T fromJson(String text, Class<T> type); }");
	}

	private ExecutionAnalysis scan() { return ExecutionAnalysis.scan(workspaceManager.getCurrent(), () -> false); }
	private Trace trace(ExecutionAnalysis analysis) {
		assertEquals(1, analysis.sinks().size());
		return analysis.trace(analysis.sinks().getFirst(), Limits.defaults(), () -> false);
	}
	private void plugin(String body) {
		compileFull("Plugin", "public class Plugin extends org.java_websocket.client.WebSocketClient { " + body + " }");
	}

	@Test void jsonFieldHelpersAndScheduledCaptureReachPlayerReceiver() {
		plugin("""
				static class Packet { String playerName; }
				public void onMessage(String message) {
				  Packet request = new com.google.gson.Gson().fromJson(message, Packet.class);
				  first(request.playerName);
				}
				void first(String name) { second(name); }
				void second(String name) { third(name); }
				void third(String name) { fourth(name); }
				void fourth(String name) { fifth(name); }
				void fifth(String name) {
				  Runnable task = () -> org.bukkit.Bukkit.getPlayer(name).setOp(true);
				  org.bukkit.Bukkit.getScheduler().runTask(null, task);
				}
				""");
		ExecutionAnalysis analysis = scan();
		Trace trace = trace(analysis);
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA), trace.toString());
		String explanation = trace.witnesses().toString();
		assertTrue(explanation.contains("JSON deserialization"), explanation);
		assertTrue(explanation.contains("Captured lambda value"), explanation);
		assertTrue(explanation.contains("Player lookup"), explanation);
		assertTrue(analysis.methods().stream().flatMap(m -> analysis.calls(m.id()).stream()).anyMatch(c -> c.kind() == CallKind.SCHEDULED));
		assertFalse(trace.truncated());
	}

	@Test void messageControlledConstantGrantIsControlInfluence() {
		plugin("""
				public void onMessage(String message) {
				  if (message.equals("grant")) org.bukkit.Bukkit.getPlayer("fixed").setOp(true);
				}
				""");
		Trace trace = trace(scan());
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.CONTROL), trace.toString());
		assertTrue(trace.witnesses().stream().noneMatch(w -> w.influence() == Influence.DATA), trace.toString());
		assertFalse(trace.conditions().isEmpty());
	}

	@Test void callContextsDoNotMixAnIgnoredTaintedCallIntoAConstantCall() {
		plugin("""
				static String identity(String text) { return text; }
				public void onMessage(String message) {
				  identity(message);
				  org.bukkit.Bukkit.getPlayer(identity("fixed")).setOp(true);
				}
				""");
		Trace trace = trace(scan());
		assertTrue(trace.witnesses().stream().noneMatch(w -> w.influence() == Influence.DATA || w.influence() == Influence.CONTROL), trace.toString());
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.REACHABILITY));
	}

	@Test void helperIgnoringItsArgumentDoesNotPropagateTaint() {
		plugin("""
				static String fixed(String ignored) { return "fixed"; }
				public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(fixed(message)).setOp(true); }
				""");
		assertTrue(trace(scan()).witnesses().stream().allMatch(w -> w.influence() == Influence.REACHABILITY));
	}

	@Test void globalFieldPathIsLabeledAsHeapApproximation() {
		plugin("""
				String pending;
				public void onMessage(String message) { pending = message; }
				public void later() { org.bukkit.Bukkit.getPlayer(pending).setOp(true); }
				""");
		Trace trace = trace(scan());
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA && w.steps().stream().anyMatch(Step::assumed)), trace.toString());
		assertTrue(trace.gaps().stream().anyMatch(s -> s.contains("Heap approximation")));
	}

	@Test void interfaceDispatchFindsAnImplementationAndLabelsItPossible() {
		plugin("""
				interface Action { void execute(String name); }
				static class Grant implements Action {
				  public void execute(String name) { org.bukkit.Bukkit.getPlayer(name).setOp(true); }
				}
				public void onMessage(String message) { Action action = new Grant(); action.execute(message); }
				""");
		Trace trace = trace(scan());
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA), trace.toString());
		assertTrue(trace.witnesses().stream().flatMap(w -> w.steps().stream()).anyMatch(Step::assumed));
	}

	@Test void permissionGuardRemainsVisibleWithoutDeclaringTheFlowSafe() {
		plugin("""
				native boolean hasPermission();
				public void onMessage(String message) {
				  if (!hasPermission()) return;
				  org.bukkit.Bukkit.getPlayer(message).setOp(true);
				}
				""");
		Trace trace = trace(scan());
		assertTrue(trace.conditions().stream().anyMatch(c -> c.description().contains("hasPermission")), trace.toString());
		assertTrue(trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA));
	}

	@Test void unknownReturnHasAnExplicitGap() {
		plugin("""
				native String decode(String input);
				public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(decode(message)).setOp(true); }
				""");
		Trace trace = trace(scan());
		assertFalse(trace.gaps().isEmpty());
		assertTrue(trace.witnesses().stream().noneMatch(w -> w.influence() == Influence.DATA));
	}

	@Test void budgetCancellationAndStalenessAreExplicit() {
		plugin("public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(message).setOp(true); }");
		ExecutionAnalysis analysis = scan();
		assertTrue(analysis.trace(analysis.sinks().getFirst(), new Limits(1, 1, 1, 1), () -> false).truncated());
		assertThrows(CancellationException.class, () -> analysis.trace(analysis.sinks().getFirst(), Limits.defaults(), () -> true));
		assertThrows(CancellationException.class, () -> ExecutionAnalysis.scan(workspaceManager.getCurrent(), () -> true));
		assertTrue(analysis.isCurrent());
		compileFull("Extra", "public class Extra {}");
		assertFalse(analysis.isCurrent());
		assertThrows(IllegalStateException.class, () -> trace(analysis));
	}

	@Test void lambdaConstructionAloneDoesNotCreateAnExecutionEdge() {
		plugin("""
				public void onMessage(String message) { Runnable unused = () -> org.bukkit.Bukkit.getPlayer(message).setOp(true); }
				""");
		ExecutionAnalysis analysis = scan();
		assertTrue(trace(analysis).witnesses().isEmpty());
		assertTrue(analysis.methods().stream().flatMap(m -> analysis.calls(m.id()).stream()).noneMatch(c -> c.kind() == CallKind.SCHEDULED));
	}

	@Test void directlyInvokedLambdaCarriesCapturedInput() {
		plugin("""
				public void onMessage(String message) {
				  Runnable task = () -> org.bukkit.Bukkit.getPlayer(message).setOp(true);
				  task.run();
				}
				""");
		ExecutionAnalysis analysis = scan();
		assertTrue(trace(analysis).witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA));
		assertTrue(analysis.methods().stream().flatMap(m -> analysis.calls(m.id()).stream()).anyMatch(c -> c.kind() == CallKind.LAMBDA));
	}

	@Test void wideParametersAndMultipleCallerContextsAreMappedByLogicalArgument() {
		plugin("""
				static String identity(long wide, String name, double anotherWide) { return name; }
				public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(identity(99L, message, 12.5)).setOp(true); }
				""");
		assertTrue(trace(scan()).witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA));
	}

	@Test void recursiveReturnsStopAtTheContextBudget() {
		plugin("""
				static String recurse(String name, int n) { if (n > 0) return recurse(name, n - 1); return name; }
				public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(recurse(message, 10)).setOp(true); }
				""");
		ExecutionAnalysis analysis = scan();
		Trace trace = analysis.trace(analysis.sinks().getFirst(), new Limits(1000, 100, 40, 1), () -> false);
		assertTrue(trace.truncated());
		assertTrue(trace.gaps().stream().anyMatch(s -> s.contains("context limit")));
	}

	@Test void constantAssignmentSelectedByAMessageStillHasControlInfluence() {
		plugin("""
				public void onMessage(String message) {
				  String name;
				  if (message.equals("grant")) name = "alice"; else name = "bob";
				  org.bukkit.Bukkit.getPlayer(name).setOp(true);
				}
				""");
		assertTrue(trace(scan()).witnesses().stream().anyMatch(w -> w.influence() == Influence.CONTROL));
	}

	@Test void unrelatedOnMessageMethodIsNotANetworkSource() {
		compileFull("Unrelated", """
				public class Unrelated {
				  public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(message).setOp(true); }
				}
				""");
		Trace trace = trace(scan());
		assertTrue(trace.witnesses().isEmpty());
		assertTrue(trace.gaps().stream().anyMatch(s -> s.contains("Unbound parameter")));
	}

	@Test void embeddedAndVersionedDefinitionsKeepDistinctCallSitesAndDataFlows() {
		plugin("public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(message).setOp(true); }");
		JvmClassInfo tainted = get("Plugin");
		plugin("public void onMessage(String message) { org.bukkit.Bukkit.getPlayer(\"fixed\").setOp(true); }");
		JvmClassInfo fixed = get("Plugin");
		BasicJvmClassBundle primary = new BasicJvmClassBundle();
		workspaceManager.getCurrent().getPrimaryResource().getJvmClassBundle().values().forEach(primary::initialPut); primary.put(tainted);
		BasicVersionedJvmClassBundle versioned = new BasicVersionedJvmClassBundle(17); versioned.put(fixed);
		var embedded = new WorkspaceFileResourceBuilder().withFileInfo(TestClassUtils.createFile("inner.jar", new byte[0]))
				.withJvmClassBundle(TestClassUtils.fromClasses(fixed)).build();
		Workspace isolated = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(primary)
				.withVersionedJvmClassBundles(new TreeMap<>(Map.of(17, versioned))).withEmbeddedResources(Map.of("inner.jar", embedded)).build());
		try {
			ExecutionAnalysis analysis = ExecutionAnalysis.scan(isolated, () -> false);
			assertEquals(3, analysis.sinks().size());
			for (Site site : analysis.sinks()) {
				Trace trace = analysis.trace(site, Limits.defaults(), () -> false);
				boolean data = trace.witnesses().stream().anyMatch(w -> w.influence() == Influence.DATA);
				assertEquals(site.method().resource().equals("primary"), data, site.toString());
			}
		} finally { isolated.close(); }
	}

	@Test void invalidBytecodeAndExceptionControlProduceCoverageNotes() {
		plugin("""
				public void onMessage(String message) {
				  try { org.bukkit.Bukkit.getPlayer(message).setOp(true); } catch (RuntimeException ex) { }
				}
				""");
		assertTrue(trace(scan()).gaps().stream().anyMatch(s -> s.contains("Exception-dependent")));
		var invalid = TestClassUtils.createClass("Invalid", node -> {
			var method = node.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "run", "()V", null, null);
			method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
			method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/entity/Player", "setOp", "(Z)V", true);
			method.visitInsn(org.objectweb.asm.Opcodes.RETURN); method.visitMaxs(1, 0); method.visitEnd();
		});
		Workspace isolated = TestClassUtils.fromBundle(TestClassUtils.fromClasses(invalid));
		try {
			ExecutionAnalysis analysis = ExecutionAnalysis.scan(isolated, () -> false);
			assertTrue(analysis.trace(analysis.sinks().getFirst(), Limits.defaults(), () -> false).gaps().stream().anyMatch(s -> s.contains("analysis failed")));
		} finally { isolated.close(); }
	}
}
