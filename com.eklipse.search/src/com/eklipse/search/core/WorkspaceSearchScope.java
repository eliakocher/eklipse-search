package com.eklipse.search.core;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.search.core.text.TextSearchScope;

/**
 * All open projects of the workspace, filtered by include/exclude globs.
 * <p>
 * Folders that are themselves the location of another open project (nested Maven modules imported as separate
 * projects) are skipped, so every file is reported once, under its innermost project.
 */
public final class WorkspaceSearchScope extends TextSearchScope {

	private static final Set<String> IGNORED_FOLDERS = Set.of(".git", ".svn", ".hg");

	private final GlobFilter includes;
	private final GlobFilter excludes;
	private final boolean excludeDerived;
	private final Set<IPath> projectLocations = new HashSet<>();

	/**
	 * @param includes globs a file must match, empty to accept all files
	 * @param excludes globs of files and folders to skip
	 * @param excludeDerived whether derived resources are skipped
	 */
	public WorkspaceSearchScope(GlobFilter includes, GlobFilter excludes, boolean excludeDerived) {
		this.includes = includes;
		this.excludes = excludes;
		this.excludeDerived = excludeDerived;
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			IPath location = project.isOpen() ? project.getLocation() : null;
			if (location != null) {
				projectLocations.add(location);
			}
		}
	}

	/**
	 * @param query the query providing the include/exclude options
	 * @return the scope for the query
	 */
	public static WorkspaceSearchScope of(SearchQuery query) {
		return new WorkspaceSearchScope(GlobFilter.parse(query.includes()), GlobFilter.parse(query.excludes()),
				query.excludeDerived());
	}

	@Override
	public IResource[] getRoots() {
		return new IResource[] { ResourcesPlugin.getWorkspace().getRoot() };
	}

	@Override
	public boolean contains(IResourceProxy proxy) {
		if (proxy.isHidden() || proxy.isTeamPrivateMember() || excludeDerived && proxy.isDerived()) {
			return false;
		}
		switch (proxy.getType()) {
			case IResource.FILE:
				return acceptsFile(relativePath(proxy.requestFullPath()));
			case IResource.FOLDER:
				return !IGNORED_FOLDERS.contains(proxy.getName())
						&& !excludes.matches(relativePath(proxy.requestFullPath()))
						&& !isNestedProjectFolder(proxy.requestResource());
			default:
				return true;
		}
	}

	/**
	 * Checks a single file including all of its parent folders, used to decide whether a changed file needs to be
	 * searched again.
	 *
	 * @param file the file to check
	 * @return {@code true} if a full search would visit the file
	 */
	public boolean containsFile(IFile file) {
		if (!file.isAccessible() || file.isHidden(IResource.CHECK_ANCESTORS)
				|| file.isTeamPrivateMember(IResource.CHECK_ANCESTORS)
				|| excludeDerived && file.isDerived(IResource.CHECK_ANCESTORS)) {
			return false;
		}
		for (IContainer parent = file.getParent(); parent.getType() == IResource.FOLDER; parent = parent.getParent()) {
			if (IGNORED_FOLDERS.contains(parent.getName()) || isNestedProjectFolder(parent)) {
				return false;
			}
		}
		return acceptsFile(relativePath(file.getFullPath()));
	}

	private boolean acceptsFile(String path) {
		return !excludes.matches(path) && (includes.isEmpty() || includes.matches(path));
	}

	private boolean isNestedProjectFolder(IResource folder) {
		IPath location = folder.getLocation();
		return location != null && projectLocations.contains(location);
	}

	private static String relativePath(IPath fullPath) {
		return fullPath.makeRelative().toString();
	}
}
