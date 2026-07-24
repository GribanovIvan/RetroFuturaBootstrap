package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Regression test for the net.minecraft stack-map-frame recompute. A transformer that rewrites a net.minecraft class
 * with COMPUTE_MAXS only (the common coremod pattern) leaves a branch target with no frame; on a class-file version
 * >= 51 the strict verifier rejects it with {@code VerifyError: Expecting a stackmap frame at branch target N}.
 * Nothing throws at transform time, so the COMPUTE_FRAMES retry in {@code runTransformers} never fires. The
 * LaunchClassLoader recompute (rfb.computeMinecraftFrames, on by default) must repair it.
 */
class MinecraftFrameRecomputeTest {

    /** Re-emits a class with COMPUTE_MAXS only, dropping all stack map frames. */
    public static final class StaleFrameTransformer implements IClassTransformer {
        public StaleFrameTransformer() {}

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (bytes == null || transformedName == null || !transformedName.endsWith(".Branchy")) {
                return bytes;
            }
            final ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(cw);
            return cw.toByteArray();
        }
    }

    private static byte[] validBranchyClass() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/rfbtest/Branchy", null, "java/lang/Object", null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // int f(int x) { if (x > 0) return x; return -x; } -- the else-branch target needs a stack map frame on v52.
        final MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(I)I", null, null);
        m.visitCode();
        final Label elseL = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitJumpInsn(Opcodes.IFGT, elseL);
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitInsn(Opcodes.INEG);
        m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(elseL);
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void staleFrameOnMinecraftClassIsRecomputed() throws Exception {
        final Path tmp = Files.createTempDirectory("rfbframes");
        final Path cls = tmp.resolve("net/minecraft/rfbtest/Branchy.class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, validBranchyClass());

        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }
        urls.add(tmp.toUri().toURL());

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            lcl.registerTransformer(StaleFrameTransformer.class.getName());
            final Class<?> c = lcl.findClass("net.minecraft.rfbtest.Branchy");
            // Instantiating forces linking/verification, which is where a bad StackMapTable throws.
            final Object inst = c.getDeclaredConstructor().newInstance();
            final Object result = c.getMethod("f", int.class).invoke(inst, -5);
            assertEquals(5, result);
        }
    }
}
