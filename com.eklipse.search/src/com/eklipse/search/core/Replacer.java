package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;

/**
 * Replaces matches through LTK, so that open editors are updated in place, unsaved editors stay unsaved and the
 * whole operation can be undone like a refactoring.
 */
public final class Replacer {

	private Replacer() {
	}

	/**
	 * What a replace did.
	 *
	 * @param replacedCount the number of replaced matches
	 * @param changedFiles the files that were modified
	 * @param staleFiles the files skipped because their content changed since the search
	 * @param status the validation status of the change, may contain warnings or errors
	 */
	public record Outcome(int replacedCount, Set<IFile> changedFiles, Set<IFile> staleFiles, RefactoringStatus status) {
	}

	/**
	 * Replaces the given matches.
	 *
	 * @param matchesByFile the matches to replace, grouped by file
	 * @param pattern the pattern that found the matches
	 * @param regex whether {@code replacement} is a template with group references
	 * @param replacement the replacement text or template
	 * @param preserveCase whether to adapt the replacement to the case of each match
	 * @param undoLabel the label shown in Edit &gt; Undo
	 * @param monitor the progress monitor
	 * @return what was replaced
	 * @throws CoreException if a file could not be read or written
	 */
	public static Outcome replace(Map<IFile, List<LineMatch>> matchesByFile, Pattern pattern, boolean regex,
			String replacement, boolean preserveCase, String undoLabel, IProgressMonitor monitor) throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor, "Replacing", matchesByFile.size() + 3);
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		CompositeChange change = new CompositeChange(undoLabel);
		Set<IFile> changed = new LinkedHashSet<>();
		Set<IFile> stale = new LinkedHashSet<>();
		int count = 0;
		for (Map.Entry<IFile, List<LineMatch>> entry : matchesByFile.entrySet()) {
			IFile file = entry.getKey();
			IPath path = file.getFullPath();
			manager.connect(path, LocationKind.IFILE, progress.split(1));
			try {
				String content = manager.getTextFileBuffer(path, LocationKind.IFILE).getDocument().get();
				MultiTextEdit edit = createEdit(content, entry.getValue(), pattern, regex, replacement, preserveCase);
				if (edit == null) {
					stale.add(file);
					continue;
				}
				TextFileChange fileChange = new TextFileChange(file.getName(), file);
				fileChange.setEdit(edit);
				fileChange.setSaveMode(TextFileChange.KEEP_SAVE_STATE);
				change.add(fileChange);
				changed.add(file);
				count += edit.getChildrenSize();
			} finally {
				manager.disconnect(path, LocationKind.IFILE, null);
			}
		}
		if (changed.isEmpty()) {
			return new Outcome(0, Set.of(), stale, new RefactoringStatus());
		}
		change.initializeValidationData(progress.split(1));
		PerformChangeOperation operation = new PerformChangeOperation(change);
		operation.setUndoManager(RefactoringCore.getUndoManager(), undoLabel);
		operation.run(progress.split(2));
		RefactoringStatus status = operation.getValidationStatus();
		if (!operation.changeExecuted()) {
			return new Outcome(0, Set.of(), stale, status != null ? status
					: RefactoringStatus.createFatalErrorStatus("The replace could not be performed."));
		}
		return new Outcome(count, changed, stale, status != null ? status : new RefactoringStatus());
	}

	/**
	 * Computes the edits for one file.
	 * <p>
	 * Every match is verified against the current content first. If the file was modified since the search, the
	 * offsets can't be trusted anymore and {@code null} is returned, so nothing gets replaced at a wrong position.
	 *
	 * @param content the current content of the file
	 * @param matches the matches to replace
	 * @param pattern the pattern that found the matches
	 * @param regex whether {@code replacement} is a template with group references
	 * @param replacement the replacement text or template
	 * @param preserveCase whether to adapt the replacement to the case of each match
	 * @return the edits, {@code null} if the content no longer matches the search results
	 */
	public static MultiTextEdit createEdit(String content, List<LineMatch> matches, Pattern pattern, boolean regex,
			String replacement, boolean preserveCase) {
		List<LineMatch> sorted = new ArrayList<>(matches);
		sorted.sort(Comparator.comparingInt(LineMatch::getOffset));
		Matcher matcher = regex ? pattern.matcher(content) : null;
		MultiTextEdit edit = new MultiTextEdit();
		int previousEnd = 0;
		for (LineMatch match : sorted) {
			int offset = match.getOffset();
			int end = offset + match.getLength();
			if (offset < previousEnd || end > content.length() || !content.startsWith(match.getMatchedText(), offset)) {
				return null;
			}
			String value = replacement;
			if (matcher != null) {
				// find(offset) sees the whole content, so look-behinds and anchors behave like in the search
				if (!matcher.find(offset) || matcher.start() != offset || matcher.end() != end) {
					return null;
				}
				value = ReplacementTemplate.expand(replacement, matcher);
			}
			if (preserveCase) {
				value = PreserveCase.apply(match.getMatchedText(), value);
			}
			edit.addChild(new ReplaceEdit(offset, match.getLength(), value));
			previousEnd = end;
		}
		return edit;
	}
}
