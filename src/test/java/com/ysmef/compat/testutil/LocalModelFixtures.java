package com.ysmef.compat.testutil;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads third-party model fixtures from a local, untracked directory. */
public final class LocalModelFixtures {
    private static final String PROPERTY = "ysmef.localModelFixtures";

    private LocalModelFixtures() {}

    public static InputStream open(String resource) {
        if (!resource.startsWith("/")) {
            throw new IllegalArgumentException("fixture path must start with /: " + resource);
        }
        Path root = Path.of(System.getProperty(PROPERTY, ".local-test-fixtures"))
                .toAbsolutePath().normalize();
        Path file = root.resolve(resource.substring(1)).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("fixture path escapes local root: " + resource);
        }
        Assumptions.assumeTrue(Files.isRegularFile(file),
                () -> "local model fixture absent: " + resource + " (set -D" + PROPERTY
                        + "=<directory> or place it under .local-test-fixtures)");
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read local model fixture " + resource, e);
        }
    }
}
