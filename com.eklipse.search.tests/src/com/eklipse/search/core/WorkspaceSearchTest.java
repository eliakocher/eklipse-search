package com.eklipse.search.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs searches and replaces against a real workspace.
 */
class WorkspaceSearchTest {

	private static final String JAVA = "class Foo {\n\tFoo foo = new Foo(); // FOO\n}\n";
	private static final String NOTES = "foobar Foo_bar foo.bar\nsecond line foo\n";

	private IProject project;
	private IProject nested;

	@BeforeEach
	void setUp() throws CoreException, IOException {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		// outside the workspace folder, Eclipse doesn't allow nested project locations inside it
		IProjectDescription outer = ResourcesPlugin.getWorkspace().newProjectDescription("search-test");
		outer.setLocation(Path.fromOSString(Files.createTempDirectory("search-test").toString()));
		project = root.getProject("search-test");
		project.create(outer, null);
		project.open(null);
		project.setDefaultCharset("UTF-8", null);
		createFile("src/Foo.java", JAVA);
		createFile("src/notes.txt", NOTES);
		createFile("target/Gen.java", "Foo\n");
		project.getFolder("target").setDerived(true, null);
		createFile("web/node_modules/lib.js", "Foo\n");
		createFile("module/Nested.java", "Foo\n");

		// a nested project pointing to search-test/module, like a Maven module imported separately
		nested = root.getProject("search-nested");
		IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(nested.getName());
		description.setLocation(project.getFolder("module").getLocation());
		nested.create(description, null);
		nested.open(null);
	}

	@AfterEach
	void tearDown() throws CoreException {
		nested.delete(false, true, null);
		project.delete(true, true, null);
	}

	@Test
	void caseInsensitiveSearchSkipsDerivedExcludedAndNestedDuplicates() {
		List<LineMatch> matches = search(new SearchQuery("foo", false, false, false, "", "node_modules", true));

		assertEquals(List.of(
				"search-nested/Nested.java:1:0",
				"search-test/src/Foo.java:1:6", "search-test/src/Foo.java:2:1",
				"search-test/src/Foo.java:2:5", "search-test/src/Foo.java:2:15",
				"search-test/src/Foo.java:2:25",
				"search-test/src/notes.txt:1:0", "search-test/src/notes.txt:1:7",
				"search-test/src/notes.txt:1:15", "search-test/src/notes.txt:2:12"),
				describe(matches));
	}

	@Test
	void previewIsTheTrimmedLine() {
		List<LineMatch> matches = search(new SearchQuery("new", true, false, false, "Foo.java", "", true));

		assertEquals(1, matches.size());
		LineMatch match = matches.get(0);
		assertEquals("Foo foo = new Foo(); // FOO", match.getPreview());
		assertEquals(10, match.getPreviewMatchStart());
		assertEquals(3, match.getPreviewMatchLength());
		assertEquals("new", match.getMatchedText());
	}

	@Test
	void derivedResourcesCanBeIncluded() {
		List<LineMatch> matches = search(new SearchQuery("Foo", true, true, false, "Gen.java", "", false));

		assertEquals(List.of("search-test/target/Gen.java:1:0"), describe(matches));
	}

	@Test
	void scopeChecksSingleFiles() {
		WorkspaceSearchScope scope = WorkspaceSearchScope.of(new SearchQuery("x", false, false, false, "", "node_modules", true));

		assertTrue(scope.containsFile(project.getFile("src/Foo.java")));
		assertFalse(scope.containsFile(project.getFile("target/Gen.java")));
		assertFalse(scope.containsFile(project.getFile("web/node_modules/lib.js")));
		assertFalse(scope.containsFile(project.getFile("module/Nested.java")));
		assertTrue(scope.containsFile(nested.getFile("Nested.java")));
		assertFalse(scope.containsFile(project.getFile("src/Missing.java")));
	}

	@Test
	void fileNamesMatchInTheSameScope() {
		SearchQuery query = new SearchQuery("JAVA", false, false, false, "", "node_modules", true);
		List<String> names = Collections.synchronizedList(new ArrayList<>());

		TextSearcher.search(WorkspaceSearchScope.of(query), query.createPattern(), 1000, match -> {
		}, name -> names.add(name.getFile().getFullPath().makeRelative() + ":" + name.getStart() + "-" + name.getEnd()),
				new NullProgressMonitor());

		// not the derived target/Gen.java, Nested.java once
		names.sort(null);
		assertEquals(List.of("search-nested/Nested.java:7-11", "search-test/src/Foo.java:4-8"), names);
	}

