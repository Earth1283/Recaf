# Simplifying dispatchers and exploring input influence

Three separate tools are available under **Analysis**. They inspect JVM bytecode without running plugin classes.

## Simplify control flow

**Analysis → Simplify control flow** opens a deobfuscation preview configured with constant folding, local dispatcher
simplification, opaque predicate simplification, dead code removal, goto inlining, and variable folding, with up to
six passes. Review the source/bytecode diff, then use **Transform workspace** to apply. **Run on preview only** allows
inspection without committing changes.

For example:

```java
int state = 123;
while (true) {
    switch (state) {
        case 123 -> state = 321;
        case 321 -> state = 69;
        case 69 -> { work(); return; }
    }
}
```

can become `work(); return;`. Conditional transitions retain their condition. Literal ternary transitions sharing
one state store are also specialized. Assignments inside the loop retain their reset semantics; traditional switch
fall-through follows the actual bytecode.

The new transformer (`peephole.flow.dispatcher`) redirects proven constant local integer state writes to their switch
destinations. It bypasses only labels, NOPs, unconditional jumps, and the dispatcher's load/switch. State writes and
useful effects remain; subsequent cleanup removes unused bookkeeping. Unknown transitions retain their dispatcher.
It does not duplicate arbitrary case bodies or speculate about runtime state.

This version leaves methods with exception handlers, more than 30,000 ASM instructions, or failed bytecode analysis
unchanged. Field/array dispatch state, encrypted runtime transitions, and transitions with intervening effects are
outside the pass's scope. The pass also participates in plugin deobfuscated views and readable analysis copies.

## Trace external influence

Open **Analysis → Trace external influence**, or right-click a JVM method/reference and choose **Trace external
influence**. Select an exact call site in the sidebar. Entries include caller, resource, descriptor, and executable
instruction ordinal (zero-based). The execution explorer can send any selected call site here.

Built-in sinks cover Bukkit operator state changes and server command dispatch, process execution, and recognized
class-loader `defineClass` calls. `setOp(false)` remains an operator state change, not an escalation verdict.

- **DATA:** input affects a sink operand, including its receiver: a message can select the player receiving `setOp(true)`.
- **CONTROL:** input affects a branch controlling the operation or an intermediate value, including constant sink arguments.
- **REACHABILITY:** a recognized network callback can reach the operation; this alone does not establish data influence.

Value/control paths precede reachability-only paths. Select a path for a visual source-to-sink chain. Select a node
and use **Open method** to inspect code; evidence identifies its instruction ordinal. **Show bytecode steps** includes
the local copies hidden by the shorter presentation. The selectable evidence text contains all steps, controlling
conditions encountered (including recognizable permission-check calls), and coverage notes.

The intended example is:

```text
WebSocket message parameter
  → Gson/Jackson deserialization
  → request.playerName
  → intermediate method arguments
  → captured value in a scheduled lambda
  → Bukkit.getPlayer(name)
  → Player.setOp(true)
```

Sources include supported Java-WebSocket callbacks, annotated `javax.websocket`/`jakarta.websocket` message handlers,
JDK WebSocket text/binary listeners, recognized Netty inbound handlers, servlet request accessors, JDK HTTP response
bodies, and selected player-chat accessors. Models cover JSON parsing/accessors, string operations, selected JDK
conversions, Bukkit player lookup, and selected container accessors. Bukkit schedulers, standard executor interfaces,
and direct `Runnable.run`/`Callable.call` lambda invocations have explicit callback models. Constructing a lambda alone
does not create an execution edge.

## Plugin execution explorer

**Analysis → Plugin execution explorer** opens an independent tab of possible method-call relationships. Choose an
entry point, or enable **Show all methods** and search for another starting point. Recognized entries include
supported network callbacks, annotated event handlers, Bukkit/Bungee lifecycle hooks, Bukkit command callbacks,
and JVM `main` methods.

Select a graph method to expand its calls. **Collapse graph** restores the starting method's immediate calls.
Privileged API boundaries remain visible; **Show external calls** adds other unresolved/external calls. The lower
call list identifies exact sites and explains virtual dispatch and callback handoffs. Select a call and choose
**Trace selected call** to open the independent influence view. **Open method** opens the selected method.
Drag the background to pan; Ctrl+scroll zooms. **Fit graph** fits the current graph within the viewport.

Solid edges are resolved direct calls within one resource. Dashed edges indicate virtual dispatch, callback models,
cross-resource resolution, or external/unresolved bodies. Highlighted methods contain a recognized sink or network
entry. This is a syntactic call inventory: an edge is not proof that its instruction executes. It is not a timeline
or a graph of every individual instruction. Display limits are 120 nodes, 240 edges, and ten levels, reported in status.

## Precision, coverage, and limits

The inventory scans primary, embedded, and multi-release JVM resources, preserving their locations. Resolution prefers
the caller's resource and otherwise requires a unique definition. Cross-resource classloader behavior is approximate.
Supporting library bodies are external boundaries; shaded libraries in the primary resource are indexed. Known
propagation models take precedence over their bodies.

The slicer uses ASM SourceInterpreter def-use relations with merged origins. Return-to-call and formal-to-actual
edges carry call-site context, avoiding mixing an unrelated tainted call into a constant call to the same helper.
Recursion uses bounded contexts rather than the security scanner's three-call reachability cutoff.

This is a bounded investigation tool, not a complete verifier or vulnerability verdict:

- Virtual dispatch approximates the hierarchy of indexed classes. Supporting-library hierarchy, runtime classloaders,
  reflection, and custom dynamic bootstraps can leave unresolved paths.
- Field reads consider possible writes and receiver influence, without object identity or write ordering. Deserialized
  objects are tracked at object level, not individual JSON keys. Approximate edges are labeled.
- General constructor/object mutation contents, array writes/aliases, and key-specific container mutations are not
  modeled. Reached boundaries produce notes; stack permutations that merge origins are marked approximate.
- Control dependencies use normal-flow postdominators. Exception-dependent control is explicitly omitted;
  nonterminating regions use conservative exit edges. Branch feasibility and authorization correctness are not proved.
- Unsupported returns stop with a coverage note. Unknown operations are not sanitizers. Permission checks remain
  visible for inspection and do not automatically mark a flow safe.
- Inventory stops at 100,000 methods. Value analysis stops above 20,000 ASM instructions or 2,000,000 estimated frame slots per method; control
  dependencies stop above 1,800. Default traces allow 20,000 visited/pending states, depth 160, 40 witnesses, and
  16 nested call contexts. Exhausted trace budgets explicitly mark partial results.

Both views run background workers and cancel replaced requests. Closed views discard late results. Input snapshot
checks cover class changes and resource/bundle membership; edits require **Rescan** before using results. Neither
view edits or executes the plugin. An empty result means no modeled path was found within stated coverage.

Core code is in `services.analysis.flow`; UI code is in `ui.pane.analysis`. Regression fixtures cover dispatcher
behavior and effects, real branches/defaults, shared ternary stores, protected methods, JSON/field/helper/callback
flow, call-site separation, virtual dispatch, recursion budgets, resource/version provenance, and malformed bytecode.
JavaFX tests cover independent-view navigation, filtering, snapshot changes, cancellation, and obsolete publication.
