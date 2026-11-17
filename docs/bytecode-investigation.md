# Reading heavily obfuscated embedded JARs

Open **Analysis → Bytecode investigation**. Choose the embedded JAR in the resource selector—for nLogin, this is
`com/nickuc/login/loader/plugin.jar`. Selecting the outer resource scans its embedded descendants as well.

For a first reading, leave **Use stable ASCII names** checked and click **Open readable analysis copy**. Recaf builds
an independent workspace in the background and opens its `RECAF-READABILITY.txt` report when finished. The report has
before/after counts and transformation warnings. `RECAF-ALIASES.tsv` maps generated class, method, and field names
back to their originals. Original classes remain in supporting resources. The source JAR is not written to.

The analysis copy is intended for reading, not deployment as a replacement plugin. Generated names are synthetic
labels, not recovered author names. Reflection strings, manifests, external callers, and runtime behavior are not
rewritten to make the aliased output runnable. Keep the source workspace's changes saved before switching workspaces.

## What the readable copy does

The bounded pipeline runs constant and variable folding, evaluates constant-input static helpers, resolves supported
static string bootstraps, collects/inlines static values, and simplifies opaque predicates and unreachable code.
Later phases see the earlier phases' output. The string-decoding phase emulates class initializers to recover string
tables. Evaluation interprets bytecode and uses supported JDK models; it does not load plugin classes into the host JVM.

The new bootstrap resolver recognizes a fixed `Lookup.findStatic → MethodHandle.asType → MutableCallSite` construction.
It checks literal target data, lookup and call-site type provenance, class-loader provenance, declaring-class initialization, return data flow,
accessibility, and an instruction allowlist. Bootstraps with arbitrary side effects, conditional dispatch, changed
call-site types, or extra bootstrap arguments stay unresolved. Ordinary JDK lambdas and concatenation remain intact.

The evaluator accepts JVM int stack values for boolean/byte/char/short arguments, including nested calls. DES key
specification constructors are modeled alongside the existing crypto models, enabling DES string helpers in nLogin.
Long and double array stores and receiver checks use logical ASM operand indices, including during class initialization.
Isolated `AtomicInteger` construction, `get`, and `incrementAndGet` support counters used by LiteBans's decoder.

Readable copies and selected-call inspection check support along the executed path. Unsupported instructions in unused
branches do not block an otherwise supported decoder. Reaching an unsupported instruction or unresolved/native call
stops evaluation; failures in nested calls propagate rather than becoming an ignored unknown return value. Unknown
branches and exhausted instruction budgets also stop evaluation. Input classes are still interpreted rather than loaded.
The general **Call result inlining** transform offers this as **Allow unsupported instructions in unused branches**,
disabled by default; the readable-copy pipeline enables it.

Unusual Unicode class names receive collision-checked `ReadableClass_0001` aliases. Short or Unicode private/static
helper names receive role labels such as `decodeLiteral_1`, `stringHelper_2`, and `bootstrapString_3`; private fields
receive labels such as `stringTable_1`. Existing readable names and virtual API method names are preserved. Bytecode
references are remapped together. Ambiguous duplicate class definitions retain their original names.

If a transformer fails on a class, that class keeps its version from before the phase, and the report records the
failure. Cancellation discards the unfinished copy. The instruction-budget spinner bounds each evaluation, not the
total duration of a workspace run. Larger embedded plugins can take minutes; phase/class progress appears above the tables.

## Investigating what remains

The five searchable, sortable tables provide:

- **Obfuscation hotspots:** methods ranked by dynamic calls, bit operations, encoded-looking literals, crypto,
  reflection, class loading, and exception handlers. These are investigation indicators, not a security verdict.
- **Dynamic calls:** caller, real-opcode ordinal, bootstrap target and arguments, resource provenance, and surrounding
  instructions. Standard JDK bootstrap sites are hidden by default and can be included explicitly.
- **Bootstrap families:** grouped call sites with their exact resource and a shortcut to inspect all sites in a family.
- **String-helper calls:** static calls returning strings with arguments, with direct access to the caller and target.
- **Native methods:** native declarations, exact-resource direct callers and opcode ordinals, and bundled
  native-library filename candidates, including Skidfuscator `.skid` resources. Search also matches caller names.
  Reflective and dynamic native calls may be absent from the direct-caller list. Native methods cannot be evaluated
  by the Java interpreter; their results remain unknown.