	@Test
	void limitStopsTheSearch() {
		SearchQuery query = new SearchQuery("o", false, false, false, "", "", true);
		List<LineMatch> matches = Collections.synchronizedList(new ArrayList<>());

		TextSearcher.Result result = TextSearcher.search(WorkspaceSearchScope.of(query), query.createPattern(), 3,
				matches::add, new NullProgressMonitor());

		assertTrue(result.limitReached());
		assertEquals(3, matches.size());
	}

	@Test
	void replacePreservingCaseAndUndo() throws Exception {
		SearchQuery query = new SearchQuery("foo", false, true, false, "Foo.java", "", true);
		List<LineMatch> matches = search(query);
		assertEquals(5, matches.size());

		Replacer.Outcome outcome = Replacer.replace(byFile(matches), query.createPattern(), false, "bar", true,
				"Replace foo", new NullProgressMonitor());

		assertEquals(5, outcome.replacedCount());
		assertTrue(outcome.staleFiles().isEmpty());
		assertEquals("class Bar {\n\tBar bar = new Bar(); // BAR\n}\n", read("src/Foo.java"));

		RefactoringCore.getUndoManager().performUndo(null, new NullProgressMonitor());
		assertEquals(JAVA, read("src/Foo.java"));
	}

	@Test
	void filesChangedSinceTheSearchAreSkipped() throws Exception {
		SearchQuery query = new SearchQuery("foo", true, false, false, "notes.txt", "", true);
		List<LineMatch> matches = search(query);
		project.getFile("src/notes.txt").setContents(stream("// edited\n" + NOTES), IResource.FORCE, null);

		Replacer.Outcome outcome = Replacer.replace(byFile(matches), query.createPattern(), false, "bar", false,
				"Replace", new NullProgressMonitor());

		assertEquals(0, outcome.replacedCount());
		assertEquals(List.of(project.getFile("src/notes.txt")), List.copyOf(outcome.staleFiles()));
		assertEquals("// edited\n" + NOTES, read("src/notes.txt"));
	}

	private static List<LineMatch> search(SearchQuery query) {
		List<LineMatch> matches = Collections.synchronizedList(new ArrayList<>());
		TextSearcher.Result result = TextSearcher.search(WorkspaceSearchScope.of(query), query.createPattern(), 1000,
				matches::add, new NullProgressMonitor());
		assertFalse(result.limitReached());
		assertTrue(result.status().isOK(), result.status().toString());
		List<LineMatch> sorted = new ArrayList<>(matches);
		sorted.sort(Comparator.comparing((LineMatch m) -> m.getFile().getFullPath().toString())
				.thenComparingInt(LineMatch::getOffset));
		return sorted;
	}

	/**
	 * @return {@code path:line:column} of each match
	 */
	private static List<String> describe(List<LineMatch> matches) {
		List<String> described = new ArrayList<>();
		for (LineMatch match : matches) {
			described.add(match.getFile().getFullPath().makeRelative() + ":" + match.getLineNumber() + ":"
					+ columnOf(match));
		}
		return described;
	}

	private static int columnOf(LineMatch match) {
		String content = readQuietly(match.getFile());
		int lineStart = content.lastIndexOf('\n', match.getOffset() - 1) + 1;
		return match.getOffset() - lineStart;
	}

	private static Map<IFile, List<LineMatch>> byFile(List<LineMatch> matches) {
		Map<IFile, List<LineMatch>> byFile = new LinkedHashMap<>();
		for (LineMatch match : matches) {
			byFile.computeIfAbsent(match.getFile(), f -> new ArrayList<>()).add(match);
		}
		return byFile;
	}

	private void createFile(String path, String content) throws CoreException {
		IFile file = project.getFile(path);
		createFolders(file.getParent());
		file.create(stream(content), true, null);
	}

	private static void createFolders(org.eclipse.core.resources.IContainer container) throws CoreException {
		if (container instanceof IFolder folder && !folder.exists()) {
			createFolders(folder.getParent());
			folder.create(true, true, null);
		}
	}

	private String read(String path) throws CoreException, IOException {
		try (InputStream in = project.getFile(path).getContents(true)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String readQuietly(IFile file) {
		try (InputStream in = file.getContents(true)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (CoreException | IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static InputStream stream(String content) {
		return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	}
}
