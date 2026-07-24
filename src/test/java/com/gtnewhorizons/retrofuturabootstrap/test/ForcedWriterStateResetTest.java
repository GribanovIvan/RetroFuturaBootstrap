package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.retrofuturabootstrap.asm.SafeAsmClassWriter;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * runTransformers, on a suspected invalid-frame failure, sets SafeAsmClassWriter.forcedFlags/forcedOriginalClass and
 * retries the transform. If that retry also throws, the reset must still run, otherwise the forced state leaks onto the
 * thread and corrupts every following SafeAsmClassWriter (unwanted COMPUTE_FRAMES|COMPUTE_MAXS and a foreign class
 * header pre-visited into the writer).
 */
class ForcedWriterStateResetTest {

    /** Throws a MethodWriter-looking exception first (to trigger the retry), then throws again on the retry. */
    public static final class RetryThrowingTransformer implements IClassTransformer {
        public RetryThrowingTransformer() {}

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (transformedName == null || !transformedName.endsWith(".Dummy")) {
                return bytes;
            }
            if (SafeAsmClassWriter.forcedFlags.get() == 0) {
                // First attempt: look like ASM threw while writing frames, so runTransformers retries.
                throw methodWriterLikeFailure();
            }
            // Retry attempt (forced state is set): fail again so the reset path is what we are testing.
            throw new RuntimeException("simulated retry failure");
        }
    }

    private static byte[] trivialClass() {
        return trivialClass("net/minecraft/rfbtest/Dummy");
    }

    private static byte[] trivialClass(String internalName) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static RuntimeException methodWriterLikeFailure() {
        final RuntimeException ex = new RuntimeException("simulated bad frame");
        ex.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.objectweb.asm.MethodWriter", "computeAllFrames", "MethodWriter.java", 1),
            new StackTraceElement("org.objectweb.asm.MethodWriter", "visitMaxs", "MethodWriter.java", 2),
            new StackTraceElement("x.Y", "z", "Y.java", 3),
        });
        return ex;
    }

    /**
     * Outer's retry loads Inner, which needs a retry of its own. Records the forced state Outer's retry sees once
     * Inner's retry has finished, which must still be Outer's.
     */
    public static final class NestedRetryTransformer implements IClassTransformer {
        static LaunchClassLoader loader;
        static int innerCalls;
        static Integer outerFlagsAfterInner;
        static byte[] outerClassAfterInner;

        public NestedRetryTransformer() {}

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if ("net.minecraft.rfbtest7.Inner".equals(transformedName)) {
                if (innerCalls++ == 0) {
                    throw methodWriterLikeFailure();
                }
                return bytes;
            }
            if ("net.minecraft.rfbtest7.Outer".equals(transformedName)) {
                if (SafeAsmClassWriter.forcedOriginalClass.get() == null) {
                    throw methodWriterLikeFailure();
                }
                try {
                    loader.findClass("net.minecraft.rfbtest7.Inner");
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                outerFlagsAfterInner = SafeAsmClassWriter.forcedFlags.get();
                outerClassAfterInner = SafeAsmClassWriter.forcedOriginalClass.get();
                return bytes;
            }
            return bytes;
        }
    }

    @Test
    void forcedWriterStateIsResetWhenRetryThrows() throws Exception {
        SafeAsmClassWriter.forcedFlags.set(0);
        SafeAsmClassWriter.forcedOriginalClass.set(null);

        final Path tmp = Files.createTempDirectory("rfbforced");
        final Path cls = tmp.resolve("net/minecraft/rfbtest/Dummy.class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, trivialClass());

        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }
        urls.add(tmp.toUri().toURL());

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            lcl.registerTransformer(RetryThrowingTransformer.class.getName());
            assertThrows(Throwable.class, () -> lcl.findClass("net.minecraft.rfbtest.Dummy"));

            assertEquals(
                    0,
                    SafeAsmClassWriter.forcedFlags.get().intValue(),
                    "forcedFlags leaked onto the thread after the retry transform threw");
            assertNull(
                    SafeAsmClassWriter.forcedOriginalClass.get(),
                    "forcedOriginalClass leaked onto the thread after the retry transform threw");
        } finally {
            SafeAsmClassWriter.forcedFlags.set(0);
            SafeAsmClassWriter.forcedOriginalClass.set(null);
        }
    }

    @Test
    void nestedRetryKeepsOuterForcedState() throws Exception {
        SafeAsmClassWriter.forcedFlags.set(0);
        SafeAsmClassWriter.forcedOriginalClass.set(null);

        final Path tmp = Files.createTempDirectory("rfbforcednested");
        final byte[] outer = trivialClass("net/minecraft/rfbtest7/Outer");
        for (final String n : new String[] {"Outer", "Inner"}) {
            final Path cls = tmp.resolve("net/minecraft/rfbtest7/" + n + ".class");
            Files.createDirectories(cls.getParent());
            Files.write(cls, n.equals("Outer") ? outer : trivialClass("net/minecraft/rfbtest7/" + n));
        }

        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }
        urls.add(tmp.toUri().toURL());

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            NestedRetryTransformer.loader = lcl;
            NestedRetryTransformer.innerCalls = 0;
            lcl.registerTransformer(NestedRetryTransformer.class.getName());
            lcl.findClass("net.minecraft.rfbtest7.Outer");

            assertEquals(2, NestedRetryTransformer.innerCalls, "Inner should have gone through the retry");
            assertEquals(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                    NestedRetryTransformer.outerFlagsAfterInner.intValue(),
                    "Inner's retry reset the forced flags while Outer's retry was still running");
            assertArrayEquals(
                    outer,
                    NestedRetryTransformer.outerClassAfterInner,
                    "Inner's retry reset forcedOriginalClass while Outer's retry was still running");
            assertEquals(
                    0, SafeAsmClassWriter.forcedFlags.get().intValue(), "forced state left set after both retries");
            assertNull(SafeAsmClassWriter.forcedOriginalClass.get(), "forced state left set after both retries");
        } finally {
            SafeAsmClassWriter.forcedFlags.set(0);
            SafeAsmClassWriter.forcedOriginalClass.set(null);
        }
    }
}
