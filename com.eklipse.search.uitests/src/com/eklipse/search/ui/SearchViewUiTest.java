package com.eklipse.search.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartSashContainerElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.undo.WorkspaceUndoUtil;
import org.eclipse.ui.intro.IIntroPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.LineMatch;

/**
 * Drives the view in a real workbench and saves screenshots to {@code target/screenshots}.
 */
class SearchViewUiTest {

	private static final String CONFIGURATION = """
			package com.example;

			/**
			 * Configuration of the SMS channel.
			 */
			public class ExternalMessengerConfiguration {

				/** Failure message when an SMS could not be sent. */
				public static final String SMS_SENT_FAILED = "smsSentFailed";

				private String smsTwilioAccountSid;
				private String smsSender = "Acme";

				public String getSmsTwilioAccountSid() {
					return smsTwilioAccountSid;
				}

				public void sendSms(String message) {
					// likelihood that an incoming SMS message is spam
					System.out.println("Sending SMS: " + message);
				}
			}
			""";
	private static final String SENDER = """
			package com.example;

			public class SmsSender {

				private final ExternalMessengerConfiguration configuration = new ExternalMessengerConfiguration();

				public void send(String text) {
					configuration.sendSms(text);
				}
			}
			""";
	private static final String README = "# SMS channel\n\nThe sms channel sends messages through Twilio. See smsTwilioAccountSid.\n";

	private IProject project;
	private IWorkbenchPage page;
	private SearchView view;

	@BeforeEach
	void setUp() throws Exception {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IIntroPart intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
		if (intro != null) {
			PlatformUI.getWorkbench().getIntroManager().closeIntro(intro);
		}
		window.getShell().setBounds(40, 40, 1280, 820);
		page = window.getActivePage();

		IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription("demo-project");
		description.setLocation(org.eclipse.core.runtime.Path.fromOSString(Files.createTempDirectory("demo").toString()));
		project = ResourcesPlugin.getWorkspace().getRoot().getProject("demo-project");
		project.create(description, null);
		project.open(null);
		project.setDefaultCharset("UTF-8", null);
		createFile("src/com/example/ExternalMessengerConfiguration.java", CONFIGURATION);
		createFile("src/com/example/SmsSender.java", SENDER);
		createFile("docs/README.md", README);
		createFile("target/generated.txt", "generated sms output\n");
		project.getFolder("target").setDerived(true, null);

		view = (SearchView) page.showView(SearchView.ID);
		// a view reopened in the same session restores its previous state
		view.getCaseItem().setSelection(false);
		view.getRegexItem().setSelection(false);
		view.getReplaceText().setText("");
		view.getIncludeText().setText("");
		view.getSearchText().setText("");
		view.setPreviewVisible(true);
		processEvents();
	}

	@AfterEach
	void tearDown() throws CoreException {
		page.closeAllEditors(false);
		page.hideView(view);
		project.delete(true, true, null);
	}

