/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.copy;

import com.intellij.CommonBundle;
import com.intellij.ide.CopyPasteDelegator;
import com.intellij.ide.util.EditorHelper;
import com.intellij.ide.util.PlatformPackageUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.encoding.EncodingRegistry;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiDirectoryFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class CopyFilesOrDirectoriesHandler extends CopyHandlerDelegateBase {
  private static Logger LOG = Logger.getInstance("com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler");

  @Override
  public boolean canCopy(PsiElement[] elements, boolean fromUpdate) {
    Set<String> names = new HashSet<>();
    for (PsiElement element : elements) {
      if (!(element instanceof PsiDirectory || element instanceof PsiFile)) return false;
      if (!element.isValid()) return false;
      if (element instanceof PsiCompiledFile) return false;

      String name = ((PsiFileSystemItem) element).getName();
      if (names.contains(name)) {
        return false;
      }
      names.add(name);
    }

    PsiElement[] filteredElements = PsiTreeUtil.filterAncestors(elements);
    return filteredElements.length == elements.length;
  }

  @Override
  public void doCopy(final PsiElement[] elements, PsiDirectory defaultTargetDirectory) {
    if (defaultTargetDirectory == null) {
      defaultTargetDirectory = getCommonParentDirectory(elements);
    }
    Project project = defaultTargetDirectory != null ? defaultTargetDirectory.getProject() : elements [0].getProject();
    if (defaultTargetDirectory != null) {
      defaultTargetDirectory = resolveDirectory(defaultTargetDirectory);
      if (defaultTargetDirectory == null) return;
    }

    defaultTargetDirectory = tryNotNullizeDirectory(project, defaultTargetDirectory);

    copyAsFiles(elements, defaultTargetDirectory, project);
  }

  @Nullable
  private static PsiDirectory tryNotNullizeDirectory(@NotNull Project project, @Nullable PsiDirectory defaultTargetDirectory) {
    if (defaultTargetDirectory == null) {
      VirtualFile root = ArrayUtil.getFirstElement(ProjectRootManager.getInstance(project).getContentRoots());
      if (root == null) root = project.getBaseDir();
      if (root == null) root = VfsUtil.getUserHomeDir();
      defaultTargetDirectory = root != null ? PsiManager.getInstance(project).findDirectory(root) : null;

      if (defaultTargetDirectory == null) {
        LOG.warn("No directory found for project: " + project.getName() +", root: " + root);
      }
    }
    return defaultTargetDirectory;
  }

  public static void copyAsFiles(PsiElement[] elements, @Nullable PsiDirectory defaultTargetDirectory, Project project) {
    doCopyAsFiles(elements, defaultTargetDirectory, project);
  }

  private static void doCopyAsFiles(PsiElement[] elements, @Nullable PsiDirectory defaultTargetDirectory, Project project) {
    PsiDirectory targetDirectory;
    String newName;
    boolean openInEditor;

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      targetDirectory = defaultTargetDirectory;
      newName = null;
      openInEditor = true;
    }
    else {
      CopyFilesOrDirectoriesDialog dialog = new CopyFilesOrDirectoriesDialog(elements, defaultTargetDirectory, project, false);
      if (dialog.showAndGet()) {
        newName = elements.length == 1 ? dialog.getNewName() : null;
        targetDirectory = dialog.getTargetDirectory();
        openInEditor = dialog.openInEditor();
      }
      else {
        return;
      }
    }

    if (targetDirectory != null) {
      try {
        for (PsiElement element : elements) {
          PsiFileSystemItem psiElement = (PsiFileSystemItem)element;
          if (psiElement.isDirectory()) {
            MoveFilesOrDirectoriesUtil.checkIfMoveIntoSelf(psiElement, targetDirectory);
          }
        }
      }
      catch (IncorrectOperationException e) {
        CommonRefactoringUtil.showErrorHint(project, null, e.getMessage(), CommonBundle.getErrorTitle(), null);
        return;
      }

      SmartPointerManager manager = SmartPointerManager.getInstance(project);
      CommandProcessor.getInstance().executeCommand(project, () -> copyImpl(Arrays.stream(elements).map(el -> manager.createSmartPsiElementPointer(el)).toArray(SmartPsiElementPointer[]::new),
                                                                            newName, targetDirectory, false, openInEditor),
                                                    RefactoringBundle.message("copy.handler.copy.files.directories"), null);

    }
  }

  @Override
  public void doClone(final PsiElement element) {
    doCloneFile(element);
  }

  public static void doCloneFile(PsiElement element) {
    PsiDirectory targetDirectory;
    if (element instanceof PsiDirectory) {
      targetDirectory = ((PsiDirectory)element).getParentDirectory();
    }
    else {
      targetDirectory = PlatformPackageUtil.getDirectory(element);
    }
    targetDirectory = tryNotNullizeDirectory(element.getProject(), targetDirectory);
    if (targetDirectory == null) return;

    PsiElement[] elements = {element};
    CopyFilesOrDirectoriesDialog dialog = new CopyFilesOrDirectoriesDialog(elements, null, element.getProject(), true);
    if (dialog.showAndGet()) {
      String newName = dialog.getNewName();
      SmartPointerManager manager = SmartPointerManager.getInstance(element.getProject());
      copyImpl(Arrays.stream(elements)
                 .map(el -> manager.createSmartPsiElementPointer(el))
                 .toArray(SmartPsiElementPointer[]::new), newName, targetDirectory, true, true);
    }
  }

  @Nullable
  private static PsiDirectory getCommonParentDirectory(PsiElement[] elements){
    PsiDirectory result = null;

    for (PsiElement element : elements) {
      PsiDirectory directory;

      if (element instanceof PsiDirectory) {
        directory = (PsiDirectory)element;
        directory = directory.getParentDirectory();
      }
      else if (element instanceof PsiFile) {
        directory = PlatformPackageUtil.getDirectory(element);
      }
      else {
        throw new IllegalArgumentException("unexpected element " + element);
      }

      if (directory == null) continue;

      if (result == null) {
        result = directory;
      }
      else {
        if (PsiTreeUtil.isAncestor(directory, result, true)) {
          result = directory;
        }
      }
    }

    return result;
  }

  /**
   * @param elements
   * @param newName can be not null only if elements.length == 1
   * @param targetDirectory
   * @param openInEditor
   */
  private static void copyImpl(@NotNull final SmartPsiElementPointer[] elements,
                               @Nullable final String newName,
                               @NotNull final PsiDirectory targetDirectory,
                               final boolean doClone,
                               final boolean openInEditor) {
    if (doClone && elements.length != 1) {
      throw new IllegalArgumentException("invalid number of elements to clone:" + elements.length);
    }

    if (newName != null && elements.length != 1) {
      throw new IllegalArgumentException("no new name should be set; number of elements is: " + elements.length);
    }

    final Project project = targetDirectory.getProject();
    if (!CommonRefactoringUtil.checkReadOnlyStatus(project, Collections.singleton(targetDirectory), false)) {
      return;
    }

    String title = RefactoringBundle.message(doClone ? "copy,handler.clone.files.directories" : "copy.handler.copy.files.directories");
    try {
      PsiFile firstFile = null;
      final int[] choice = elements.length > 1 || elements[0].getElement() instanceof PsiDirectory ? new int[]{-1} : null;
      for (SmartPsiElementPointer element : elements) {
        PsiElement psiElement = element.getElement();
        if (psiElement == null) continue;
        PsiFile f = copyToDirectory((PsiFileSystemItem)psiElement, newName, targetDirectory, choice, title);
        if (firstFile == null) {
          firstFile = f;
        }
      }

      if (firstFile != null && openInEditor) {
        CopyHandler.updateSelectionInActiveProjectView(firstFile, project, doClone);
        if (!(firstFile instanceof PsiBinaryFile)) {
          EditorHelper.openInEditor(firstFile);
          ToolWindowManager.getInstance(project).activateEditorComponent();
        }
      }
    }
    catch (final IncorrectOperationException | IOException ex) {
      Messages.showErrorDialog(project, ex.getMessage(), RefactoringBundle.message("error.title"));
    }
  }

  /**
   * @param elementToCopy PsiFile or PsiDirectory
   * @param newName can be not null only if elements.length == 1
   * @return first copied PsiFile (recursively); null if no PsiFiles copied
   */
  @Nullable
  public static PsiFile copyToDirectory(@NotNull PsiFileSystemItem elementToCopy,
                                        @Nullable String newName,
                                        @NotNull PsiDirectory targetDirectory) throws IncorrectOperationException, IOException {
    return copyToDirectory(elementToCopy, newName, targetDirectory, null, null);
  }

  /**
   * @param elementToCopy PsiFile or PsiDirectory
   * @param newName can be not null only if elements.length == 1
   * @param choice a horrible way to pass/keep user preference
   * @return first copied PsiFile (recursively); null if no PsiFiles copied
   */
  @Nullable
  public static PsiFile copyToDirectory(@NotNull PsiFileSystemItem elementToCopy,
                                        @Nullable String newName,
                                        @NotNull PsiDirectory targetDirectory,
                                        @Nullable int[] choice,
                                        @Nullable String title) throws IncorrectOperationException, IOException {
    if (elementToCopy instanceof PsiFile) {
      PsiFile file = (PsiFile)elementToCopy;
      String name = newName == null ? file.getName() : newName;
      if (checkFileExist(targetDirectory, choice, file, name, "Copy")) return null;
      return new WriteCommandAction<PsiFile>(targetDirectory.getProject(), title) {
        @Override
        protected void run(@NotNull Result<PsiFile> result) throws Throwable {
          result.setResult(targetDirectory.copyFileFrom(name, file));
        }
      }.execute().getResultObject();
    }
    else if (elementToCopy instanceof PsiDirectory) {
      PsiDirectory directory = (PsiDirectory)elementToCopy;
      if (directory.equals(targetDirectory)) {
        return null;
      }
      if (newName == null) newName = directory.getName();
      final PsiDirectory existing = targetDirectory.findSubdirectory(newName);
      final PsiDirectory subdirectory;
      if (existing == null) {
        String finalNewName = newName;
        subdirectory = new WriteCommandAction<PsiDirectory>(targetDirectory.getProject(), title) {
          @Override
          protected void run(@NotNull Result<PsiDirectory> result) throws Throwable {
            result.setResult(targetDirectory.createSubdirectory(finalNewName));
          }
        }.execute().getResultObject();
      }
      else {
        subdirectory = existing;
      }
      EncodingRegistry.doActionAndRestoreEncoding(directory.getVirtualFile(),
                                                  (ThrowableComputable<VirtualFile, IOException>)() -> subdirectory.getVirtualFile());

      PsiFile firstFile = null;
      Project project = directory.getProject();
      SmartPointerManager manager = SmartPointerManager.getInstance(project);
      SmartPsiElementPointer[] children = Arrays.stream(directory.getChildren())
        .map(element -> manager.createSmartPsiElementPointer(element))
        .toArray(SmartPsiElementPointer[]::new);
      for (SmartPsiElementPointer child : children) {
        PsiFileSystemItem item = (PsiFileSystemItem)child.getElement();
        if (item == null) {
          VirtualFile file = child.getVirtualFile();
          if (file != null && !file.isDirectory()) {
            item = PsiManager.getInstance(project).findFile(file);
          }
        }

        if (item == null) {
          VirtualFile file = child.getVirtualFile();
          LOG.info("Invalidated item: " + file.getExtension());
          continue;
        }
        PsiFile f = copyToDirectory(item, item.getName(), subdirectory, choice, title);
        if (firstFile == null) {
          firstFile = f;
        }
      }
      return firstFile;
    }
    else {
      throw new IllegalArgumentException("unexpected elementToCopy: " + elementToCopy);
    }
  }

  public static boolean checkFileExist(@Nullable PsiDirectory targetDirectory, int[] choice, PsiFile file, String name, String title) {
    if (targetDirectory == null) return false;
    final PsiFile existing = targetDirectory.findFile(name);
    if (existing != null && !existing.equals(file)) {
      int selection;
      if (choice == null || choice[0] == -1) {
        String message = String.format("File '%s' already exists in directory '%s'", name, targetDirectory.getVirtualFile().getPath());
        String[] options = choice == null ? new String[]{"Overwrite", "Skip"}
                                          : new String[]{"Overwrite", "Skip", "Overwrite for all", "Skip for all"};
        selection = Messages.showDialog(message, title, options, 0, Messages.getQuestionIcon());
      }
      else {
        selection = choice[0];
      }

      if (choice != null && selection > 1) {
        choice[0] = selection % 2;
        selection = choice[0];
      }

      if (selection == 0 && file != existing) {
        WriteCommandAction.writeCommandAction(targetDirectory.getProject())
          .withName(title)
          .run(() -> existing.delete());
      }
      else {
        return true;
      }
    }

    return false;
  }

  @Nullable
  protected static PsiDirectory resolveDirectory(@NotNull PsiDirectory defaultTargetDirectory) {
    final Project project = defaultTargetDirectory.getProject();
    final Boolean showDirsChooser = defaultTargetDirectory.getCopyableUserData(CopyPasteDelegator.SHOW_CHOOSER_KEY);
    if (showDirsChooser != null && showDirsChooser.booleanValue()) {
      final PsiDirectoryContainer directoryContainer =
        PsiDirectoryFactory.getInstance(project).getDirectoryContainer(defaultTargetDirectory);
      if (directoryContainer == null) {
        return defaultTargetDirectory;
      }
      return MoveFilesOrDirectoriesUtil.resolveToDirectory(project, directoryContainer);
    }
    return defaultTargetDirectory;
  }
}
