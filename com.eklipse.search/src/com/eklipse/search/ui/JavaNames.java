package com.eklipse.search.ui;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

/**
 * The Java names of files, from the optional JDT. May only be used while catching {@link LinkageError}.
 */
final class JavaNames {

	private JavaNames() {
	}

	/**
	 * @return {@code true} if one of the files is a Java source file of a Java project, cheap to ask
	 */
	static boolean hasJavaFile(Collection<IFile> files) {
		return files.stream().anyMatch(file -> JavaCore.createCompilationUnitFrom(file) != null);
	}

	/**
	 * @return the fully qualified names of the main types of the Java files, in the order of the files
	 */
	static Set<String> qualifiedNames(Collection<IFile> files) {
		Set<String> names = new LinkedHashSet<>();
		for (IFile file : files) {
			ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
			if (unit == null) {
				continue;
			}
			try {
				IType type = unit.findPrimaryType();
				if (type != null) {
					names.add(type.getFullyQualifiedName());
				}
			} catch (RuntimeException e) {
				// no name for a file that can't be read
			}
		}
		return names;
	}
}