	@Test
	void searchToggleReplaceUndoAndOpen() throws Exception {
		waitForSearch();
		screenshot(view.getRoot(), "0-empty");
		view.activateSearch("sms");
		waitForSearch();
		assertEquals("16 results in 3 files", view.getSummary());
		screenshot(view.getRoot(), "1-search");

		toggle(view.getCaseItem());
		waitForSearch();
		assertEquals("6 results in 2 files", view.getSummary());
		screenshot(view.getRoot(), "2-match-case");

		if (!view.getReplaceText().isVisible()) {
			toggle(view.getReplaceToggleItem());
		}
		view.getReplaceText().setText("text");
		waitUntil(() -> false, 300);
		screenshot(view.getRoot(), "3-replace-preview");

		view.replaceAll(false);
		waitUntil(() -> "No results found.".equals(view.getSummary()), 10_000);
		assertEquals(CONFIGURATION.replace("smsSentFailed", "textSentFailed")
				.replace("smsTwilioAccountSid", "textTwilioAccountSid").replace("smsSender", "textSender"),
				read("src/com/example/ExternalMessengerConfiguration.java"));
		assertEquals(README.replace("The sms", "The text").replace("See sms", "See text"), read("docs/README.md"));
		screenshot(view.getRoot(), "4-after-replace");

		// Edit > Undo while the view is active uses the workspace undo context
		OperationHistoryFactory.getOperationHistory().undo(WorkspaceUndoUtil.getWorkspaceUndoContext(), null, null);
		assertEquals(CONFIGURATION, read("src/com/example/ExternalMessengerConfiguration.java"));
		assertEquals(README, read("docs/README.md"));
		waitUntil(() -> "6 results in 2 files".equals(view.getSummary()), 10_000);

		// without this, the bare platform hands .md files to the macOS default app
		IDE.setDefaultEditor(project.getFile("docs/README.md"), "org.eclipse.ui.DefaultTextEditor");
		FileMatch readme = view.getResult().get(project.getFile("docs/README.md"));
		assertNotNull(readme);
		LineMatch match = readme.getMatches().get(1);
		view.getViewer().setSelection(new StructuredSelection(match), true);
		view.openSelectionInEditor();
		processEvents();
		ITextEditor editor = (ITextEditor) page.getActiveEditor();
		assertNotNull(editor);
		ITextSelection selection = (ITextSelection) editor.getSelectionProvider().getSelection();
		assertEquals(match.getOffset(), selection.getOffset());
		assertEquals("sms", selection.getText());
	}

	@Test
	void dockedAsRightSideBar() throws Exception {
		// open it like Cmd+Alt+Shift+F does
		page.hideView(view);
		page.getWorkbenchWindow().getService(IHandlerService.class).executeCommand("com.eklipse.search.open",
				null);
		view = (SearchView) page.findView(SearchView.ID);
		assertNotNull(view);

		// a docked view: it lives in a part stack of the workbench window, not in a floating window
		MPart part = view.getSite().getService(MPart.class);
		EModelService modelService = page.getWorkbenchWindow().getService(EModelService.class);
		MWindow workbenchWindow = page.getWorkbenchWindow().getService(MWindow.class);
		assertSame(workbenchWindow, modelService.getTopLevelWindowFor(part));
		// views are shared parts, what sits in the stack is their placeholder
		MStackElement docked = part.getCurSharedRef() != null ? part.getCurSharedRef() : part;
		assertTrue((Object) docked.getParent() instanceof MPartStack, String.valueOf(docked.getParent()));

		// dock it in a slim column right of the editors, like a VS Code side bar
		MPartStack rightStack = modelService.createModelElement(MPartStack.class);
		docked.getParent().getChildren().remove(docked);
		rightStack.getChildren().add(docked);
		rightStack.setSelectedElement(docked);
		modelService.insert(rightStack,
				(MPartSashContainerElement) modelService.find("org.eclipse.ui.editorss", workbenchWindow),
				EModelService.RIGHT_OF, 0.4f);
		processEvents();
		assertSame(workbenchWindow, modelService.getTopLevelWindowFor(part));
		int width = view.getRoot().getSize().x;
		assertTrue(width > 260 && width < 450, "side bar width " + width);

		if (!view.getReplaceText().isVisible()) {
			toggle(view.getReplaceToggleItem());
		}
		view.activateSearch("sms");
		waitForSearch();
		assertEquals("16 results in 3 files", view.getSummary());
		Control[] filters = view.getIncludeText().getParent().getParent().getChildren();
		assertEquals(filters[0].getBounds().y, filters[1].getBounds().y, "include and exclude side by side");
		IFile configuration = project.getFile("src/com/example/ExternalMessengerConfiguration.java");
		click(view.getResult().get(configuration).getMatches().get(2));
		waitUntil(() -> CONFIGURATION.equals(view.getPreview().getTextWidget().getText()), 5_000);
		screenshot(view.getRoot(), "5-sidebar");

		view.getReplaceText().setText("text");
		waitUntil(() -> false, 300);
		screenshot(view.getRoot(), "6-sidebar-replace");

		// a narrower window makes the column narrower: below the breakpoint the option buttons move under their field
		page.getWorkbenchWindow().getShell().setSize(880, 820);
		waitUntil(() -> false, 300);
		Control searchField = view.getSearchText();
		Control options = searchField.getParent().getChildren()[1];
		assertTrue(view.getRoot().getSize().x < 260, "side bar width " + view.getRoot().getSize().x);
		assertTrue(options.getBounds().y > searchField.getBounds().y, "options should wrap below the field");
		assertTrue(filters[1].getBounds().y > filters[0].getBounds().y, "include and exclude should stack");
		screenshot(view.getRoot(), "7-sidebar-very-narrow");
	}