Select a row to open its method, target, bytecode, or callers/callees graph. **Evaluate constant inputs** recovers the
arguments at a selected static string call and interprets the helper in a fresh state. **Emulate class initializers**
is optional for this individual inspection. Results show inputs, plaintext when available, instructions executed, and
an explanation for unknown inputs, unsupported behavior, failures, or stale scans. Evaluation does not commit changes.
Rescan after editing classes. Nested resources and multi-release class bundles retain distinct provenance.

Skidfuscator reference bootstraps and private XOR helpers are also supported by readable analysis copies.
The **Analysis → Simplify Skidfuscator bytecode** preset provides these passes with constant folding and flow cleanup;
native predicate values remain unresolved.

The scan and interpreter run on a background worker. Tables use filtered/sorted views and render instruction context
only when a row is selected. Replaced scans and closed panes cannot publish obsolete results; short-lived evaluation
graphs do not attach persistent listeners to source resources.

## nLogin sample validation

Measured locally on 2026-11-14 using the embedded JAR from the supplied `real-jars/nLogin.jar`: 728 classes and 6,561
methods, with a 100,000-instruction evaluation budget and ASCII aliases enabled. The sample is not bundled with Recaf.

| Bytecode feature | Before | After |
|---|---:|---:|
| Custom dynamic calls returning strings | 8,864 | 0 |
| Non-JDK static string-helper calls | 20,896 | 3,866 |
| Integer/Long reverse/rotate helpers and bitwise operations | 73,595 | 5,204 |
| Classes assigned readable aliases | — | 713 |
| Transformation warnings | — | 0 |

Removing dynamic calls first exposes direct decoder calls; the subsequent phase replaces supported constant-input
calls with plaintext. One checked constructor now decompiles to a superclass call with `"backup"`, `"nlogin.admin"`,
`false`, `false`, and `new String[0]`, where the original contained custom dynamic calls, numeric fields, and obfuscated
class names. Both versions decompile successfully with CFR.

These counts do not imply complete deobfuscation. Remaining helper calls can require runtime arguments, unsupported
initializers, or other models. Aliases do not reveal business intent by themselves. Use decoded strings, call graphs,
and the original reference classes to assign meaningful names as you investigate. Path-based evaluation now propagates
failed nested operations, preserving calls that older behavior could reduce after ignoring an unmodeled operation.

Regression coverage includes narrow JVM argument types and DES decoding; selected-call evaluation, unknown inputs,
instruction limits, cancellation, nested/multi-release provenance; bootstrap behavior preservation and rejection cases;
copy isolation, alias collisions, origin reports; and JavaFX navigation, filtering, evaluation, and stale-copy disposal.
The existing folding, invokedynamic, ZKM, inheritance, and preview/diff checks also pass.

## LiteBans sample validation

Tested against the local `real-jars/LiteBans.jar`, version 2.19.0: 799 classes, with the same 100,000-instruction budget
and aliases enabled. There are no embedded JARs or custom bootstrap families in this sample. Its 208 dynamic calls
use standard JDK lambda and string-concatenation bootstraps and remain intact.

The first run exposed a wide-operand bug in exception prediction, causing 70 transformation warnings. Fixing the
logical stack indices removed those warnings. Supporting the local atomic counter and checking only reached
instructions then unlocked the shared `(Object, int) → String` decoder despite unsupported APIs in its unused branches.

| Feature | Original | Readable copy |
|---|---:|---:|
| Calls to the shared LiteBans string decoder | 11,568 | 141 |
| Non-JDK static string-helper calls | 12,348 | 909 |
| Bitwise operations/helpers | 634 | 621 |
| Transformer failures | — | 0 |
| Serialization warnings (one retained class across two phases) | — | 2 |
| Methods assigned role aliases | — | 1,571 |
| Private fields assigned aliases | — | 1,847 |

CFR successfully decompiles the original and transformed Bukkit entry point. Its string-table initializer changes
from encoded literals and decoder calls to plaintext including `config.yml`, `No command manager found!`, and
`org.bukkit.entity.Llama`. Class aliases remain at zero because this sample's names already use ASCII; short obfuscated
class names and virtual method names retain their originals. This improves string readability without claiming recovered
business names or complete deobfuscation. The original JAR is unchanged. The large `litebans/fK` class exceeds ASM's frame-computation operation limit;
its previous bytecode is retained in each affected phase and the report records the skips. It still receives reference
remapping when aliases are applied. This limit is not treated as successful deobfuscation of that class.
