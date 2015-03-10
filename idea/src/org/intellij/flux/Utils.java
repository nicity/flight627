package org.intellij.flux;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Created by maximmossienko on 20/02/15.
 */
public class Utils {
    public static Project findReferencedProject(String projectName) {
        for(Project project: ProjectManager.getInstance().getOpenProjects()) {
            if (!Comparing.equal(project.getName(), projectName)) continue;
            return project;
        }
        return null;
    }

    @Nullable
    public static VirtualFile findReferencedFile(String resourcePath, String projectName) {
        VirtualFile resource = null;
        out:
        for(Project project: ProjectManager.getInstance().getOpenProjects()) {
            if (!Comparing.equal(project.getName(), projectName)) continue;
            for(Module module: ModuleManager.getInstance(project).getModules()) {
                for(VirtualFile contentRoot: ModuleRootManager.getInstance(module).getContentRoots()) {
                    resource = VfsUtil.findRelativeFile(resourcePath, contentRoot);
                    if (resource != null) break out;
                }
            }
        }
        return resource;
    }

    @Nullable
    public static Project findReferencedProject(VirtualFile file) {
        for(Project project: ProjectManager.getInstance().getOpenProjects()) {
            if(ProjectRootManager.getInstance(project).getFileIndex().isInContent(file)) {
                return project;
            }
        }
        return null;
    }

    @Nullable
    static PsiElement getTargetElement(int offset, Project referencedProject, Document document, boolean nameElementAccepted) {
        PsiFile psiFile = PsiDocumentManager.getInstance(referencedProject).getPsiFile(document);
        if (psiFile instanceof PsiCompiledFile) psiFile = ((PsiCompiledFile)psiFile).getDecompiledPsiFile();
        PsiReference referenceAt = psiFile != null ? psiFile.findReferenceAt(offset) : null;
        if (referenceAt == null) {
            if (nameElementAccepted && psiFile != null) {
                PsiNamedElement parent = PsiTreeUtil.getParentOfType(psiFile.findElementAt(offset), PsiNamedElement.class);
                if (parent != null) return parent;
            }
            return null;
        }
        PsiElement resolve = referenceAt.resolve();
        if (resolve == null) {
            if (referenceAt instanceof PsiPolyVariantReference) {
                ResolveResult[] resolveResults = ((PsiPolyVariantReference) referenceAt).multiResolve(false);
                if (resolveResults.length == 0) return null;
                // todo multiple variants
                resolve = resolveResults[0].getElement();
                if (resolve == null) return null;
            } else {
                return null;
            }
        }
        return resolve;
    }
}
