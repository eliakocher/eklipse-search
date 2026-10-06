package com.eklipse.search.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Measures how long the UI thread is blocked while typing a query that matches a lot, which is what makes macOS show
 * the spinning wheel.
 */
class SearchViewPerformanceTest {

	private static final int FILES = 10000;
	private static final int LINES = 40;
	/** macOS shows the spinning wheel after about 2 s, anything above a few frames feels sluggish. */
	private static final long MAX_ALLOWED_STALL_MS = 250;

	private IProject project;
	private IWorkbenchPage page;
	private SearchView view;
	private long maxStallNanos;
	private long lastBeat;
	private boolean measuring;

	@BeforeEach
	void setUp() throws Exception {
		Path location = Files.createTempDirectory("perf");
		for (int f = 0; f < FILES; f++) {
			Path dir = location.resolve("src/pkg" + f % 50);
			Files.createDirectories(dir);
			StringBuilder content = new StringBuilder();
			// like a big code base: many files with only a couple of hits each
			for (int l = 0; l < LINES; l++) {
				content.append(l % 20 == 3 ? "\tpublic void sendSms" : "\tprivate int counter").append(l)
						.append("(String message) { // line ").append(l).append(" of file ").append(f).append('\n');
			}
			Files.writeString(dir.resolve("File" + f + ".java"), content, StandardCharsets.UTF_8);
		}
		IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription("perf-project");
		description.setLocation(org.eclipse.core.runtime.Path.fromOSString(location.toString()));
		project = ResourcesPlugin.getWorkspace().getRoot().getProject("perf-project");
		project.create(description, null);
		project.open(null);
		project.refreshLocal(IResource.DEPTH_INFINITE, null);
		project.setDefaultCharset("UTF-8", null);

		page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		view = (SearchView) page.showView(SearchView.ID);
		view.getCaseItem().setSelection(false);
		view.getRegexItem().setSelection(false);
		view.getReplaceText().setText("");
		view.getIncludeText().setText("");
		view.getSearchText().setText("");
		drain(500);
	}

	@AfterEach
	void tearDown() throws CoreException {
		measuring = false;
		page.hideView(view);
		project.delete(true, true, null);
	}

	@Test
	void typingAQueryWithManyResultsKeepsTheUiResponsive() {
		startMeasuring();
		long start = System.nanoTime();
		String query = "sendSms";
		for (int i = 1; i <= query.length(); i++) {
			view.getSearchText().setText(query.substring(0, i));
			drain(120); // typing speed
		}
		waitUntil(() -> !view.isSearching(), 60_000);
		long totalMs = (System.nanoTime() - start) / 1_000_000;
		measuring = false;
		long stallMs = maxStallNanos / 1_000_000;
		System.out.println("PERF typing '" + query + "': " + view.getSummary() + ", total " + totalMs
				+ " ms, longest UI freeze " + stallMs + " ms");

		startMeasuring();
		view.getViewer().getControl().setFocus();
		long collapseStart = System.nanoTime();
		view.getViewer().collapseAll();
		drain(200);
		measuring = false;
		System.out.println("PERF collapse all: " + (System.nanoTime() - collapseStart) / 1_000_000
				+ " ms, longest UI freeze " + maxStallNanos / 1_000_000 + " ms");

		assertTrue(stallMs < MAX_ALLOWED_STALL_MS, "UI was blocked for " + stallMs + " ms");
	}

	private void startMeasuring() {
		maxStallNanos = 0;
		lastBeat = System.nanoTime();
		measuring = true;
		Display display = Display.getCurrent();
		display.timerExec(5, new Runnable() {
			@Override
			public void run() {
				long now = System.nanoTime();
				maxStallNanos = Math.max(maxStallNanos, now - lastBeat);
				lastBeat = now;
				if (measuring) {
					display.timerExec(5, this);
				}
			}
		});
	}

	private static void drain(long millis) {
		Display display = Display.getCurrent();
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			if (!display.readAndDispatch()) {
				display.timerExec(5, () -> {
					// wake up
				});
				display.sleep();
			}
		}
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
		long end = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				fail("Timed out");
			}
			drain(20);
		}
	}
}
