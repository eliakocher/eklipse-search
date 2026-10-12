package com.eklipse.search.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.MultiStatus;

/**
 * The files of the last searched scope, so typing in the search field doesn't walk the whole workspace for every
 * search: walking the resource tree of a big workspace takes a moment, matching the names of the files found a few
 * milliseconds. Must be registered as a {@link IResourceChangeEvent#POST_CHANGE} listener, it forgets the files when
 * files are added to the scope or removed from it. Thread safe.
 */
public final class ScopeFiles implements IResourceChangeListener {

	/**
	 * @param files the files of the scope, {@code null} while the workspace is walked
	 */
	private record Entry(SearchQuery query, WorkspaceSearchScope scope, int version, IFile[] files) {

		boolean isValidFor(SearchQuery other, int currentVersion) {
			return files != null && version == currentVersion && query.hasSameScope(other);
		}
	}

	private static final int SCOPE_FLAGS = IResourceDelta.OPEN | IResourceDelta.DERIVED_CHANGED | IResourceDelta.TYPE;

	private final AtomicInteger version = new AtomicInteger();
	private volatile Entry entry;

	/**
	 * Returns the files of a scope, walking the workspace only if the files of an earlier call don't fit. A second
	 * caller waits for the walk in progress instead of walking too.
	 *
	 * @param query the query whose include/exclude options define the scope
	 * @param scope the scope of {@code query}
	 * @param status receives problems with the resources visited when the workspace is walked
	 * @return the files of the scope, shared: the array must not be modified
	 */
	public synchronized IFile[] get(SearchQuery query, WorkspaceSearchScope scope, MultiStatus status) {
		Entry cached = entry;
		int current = version.get();
		if (cached != null && cached.isValidFor(query, current)) {
			return cached.files;
		}
		// tells resourceChanged which changes the walk may miss, a change while walking invalidates what it finds
		entry = new Entry(query, scope, current, null);
		IFile[] files = scope.evaluateFilesInScope(status);
		entry = new Entry(query, scope, current, files);
		return files;
	}

	/**
	 * @param query the query whose include/exclude options define the scope
	 * @return {@code true} if {@link #get} returns the files without walking the workspace
	 */
	public boolean isCached(SearchQuery query) {
		Entry cached = entry;
		return cached != null && cached.isValidFor(query, version.get());
	}

	/**
	 * Forgets the files.
	 */
	public void invalidate() {
		version.incrementAndGet();
		entry = null;
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		if (delta != null && changesFiles(delta, entry)) {
			invalidate();
		}
	}

	/**
	 * @param cached the files the change may make outdated, {@code null} to count every file added or removed
	 * @return {@code true} if the change adds files to the scope or removes files from it
	 */
	private static boolean changesFiles(IResourceDelta delta, Entry cached) {
		AtomicBoolean changed = new AtomicBoolean();
		try {
			delta.accept(d -> {
				IResource resource = d.getResource();
				// files that come into scope or leave it without being added or removed; a removed project brings back
				// the folder it was nested in
				if (changed.get() || (d.getFlags() & SCOPE_FLAGS) != 0
						|| resource.getType() == IResource.PROJECT && d.getKind() != IResourceDelta.CHANGED) {
					changed.set(true);
					return false;
				}
				if (resource.getType() != IResource.FILE) {
					return cached == null || !(cached.query.excludeDerived() && resource.isDerived());
				}
				// whether a removed file was hidden or derived can't be told anymore, which globs it matches can
				if (d.getKind() != IResourceDelta.CHANGED
						&& (cached == null || cached.scope.matchesGlobs((IFile) resource))) {
					changed.set(true);
				}
				return false;
			});
		} catch (CoreException e) {
			return true;
		}
		return changed.get();
	}
}
