package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalNameMappingTransformer;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.transform.JvmTransformerContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class IllegalNameMappingTest extends TransformerTestBase {
	@ParameterizedTest
	@ValueSource(strings = {" ", "\u2000", "if"})
	void repeatedPassesReuseMemberMappings(String illegalName) throws Exception {
		String owner = "net/bundle/pantry/qj";
		String methodDesc = "()Ljava/lang/Object;";
		putClass(owner, node -> {
			node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, illegalName, "I", null, null));
			MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, illegalName, methodDesc, null, null);
			method.instructions.add(new InsnNode(ACONST_NULL));
			method.instructions.add(new InsnNode(ARETURN));
			method.maxStack = 1;
			node.methods.add(method);
		});
		var workspace = workspaceManager.getCurrent();
		var resource = workspace.getPrimaryResource();
		var bundle = resource.getJvmClassBundle();
		var transformer = new IllegalNameMappingTransformer();
		var context = new JvmTransformerContext(workspace, resource, List.of(transformer));
		transformer.transform(context, workspace, resource, bundle, get(owner));
		String fieldName = context.getMappings().getMappedFieldName(owner, illegalName, "I");
		String methodName = context.getMappings().getMappedMethodName(owner, illegalName, methodDesc);
		assertNotNull(fieldName);
		assertNotNull(methodName);

		transformer.transform(context, workspace, resource, bundle, get(owner));
		var applier = recaf.get(MappingApplierService.class).inWorkspace(workspace);
		assertDoesNotThrow(() -> applier.applyToPrimaryResource(context.getMappings()).apply());
		assertEquals(1, context.getMappings().getClassFieldMappings(owner).size());
		assertEquals(1, context.getMappings().getClassMethodMappings(owner).size());
		assertNotNull(get(owner).getDeclaredField(fieldName, "I"));
		assertNotNull(get(owner).getDeclaredMethod(methodName, methodDesc));
	}
}
