package com.github.squi2rel.vp.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResidenceReflectionTest {
    @TempDir
    Path directory;

    @Test
    void adaptedHandlesSupportPrimitiveVoidAndArraySignatures() {
        ResidenceReflection.Handle booleanMethod = ResidenceReflection.staticMethod(Signatures.class, "positive", boolean.class, int.class);
        assertEquals(true, ResidenceReflection.invoke(booleanMethod, 1));
        assertEquals(false, ResidenceReflection.invoke(booleanMethod, -1));
        Signatures target = new Signatures();
        String[] values = {"one", "two"};
        ResidenceReflection.Handle setter = ResidenceReflection.virtualMethod(Signatures.class, "store", void.class, String[].class);
        assertNull(ResidenceReflection.invoke(setter, target, values));
        assertSame(values, target.values);
    }

    @Test
    void adaptedHandlesPreserveFailureSemantics() {
        ResidenceReflection.Handle checked = ResidenceReflection.staticMethod(Signatures.class, "checked", void.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ResidenceReflection.invoke(checked));
        assertEquals(IOException.class, error.getCause().getClass());
        ResidenceReflection.Handle unchecked = ResidenceReflection.staticMethod(Signatures.class, "unchecked", void.class);
        assertSame(Signatures.FAILURE, assertThrows(IllegalArgumentException.class, () -> ResidenceReflection.invoke(unchecked)));
    }

    public static final class Signatures {
        private static final IllegalArgumentException FAILURE = new IllegalArgumentException("fixture");
        private String[] values;

        public static boolean positive(int value) {
            return value > 0;
        }

        public void store(String[] values) {
            this.values = values;
        }

        public static void checked() throws IOException {
            throw new IOException("fixture");
        }

        public static void unchecked() {
            throw FAILURE;
        }
    }

    @Test
    void exactLookupIgnoresUnrelatedMethodWithMissingOptionalType() throws Exception {
        Path sources = Files.createDirectories(directory.resolve("sources/fixture"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path missingSource = sources.resolve("MissingType.java");
        Path targetSource = sources.resolve("Target.java");
        Files.writeString(missingSource, """
                package fixture;
                public final class MissingType {
                }
                """);
        Files.writeString(targetSource, """
                package fixture;
                public final class Target {
                    public static String availableStatic() {
                        return "static";
                    }
                    public String available() {
                        return "virtual";
                    }
                    public MissingType unavailable() {
                        return null;
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                missingSource.toString(),
                targetSource.toString()
        ));
        Files.delete(classes.resolve("fixture/MissingType.class"));

        URL[] urls = {classes.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> targetType = Class.forName("fixture.Target", true, loader);
            assertThrows(NoClassDefFoundError.class, () -> targetType.getMethods());

            ResidenceReflection.Handle staticMethod = ResidenceReflection.staticMethod(
                    targetType,
                    "availableStatic",
                    String.class
            );
            assertEquals("static", ResidenceReflection.invoke(staticMethod));

            Object target = targetType.getConstructor().newInstance();
            ResidenceReflection.Handle virtualMethod = ResidenceReflection.virtualMethod(
                    targetType,
                    "available",
                    String.class
            );
            assertEquals("virtual", ResidenceReflection.invoke(virtualMethod, target));
        }
    }
}
