package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;

/**
 * runTransformers' catch (UnsupportedOperationException e) unconditionally calls e.getMessage().contains(...), which
 * throws a NullPointerException if the transformer's UnsupportedOperationException has a null message - masking
 * whatever the transformer actually threw with an unrelated NPE instead.
 */
class TransformerUnsupportedOperationNullMessageTest {

    /** Throws an UnsupportedOperationException with a null message, unrelated to the "requires ASM" case. */
    public static final class NullMessageUoeTransformer implements IClassTransformer {
        public NullMessageUoeTransformer() {}

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (transformedName != null && transformedName.endsWith(".Trigger")) {
                throw new UnsupportedOperationException();
            }
            return bytes;
        }
    }

    @Test
    void nullMessageDoesNotThrowNpe() throws Exception {
        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            lcl.registerTransformer(NullMessageUoeTransformer.class.getName());
            final ClassNotFoundException ex =
                    assertThrows(ClassNotFoundException.class, () -> lcl.findClass("net.minecraft.rfbtest4.Trigger"));
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertInstanceOf(
                    UnsupportedOperationException.class,
                    root,
                    "the original UnsupportedOperationException should propagate, not be masked by an NPE from"
                            + " e.getMessage().contains(...) on a null message");
        }
    }
}