	@Test
	void clickPreviewsOrOpens() throws Exception {
		view.activateSearch("sendSms");
		waitForSearch();
		IFile configuration = project.getFile("src/com/example/ExternalMessengerConfiguration.java");
		// the bare platform has no Java editor and would hand the file to the operating system
		IDE.setDefaultEditor(configuration, "org.eclipse.ui.DefaultTextEditor");
		LineMatch match = view.getResult().get(configuration).getMatches().get(0);

		// with the preview, a click shows the file below the results
		click(match);
		StyledText preview = view.getPreview().getTextWidget();
		waitUntil(() -> CONFIGURATION.equals(preview.getText()), 5_000);
		assertEquals(0, page.getEditorReferences().length, "a click only previews");
		assertEquals(match.getLineNumber() - 1, preview.getLineAtOffset(view.getPreview().getTargetLineOffset()));
		StyleRange highlight = preview.getStyleRangeAtOffset(match.getOffset());
		assertNotNull(highlight, "the match is highlighted");
		assertEquals(SWT.BORDER_SOLID, highlight.borderStyle, "the selected match is boxed");
		int topLine = preview.getTopIndex();
		int visibleLines = preview.getClientArea().height / preview.getLineHeight();
		assertTrue(match.getLineNumber() - 1 >= topLine && match.getLineNumber() - 1 < topLine + visibleLines,
				"the match is scrolled into view");
		screenshot(view.getRoot(), "8-preview");

		// the preview shows unsaved changes of an open editor, like the search
		view.openSelectionInEditor();
		ITextEditor editor = (ITextEditor) page.getActiveEditor();
		assertNotNull(editor, "a double-click opens the editor");
		editor.getDocumentProvider().getDocument(editor.getEditorInput()).replace(0, 0, "// unsaved\n");
		view.activateSearch("sendSms");
		waitForSearch();
		click(view.getResult().get(configuration).getMatches().get(0));
		waitUntil(() -> preview.getText().startsWith("// unsaved"), 5_000);
		page.closeAllEditors(false);

		// without the preview, a click opens the editor
		view.activateSearch("sendSms");
		waitForSearch();
		view.setPreviewVisible(false);
		// a maximized sash form moves the other controls out of sight instead of hiding them
		assertEquals(0, view.getPreview().getControl().getSize().y, "the preview is hidden");
		click(view.getResult().get(configuration).getMatches().get(0));
		processEvents();
		assertEquals(1, page.getEditorReferences().length, "a click opens the editor");
	}

