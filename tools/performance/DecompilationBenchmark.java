import com.google.gson.Gson;
import com.sun.management.ThreadMXBean;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.cfr.CfrConfig;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.procyon.ProcyonConfig;
import software.coley.recaf.services.decompile.procyon.ProcyonDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerConfig;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.services.json.GsonProvider;
import software.coley.recaf.services.json.GsonProviderConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Standalone decompilation benchmark: never loads or executes code from the input JAR.
 * <p>
 * Arguments: {@code decompiler input warmup samples [classLimit]}. The input may name a JAR inside another JAR as
 * {@code outer.jar!path/inner.jar}. Each pass decompiles every top-level class once, on one thread, in name order.
 */
public class DecompilationBenchmark {
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    /** Stack traces CFR prints for failed methods include Recaf source line numbers, which differ between variants. */
    private static final Pattern SOURCE_LINE = Pattern.compile("\\(([A-Za-z0-9_$]+)\\.java:\\d+\\)");
    /**
     * CFR names some synthetic variables {@code v<number>} from a counter shared by every decompilation in the process,
     * so the numbers depend on what was decompiled before. They are renumbered in order of appearance.
     */
    private static final Pattern CFR_SYNTHETIC_VARIABLE = Pattern.compile("\\bv(\\d+)\\b");
    /**
     * When CFR gives up on a method it prints which blocks it could not close. Those follow hash set iteration over
     * identity hash codes, so both their order and the blocks themselves change with whatever the JVM ran before,
     * even between two passes of one process. Only CFR's failure comments contain this text.
     */
    private static final Pattern CFR_BLOCK_LIST = Pattern.compile("Tried to end blocks [^\\n]*");

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String input = args[1];
        int warmup = Integer.parseInt(args[2]);
        int samples = Integer.parseInt(args[3]);
        int limit = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE;

        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (byte[] bytecode : readClasses(input))
            bundle.initialPut(new JvmClassInfoBuilder(bytecode).build());
        Workspace workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build());
        BasicWorkspaceManager manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        manager.setCurrentIgnoringConditions(workspace);
        JvmDecompiler decompiler = switch (mode) {
            case "cfr" -> new CfrDecompiler(manager, new CfrConfig());
            case "vineflower" -> new VineflowerDecompiler(new VineflowerConfig());
            case "procyon" -> new ProcyonDecompiler(new GsonProvider(new GsonProviderConfig()), new ProcyonConfig());
            default -> throw new IllegalArgumentException("Unknown decompiler: " + mode);
        };
        List<JvmClassInfo> targets = bundle.values().stream()
                .filter(c -> !c.isInnerClass() && !c.getName().contains("$"))
                .sorted(Comparator.comparing(JvmClassInfo::getName))
                .limit(limit)
                .toList();

        List<Trial> trials = new ArrayList<>();
        List<String> expected = null;
        for (int i = -warmup; i < samples; i++) {
            List<String> outputs = new ArrayList<>(targets.size());
            Trial trial = run(decompiler, workspace, targets, outputs);
            if (expected != null && !expected.equals(outputs))
                throw new AssertionError("Non-deterministic decompilation output: " + firstDifference(targets, expected, outputs));
            expected = outputs;
            if (i >= 0) trials.add(trial);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("input", input);
        result.put("classes", targets.size());
        result.put("trials", trials);
        System.out.println("PERF " + new Gson().toJson(result));
    }

    private static String firstDifference(List<JvmClassInfo> targets, List<String> a, List<String> b) {
        for (int i = 0; i < targets.size(); i++) {
            if (a.get(i).equals(b.get(i)))
                continue;
            String[] linesA = a.get(i).split("\n");
            String[] linesB = b.get(i).split("\n");
            for (int line = 0; line < Math.min(linesA.length, linesB.length); line++)
                if (!linesA[line].equals(linesB[line]))
                    return targets.get(i).getName() + " line " + (line + 1) + ":\n  " + linesA[line].trim() + "\n  " + linesB[line].trim();
            return targets.get(i).getName() + " (length differs)";
        }
        return "none";
    }

    private static Trial run(JvmDecompiler decompiler, Workspace workspace, List<JvmClassInfo> targets,
                             List<String> normalizedOutputs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int failures = 0;
        long chars = 0;
        System.gc();
        long allocatedBefore = ALLOCATION.getTotalThreadAllocatedBytes();
        long start = System.nanoTime();
        List<String> outputs = new ArrayList<>(targets.size());
        for (JvmClassInfo target : targets) {
            DecompileResult result;
            try {
                result = decompiler.decompile(workspace, target);
            } catch (Throwable t) {
                outputs.add("THROWN " + t.getClass().getName());
                failures++;
                continue;
            }
            String text = result.getText();
            if (text == null) {
                failures++;
                outputs.add("FAILED");
            } else {
                chars += text.length();
                outputs.add(text);
            }
        }
        long nanos = System.nanoTime() - start;
        long allocated = ALLOCATION.getTotalThreadAllocatedBytes() - allocatedBefore;
        for (int i = 0; i < targets.size(); i++) {
            String normalized = normalize(outputs.get(i));
            normalizedOutputs.add(normalized);
            digest.update(targets.get(i).getName().getBytes(StandardCharsets.UTF_8));
            digest.update(normalized.getBytes(StandardCharsets.UTF_8));
        }
        return new Trial(nanos, allocated, failures, chars, HexFormat.of().formatHex(digest.digest()));
    }

    private static String normalize(String text) {
        text = SOURCE_LINE.matcher(text).replaceAll("($1.java)");
        Map<String, String> renumbered = new HashMap<>();
        text = CFR_SYNTHETIC_VARIABLE.matcher(text)
                .replaceAll(match -> renumbered.computeIfAbsent(match.group(), k -> "v#" + renumbered.size()));
        return CFR_BLOCK_LIST.matcher(text).replaceAll("Tried to end blocks (varies)");
    }

    private static List<byte[]> readClasses(String input) throws Exception {
        int split = input.indexOf('!');
        byte[] archive = Files.readAllBytes(Path.of(split < 0 ? input : input.substring(0, split)));
        if (split >= 0)
            archive = readEntry(new ByteArrayInputStream(archive), input.substring(split + 1));
        List<byte[]> classes = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/"))
                    classes.add(zip.readAllBytes());
        }
        return classes;
    }

    private static byte[] readEntry(InputStream archive, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
                if (entry.getName().equals(name))
                    return zip.readAllBytes();
        }
        throw new IllegalArgumentException("No entry '" + name + "'");
    }

    private record Trial(long nanos, long allocatedBytes, int failures, long chars, String digest) {}
}
