package com.eklipse.search.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The timings of the performance tests, compared with the baseline measured before: a change that makes the view
 * clearly slower fails, also while it stays below the limits at which the view feels sluggish. A run writes its
 * timings to {@code target/performance.properties}; {@code performance-baseline.properties} next to the pom holds the
 * ones to compare with. A timing without a baseline becomes one, {@code -Dperformance.updateBaseline=true} replaces
 * them all, e.g. after a slowdown that's worth it.
 */
final class PerformanceMetrics {

	/** Timings vary from run to run, only a clear slowdown fails. */
	private static final double MAX_SLOWDOWN = 2;
	/** Short timings vary by more than their share, e.g. with a garbage collection. */
	private static final long SLACK_MS = 100;

	private static final Path BASELINE = Path
			.of(System.getProperty("performance.baseline", "performance-baseline.properties"));
	private static final Path RESULTS = Path
			.of(System.getProperty("performance.results", "target/performance.properties"));
	private static final boolean UPDATE_BASELINE = Boolean.getBoolean("performance.updateBaseline");

	private static final Map<String, Long> baseline = load(BASELINE);
	private static final Map<String, Long> measured = new TreeMap<>();

	private PerformanceMetrics() {
	}

	/**
	 * Records a timing and fails if it's clearly slower than its baseline.
	 *
	 * @param name the name of the timing, e.g. {@code typing.longestFreeze}
	 * @param millis the time it took
	 */
	static void record(String name, long millis) {
		measured.put(name, millis);
		Long expected = baseline.get(name);
		System.out.println("PERF " + name + ": " + millis + " ms"
				+ (expected != null ? " (baseline " + expected + " ms)" : " (no baseline yet)"));
		if (expected != null && !UPDATE_BASELINE) {
			long limit = Math.round(expected * MAX_SLOWDOWN) + SLACK_MS;
			assertTrue(millis <= limit, name + " took " + millis + " ms, more than " + limit
					+ " ms for a baseline of " + expected
					+ " ms. If that's worth it, run with -Dperformance.updateBaseline=true");
		}
	}

	/**
	 * Writes the timings of this run, and the new baselines.
	 */
	static void save() throws IOException {
		write(RESULTS, measured, "Timings of the last run of the performance tests in ms");
		Map<String, Long> updated = new TreeMap<>(baseline);
		measured.forEach(UPDATE_BASELINE ? updated::put : updated::putIfAbsent);
		if (!updated.equals(baseline)) {
			write(BASELINE, updated, "Timings the performance tests compare with in ms, see PerformanceMetrics");
			baseline.putAll(updated);
		}
	}

	private static Map<String, Long> load(Path file) {
		Map<String, Long> timings = new TreeMap<>();
		if (Files.exists(file)) {
			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException e) {
				throw new IllegalStateException("Could not read " + file, e);
			}
			properties.forEach((key, value) -> timings.put((String) key, Long.parseLong(((String) value).trim())));
		}
		return timings;
	}

	/**
	 * Writes the timings sorted and without a date, so the baseline only changes in git where a timing does.
	 */
	private static void write(Path file, Map<String, Long> timings, String comment) throws IOException {
		StringBuilder text = new StringBuilder("# ").append(comment).append('\n');
		timings.forEach((name, millis) -> text.append(name).append('=').append(millis).append('\n'));
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}
}