	@Test
	void typingMoreOnlySearchesTheFilesWithMatches() throws Exception {
		IFile plain = createFile("src/com/example/Plain.java", "class Plain {\n}\n");
		IFile hidden = createFile("src/com/example/Hidden.java", "class Hidden { String smsSender; }\n");
		// the bare platform has no Java editor and would hand the files to the operating system
		IDE.setDefaultEditor(plain, "org.eclipse.ui.DefaultTextEditor");
		IDE.setDefaultEditor(hidden, "org.eclipse.ui.DefaultTextEditor");
		// unsaved changes hide the match in Hidden.java from the search
		ITextEditor hiddenEditor = (ITextEditor) IDE.openEditor(page, hidden);
		document(hiddenEditor).set("class Hidden {}\n");
		view.activateSearch("sms");
		waitForSearch();
		assertEquals("16 results in 3 files", view.getSummary());
		assertFalse(view.isNarrowed());

		// changes the narrowing search must see although these files had no matches: a new file, unsaved typing in
		// an editor, an editor closed without saving
		createFile("src/com/example/Late.java", "class Late { void smsSender() {} }\n");
		document((ITextEditor) IDE.openEditor(page, plain)).replace(0, 0, "// smsSender\n");
		page.closeEditor(hiddenEditor, false);
		processEvents();

		view.getSearchText().setText("smsS");
		waitUntil(() -> view.getSummary().startsWith("Searching"), 5_000);
		assertTrue(view.getViewer().getTree().getItemCount() >= 3, "the previous results stay, no blinking empty tree");
		waitForSearch();
		assertTrue(view.isNarrowed());
		assertEquals(Set.of("ExternalMessengerConfiguration.java", "SmsSender.java", "Late.java", "Plain.java",
				"Hidden.java"), resultFileNames());
		String narrowed = view.getSummary();

		// the same as searching everything
		view.getSearchText().setText("");
		waitForSearch();
		view.getSearchText().setText("smsS");
		waitForSearch();
		assertFalse(view.isNarrowed());
		assertEquals(narrowed, view.getSummary());
	}

	@Test
	void invalidRegexShowsAnError() {
		toggle(view.getRegexItem());
		view.getSearchText().setText("sms(");
		waitForSearch();
		assertTrue(view.getSummary().startsWith("Invalid regular expression: Unclosed group"), view.getSummary());
		assertEquals(0, view.getResult().getMatchCount());
	}

	private void toggle(ToolItem item) {
		if ((item.getStyle() & SWT.CHECK) != 0) {
			item.setSelection(!item.getSelection());
		}
		item.notifyListeners(SWT.Selection, new Event());
		processEvents();
	}

	/**
	 * Clicks a result like the mouse does: selects it, then releases the button over it.
	 */
	private void click(Object element) {
		view.getViewer().setSelection(new StructuredSelection(element), true);
		processEvents();
		TreeItem item = (TreeItem) view.getViewer().testFindItem(element);
		Rectangle bounds = item.getBounds();
		Event event = new Event();
		event.button = 1;
		event.count = 1;
		event.x = bounds.x + 5;
		event.y = bounds.y + bounds.height / 2;
		item.getParent().notifyListeners(SWT.MouseUp, event);
		processEvents();
	}

	private void waitForSearch() {
		waitUntil(() -> !view.isSearching(), 10_000);
	}

	private static void processEvents() {
		Display display = Display.getCurrent();
		while (display.readAndDispatch()) {
			// keep going
		}
	}

	private static void waitUntil(BooleanSupplier condition, long timeoutMs) {
		Display display = Display.getCurrent();
		long end = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				if (timeoutMs >= 1000) {
					fail("Timed out");
				}
				return;
			}
			if (!display.readAndDispatch()) {
				display.timerExec(20, () -> {
					// wake up
				});
				display.sleep();
			}
		}
	}

	private static void screenshot(Control control, String name) throws IOException {
		processEvents();
		Point size = control.getSize();
		Image image = new Image(control.getDisplay(), size.x, size.y);
		GC gc = new GC(image);
		try {
			control.print(gc);
		} finally {
			gc.dispose();
		}
		ImageLoader loader = new ImageLoader();
		loader.data = new ImageData[] { image.getImageData(200) };
		image.dispose();
		Path directory = Path.of(System.getProperty("screenshots", "target/screenshots"));
		Files.createDirectories(directory);
		loader.save(directory.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
	}

	private IFile createFile(String path, String content) throws CoreException {
		IFile file = project.getFile(path);
		createFolders(file.getParent());
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
		return file;
	}

	private static IDocument document(ITextEditor editor) {
		return editor.getDocumentProvider().getDocument(editor.getEditorInput());
	}

	private Set<String> resultFileNames() {
		Set<String> names = new TreeSet<>();
		for (Object element : view.getResult().getFiles()) {
			names.add(((FileMatch) element).getFile().getName());
		}
		return names;
	}

	private static void createFolders(IContainer container) throws CoreException {
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
}
