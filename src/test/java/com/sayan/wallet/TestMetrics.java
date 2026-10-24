package com.sayan.wallet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// Appends measured values (latencies, counts) to target/test-metrics.tsv for the test report.
final class TestMetrics {
    private static final Path FILE = Path.of("target", "test-metrics.tsv");

    private TestMetrics() {}

    static synchronized void record(String testCase, String key, Object value) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, testCase + "\t" + key + "\t" + value + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
