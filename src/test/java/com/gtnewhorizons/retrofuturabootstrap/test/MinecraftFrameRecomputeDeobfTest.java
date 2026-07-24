package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * Regression test for the deobfuscation-awareness half of the frame recompute: a class stored on disk under an
 * obfuscated name, remapped to a live net.minecraft.* name by a rename transformer, with a branch-merge method that
 * has no stack map frame at all (a COMPUTE_MAXS-only transform, the common coremod pattern). Producing that frame is
 * left entirely to LaunchClassLoader's recompute, so getCommonSuperClass(SubA, SubB) must resolve in the live
 * namespace or the emitted frame references the obfuscated supertype, which the JVM can never define -
 * NoClassDefFoundError for the obfuscated name (this is what findClassMetadata's obf fallback fixes).
 *
 * <p>SafeAsmClassWriter#getCommonSuperClass resolves types via Main.launchLoader (a static field), so it must be set
 * to the LaunchClassLoader under test - otherwise it silently falls back to a resolution path that can't see these
 * classes either, and the recompute's own error handling swallows the resulting failure into a false pass.
 */
class MinecraftFrameRecomputeDeobfTest {

    /** Remaps obfuscated a/b/c/d to live net.minecraft.rfbtest.* names; does not touch frames. */
    public static final class RenameTransformer implements IClassTransformer, IClassNameTransformer {
        private static final java.util.Map<String, String> OBF_TO_LIVE = new java.util.HashMap<>();
        private static final java.util.Map<String, String> LIVE_TO_OBF_DOT = new java.util.HashMap<>();
        private static final java.util.Map<String, String> OBF_TO_LIVE_DOT = new java.util.HashMap<>();

        static {
            put("a", "net/minecraft/rfbtest2/Base");
            put("b", "net/minecraft/rfbtest2/SubA");
            put("c", "net/minecraft/rfbtest2/SubB");
            put("d", "net/minecraft/rfbtest2/Merger");
        }

        private static void put(String obf, String live) {
            OBF_TO_LIVE.put(obf, live);
            OBF_TO_LIVE_DOT.put(obf.replace('/', '.'), live.replace('/', '.'));
            LIVE_TO_OBF_DOT.put(live.replace('/', '.'), obf.replace('/', '.'));
        }

        public RenameTransformer() {}

        @Override
        public String unmapClassName(String name) {
            final String obf = LIVE_TO_OBF_DOT.get(name);
            return obf != null ? obf : name;
        }

        @Override
        public String remapClassName(String name) {
            final String live = OBF_TO_LIVE_DOT.get(name);
            return live != null ? live : name;
        }

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (bytes == null) {
                return null;
            }
            final ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(OBF_TO_LIVE)), 0);
            return writer.toByteArray();
        }
    }

    private static byte[] classNoFrame(String internalName, String superInternalName) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, superInternalName, null);
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternalName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** obf Merger (d): pick(boolean) merges NEW b()/NEW c() into a common `a`-typed return, with NO frame at the merge. */
    private static byte[] obfMergerNoFrame() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "d", null, "java/lang/Object", null);

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "pick", "(Z)La;", null, null);
        m.visitCode();
        final Label elseL = new Label();
        final Label endL = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitJumpInsn(Opcodes.IFEQ, elseL);
        m.visitTypeInsn(Opcodes.NEW, "b");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "b", "<init>", "()V", false);
        m.visitJumpInsn(Opcodes.GOTO, endL);
        m.visitLabel(elseL);
        m.visitTypeInsn(Opcodes.NEW, "c");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "c", "<init>", "()V", false);
        m.visitLabel(endL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void obfuscatedSupertypeIsNotBakedIntoRecomputedFrame() throws Exception {
        final Path tmp = Files.createTempDirectory("rfbframesdeobf");
        Files.write(tmp.resolve("a.class"), classNoFrame("a", "java/lang/Object"));
        Files.write(tmp.resolve("b.class"), classNoFrame("b", "a"));
        Files.write(tmp.resolve("c.class"), classNoFrame("c", "a"));
        Files.write(tmp.resolve("d.class"), obfMergerNoFrame());

        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }
        urls.add(tmp.toUri().toURL());

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            // SafeAsmClassWriter#getCommonSuperClass resolves via this static; without it set, resolution silently
            // takes a different path that can't see these classes, and the failure gets swallowed as a false pass.
            Main.launchLoader = lcl;
            try {
                lcl.registerTransformer(RenameTransformer.class.getName());
                final Class<?> c = lcl.findClass("net.minecraft.rfbtest2.Merger");
                final Object inst = c.getDeclaredConstructor().newInstance();
                final Object picked = c.getMethod("pick", boolean.class).invoke(inst, true);
                assertEquals("net.minecraft.rfbtest2.SubA", picked.getClass().getName());
            } finally {
                Main.launchLoader = null;
            }
        }
    }
}
