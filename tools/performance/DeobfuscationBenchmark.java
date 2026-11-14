import com.google.gson.Gson;
import com.sun.management.ThreadMXBean;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.inheritance.*;
import software.coley.recaf.services.mapping.*;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.services.workspace.*;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Standalone benchmark: never loads or executes code from the input JAR. */
public class DeobfuscationBenchmark implements Opcodes {
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String input = args[1];
        int warmup = Integer.parseInt(args[2]);
        int samples = Integer.parseInt(args[3]);
        List<ClassNode> templates = input.startsWith("synthetic:")
                ? List.of(synthetic(input)) : readJar(Path.of(input));
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (ClassNode node : templates) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            bundle.initialPut(new JvmClassInfoBuilder(writer.toByteArray()).build());
        }
        WorkspaceResource resource = new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build();
        Workspace workspace = new BasicWorkspace(resource);
        BasicWorkspaceManager manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        manager.setCurrentIgnoringConditions(workspace);
        InheritanceGraphService graphs = new InheritanceGraphService(manager,
                new MappingListeners(new MappingListenersConfig()), new InheritanceGraphServiceConfig());
        graphs.getOrCreateInheritanceGraph(workspace);
        List<JvmClassInfo> infos = bundle.values().stream().sorted(Comparator.comparing(JvmClassInfo::getName)).toList();
        List<Trial> trials = new ArrayList<>();
        String expected = null;
        for (int i = -warmup; i < samples; i++) {
            Trial trial = mode.equals("dead-code") ? prune(templates) : fold(workspace, resource, bundle, infos, graphs,
                    mode.equals("bitwise-folding"));
            if (expected != null && !expected.equals(trial.digest()))
                throw new AssertionError("Non-deterministic transformation output");
            expected = trial.digest();
            if (i >= 0) trials.add(trial);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("input", input);
        result.put("classes", templates.size());
        result.put("methods", templates.stream().mapToInt(n -> n.methods.size()).sum());
        result.put("trials", trials);
        System.out.println("PERF " + new Gson().toJson(result));
    }

    private static Trial prune(List<ClassNode> templates) throws Exception {
        List<ClassNode> nodes = new ArrayList<>();
        for (ClassNode template : templates) {
            ClassNode copy = new ClassNode();
            template.accept(copy);
            nodes.add(copy);
        }
        DeadCodeRemovingTransformer transformer = new DeadCodeRemovingTransformer();
        List<String> errors = new ArrayList<>();
        int changed = 0;
        long allocation = allocated();
        long start = System.nanoTime();
        for (ClassNode node : nodes)
            for (MethodNode method : node.methods) {
                try {
                    if (transformer.prune(node, method)) changed++;
                } catch (TransformationException ex) {
                    errors.add(node.name + '.' + method.name + method.desc + ':' + ex);
                }
            }
        long elapsed = System.nanoTime() - start;
        long bytes = allocated() - allocation;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (ClassNode node : nodes) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            digest.update(writer.toByteArray());
        }
        errors.forEach(e -> digest.update(e.getBytes(StandardCharsets.UTF_8)));
        return new Trial(elapsed, bytes, HexFormat.of().formatHex(digest.digest()), errors.size(), changed, 0);
    }

    private static Trial fold(Workspace workspace, WorkspaceResource resource, BasicJvmClassBundle bundle,
                              List<JvmClassInfo> infos, InheritanceGraphService graphs, boolean bitwise) throws Exception {
        OpaqueConstantFoldingTransformer transformer = bitwise ? new BitwiseFoldingTransformer(graphs) : new OpaqueConstantFoldingTransformer(graphs);
        CountingContext context = new CountingContext(workspace, resource, transformer);
        transformer.setup(context, workspace);
        List<String> errors = new ArrayList<>();
        long allocation = allocated();
        long start = System.nanoTime();
        for (JvmClassInfo info : infos) {
            try {
                transformer.transform(context, workspace, resource, bundle, info);
            } catch (TransformationException ex) {
                errors.add(info.getName() + ':' + ex);
            }
        }
        long elapsed = System.nanoTime() - start;
        long bytes = allocated() - allocation;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int changed = 0;
        for (JvmClassInfo info : infos) {
            byte[] output = context.getBytecode(bundle, info);
            digest.update(output);
            if (!Arrays.equals(info.getBytecode(), output)) changed++;
        }
        errors.forEach(e -> digest.update(e.getBytes(StandardCharsets.UTF_8)));
        return new Trial(elapsed, bytes, HexFormat.of().formatHex(digest.digest()), errors.size(), changed, context.analyses);
    }

    private static List<ClassNode> readJar(Path path) throws Exception {
        List<ClassNode> nodes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.stream().filter(e -> e.getName().endsWith(".class") && !e.isDirectory())
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var entry : entries) {
                try (var stream = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(stream.readAllBytes()).accept(node, 0);
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    private static ClassNode synthetic(String input) {
        String[] parts = input.split(":");
        int size = Integer.parseInt(parts[2]);
        ClassNode node = new ClassNode();
        node.version = V17;
        node.access = ACC_PUBLIC;
        node.name = "Synthetic";
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        node.methods.add(method);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(start);
        if (parts[1].equals("dead")) {
            method.instructions.add(new JumpInsnNode(GOTO, end));
            for (int i = 0; i < size; i++) method.instructions.add(new InsnNode(NOP));
        } else if (parts[1].equals("locals")) {
            for (int i = 0; i < size; i++) {
                method.instructions.add(new InsnNode(ICONST_0));
                method.instructions.add(new VarInsnNode(ISTORE, 0));
                method.localVariables.add(new LocalVariableNode("v" + i, "I", null, start, end, 0));
            }
            method.maxLocals = 1;
            method.maxStack = 1;
        } else {
            throw new IllegalArgumentException("Unknown synthetic input: " + input);
        }
        method.instructions.add(end);
        method.instructions.add(new InsnNode(RETURN));
        return node;
    }

    private static long allocated() {
        return ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private record Trial(long nanos, long allocatedBytes, String digest, int failures, int changed, int analyses) {}

    private static class CountingContext extends JvmTransformerContext {
        private int analyses;

        CountingContext(Workspace workspace, WorkspaceResource resource, JvmClassTransformer transformer) {
            super(workspace, resource, transformer);
        }

        @Override
        public Frame<ReValue>[] analyze(InheritanceGraph graph, ClassNode node, MethodNode method)
                throws TransformationException {
            analyses++;
            return super.analyze(graph, node, method);
        }
    }
}
