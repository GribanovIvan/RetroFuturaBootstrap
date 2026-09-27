package com.gtnewhorizons.retrofuturabootstrap.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;

/**
 * When a transformer throws while findClass is loading a class, the failure must be remembered in invalidClasses, the
 * same as LaunchWrapper did. Otherwise every later lookup of the same name re-runs the whole transformer chain and logs
 * a full stack trace again. Galacticraft probes a class that isn't in its release jar with Class.forName on every
 * render tick, which turned into ~30 stack traces per second and several GB of debug log per session.
 */
class InvalidClassCacheOnTransformerErrorTest {

    static final String MISSING = "net.minecraft.rfbtest6.MissingProbe";
    static final AtomicInteger calls = new AtomicInteger();

    /** Fails on the probed class, the way a transformer that doesn't expect missing/empty class bytes does. */
    public static final class FailOnMissingClassTransformer implements IClassTransformer {
        public FailOnMissingClassTransformer() {}

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (MISSING.equals(transformedName)) {
                calls.incrementAndGet();
                throw new ArrayIndexOutOfBoundsException("Index 6 out of bounds for length 0");
            }
            return bytes;
        }
    }

    @Test
    void failedTransformIsCachedAsInvalidClass() throws Exception {
        final List<URL> urls = new ArrayList<>();
        for (final String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(e).toURI().toURL());
        }
        calls.set(0);

        try (LaunchClassLoader lcl = new LaunchClassLoader(urls.toArray(new URL[0]))) {
            lcl.registerTransformer(FailOnMissingClassTransformer.class.getName());
            for (int i = 0; i < 3; i++) {
                assertThrows(ClassNotFoundException.class, () -> lcl.findClass(MISSING));
            }
            assertEquals(
                    1,
                    calls.get(),
                    "the transformer should run once; later lookups of a name that already failed should be served"
                            + " from invalidClasses instead of re-running the transformer chain");
        }
    }
}
