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
The **Analysis → Deobfuscation → Simplify Skidfuscator bytecode** preset provides these passes with constant folding and
flow cleanup. Native predicate values remain unresolved.

The scan and interpreter run on a background worker. Tables use filtered/sorted views and render instruction context
only when a row is selected. Replaced scans and closed panes cannot publish obsolete results; short-lived evaluation
graphs do not attach persistent listeners to source resources.

## Obfuscator fingerprints

In **Obfuscator fingerprints**, click **Run fingerprint scan** to score Allatori, Bozar, DashO, dProtect, JObf,
native-obfuscator, R8, Radon, Skidfuscator, Stringer, and Zelix KlassMaster. The percentages are heuristic evidence
ratings. A 0% means the scan found no supported fingerprint for that family. Distinct signatures and matching patterns
across different classes can raise the score. Repeated occurrences in one class or its multi-release variants
contribute to the evidence count.

The report shows the highest score for each family across the selected resource and its embedded archives. Scores are
calculated separately for each resource. Positive results list the matching evidence, its locations, and any available
cleanup suggestions. **Copy fingerprints** copies both the scores and the report details.

The Allatori rule looks for trial markers in renamed members. The R8 rule looks for the mapping ID and hash in JVM
source-file metadata. The dProtect rule covers retained decoder names paired with their character transformation.
[Allatori documentation](https://allatori.com/doc.html#version-marker), [R8 source-file template](https://r8.googlesource.com/r8.git/+/7f8c3083670f1bcfc5973c1d477c43cf2837ff3f/d8_r8/commonBuildSrc/src/main/kotlin/CreateR8LibraryTask.kt), [dProtect string encoders](https://github.com/open-obfuscator/dProtect/blob/25173cc11644679f80b0543841007e043d2f599c/base/src/main/java/dprotect/runtime/strings/StringEncoding.java)

JObf and native-obfuscator have distinct loader signatures. Stringer has a stack-trace-based string decoder signature.
[JObf packager](https://github.com/superblaubeere27/obfuscator/blob/52ff3b7c8159968d0506ce2844ac12e4b779621a/obfuscator-core/src/main/java/me/superblaubeere27/jobf/processors/packager/Packager.java), [native-obfuscator loader generation](https://github.com/radioegor146/native-obfuscator/blob/b3b3a40ac27c78e343631b078fa62f932edd09ff/obfuscator/src/main/java/by/radioegor146/NativeObfuscator.java), [Stringer decoder analysis](https://github.com/java-deobfuscator/deobfuscator/blob/e7b6eba9bfc8962968c5eb94c1588f30e309b1e1/src/main/java/com/javadeobfuscator/deobfuscator/rules/stringer/RuleStringDecryptorV3.java)

Bozar can also be recognized by the malformed method in its optional watermark class.
[Bozar watermark class generation](https://github.com/vimasig/Bozar/blob/40f06fed6ef158da3612013e9ac04c51e5cc52bd/src/main/java/io/github/vimasig/bozar/obfuscator/transformer/impl/watermark/DummyClassTransformer.java)

## Potential obfuscator technologies used

Open the **Potential obfuscator technologies used** tab and click **Run technique scan** to check for string concealment,
number obfuscation, opaque branches, custom dynamic linkage, native methods, class packing, and identifiers with
invisible characters. This scan can find techniques in an archive that has no recognized obfuscator fingerprint.

The report groups counts and example locations by resource, including embedded archives and multi-release classes.
Each count represents a matching bytecode pattern. Ordinary code can use some of the same patterns, including JNI and
custom bootstraps. **Copy technique report** copies the findings and their example locations.

Enable **In-depth control flow scan** before running the technique scan to trace integer values through local variables,
the operand stack, and control-flow joins. The report can then flag fixed conditional branches, switches with a known
selector or a single destination, and instructions unreachable from the method entry. It lists how many candidate
methods were analyzed or skipped. Complex or unsupported methods retain their regular scan results.

Use **Analysis → Deobfuscation → Simplify control flow** to preview bytecode changes from opaque predicate folding and
dead-code cleanup. The technique scan provides read-only evidence to help decide where to apply those passes.

**Algebraic opaque predicate folding** also simplifies pure JVM int and long expressions within one basic block.
It uses a bounded Java e-graph inspired by the Rust project egg to find equivalent expressions. For unresolved
integer branches, Z3 looks for a counterexample under JVM overflow, signed comparison, and shift rules. Only a proof
can fold a symbolic branch. Solver work is bounded per check and per class, and transformations use Recaf's
class-level parallelism. [egg](https://github.com/egraphs-good/egg) · [Z3](https://github.com/Z3Prover/z3)

## Method flow analysis

Select a method or a call site in **Bytecode investigation**, then click **Analyze method flow**. Use **Instruction index**
to select the sink for a backward slice. **Slice criterion** selects the instruction or a particular local or operand
stack value just before it runs. Indices count JVM opcodes and omit labels, line numbers, and stack map frames.
Stack slot 0 is the bottom of the stack, with one entry per value. **Copy details** copies the current analysis.

The report shows reaching definitions for each local or stack slot, the definitions the selected instruction consumes,
and the instructions that use its output. Abstract values include known numbers and strings, and available nullability.
The backward slice follows data and control dependencies, including the possible origins of caught exceptions.
It includes potentially aliasing field and array writes that can reach a read in the same method, including
through loops and handlers. It treats calls and dynamic constants as opaque heap reads and writes. A slice can
flag a dependency on heap state at method entry. Heap aliasing is conservative and callee bodies are outside
the scope of the slice.

Dominance and postdominance use structural branch targets and modeled exception edges. The scan resolves handler types
for integer division, remainder, array length, and monitor entry. Other typed handlers are conservative. Uncaught
exceptions flow to a shared exit. Regions with no path to an exit are reported separately and use synthetic exit edges
to compute conservative control dependencies.

The summary lists reachable return sites and values, possible escaping exceptions, direct calls and dynamic call sites,
field reads and writes, allocations, array writes, and monitor operations. Calls and heap effects are summarized locally.

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
