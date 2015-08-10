/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 17.11.2006
 * Time: 17:08:11
 */
package com.intellij.openapi.vcs.changes.patch;

import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.*;
import com.intellij.openapi.diff.impl.patch.*;
import com.intellij.openapi.diff.impl.patch.apply.ApplyFilePatch;
import com.intellij.openapi.diff.impl.patch.apply.ApplyFilePatchBase;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.VcsApplicationSettings;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.ObjectUtils;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class ApplyPatchAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.patch.ApplyPatchAction");

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getData(CommonDataKeys.PROJECT);
    if (e.getPlace().equals(ActionPlaces.PROJECT_VIEW_POPUP)) {
      VirtualFile vFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
      e.getPresentation().setEnabledAndVisible(project != null && vFile != null && vFile.getFileType() == StdFileTypes.PATCH);
    }
    else {
      e.getPresentation().setVisible(true);
      e.getPresentation().setEnabled(project != null);
    }
  }

  public void actionPerformed(AnActionEvent e) {
    final Project project = e.getRequiredData(CommonDataKeys.PROJECT);
    if (ChangeListManager.getInstance(project).isFreezedWithNotification("Can not apply patch now")) return;
    FileDocumentManager.getInstance().saveAllDocuments();

    if (e.getPlace().equals(ActionPlaces.PROJECT_VIEW_POPUP)) {
      VirtualFile vFile = e.getRequiredData(CommonDataKeys.VIRTUAL_FILE);
      showApplyPatch(project, vFile);
    }
    else {
      final FileChooserDescriptor descriptor = ApplyPatchDifferentiatedDialog.createSelectPatchDescriptor();
      final VcsApplicationSettings settings = VcsApplicationSettings.getInstance();
      final VirtualFile toSelect = settings.PATCH_STORAGE_LOCATION == null ? null :
                                   LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new File(settings.PATCH_STORAGE_LOCATION));

      FileChooser.chooseFile(descriptor, project, toSelect, new Consumer<VirtualFile>() {
        @Override
        public void consume(VirtualFile file) {
          final VirtualFile parent = file.getParent();
          if (parent != null) {
            settings.PATCH_STORAGE_LOCATION = FileUtil.toSystemDependentName(parent.getPath());
          }
          showApplyPatch(project, file);
        }
      });
    }
  }

  private static void showApplyPatch(@NotNull final Project project, @NotNull final VirtualFile file) {
    final ApplyPatchDifferentiatedDialog dialog = new ApplyPatchDifferentiatedDialog(
      project, new ApplyPatchDefaultExecutor(project),
      Collections.<ApplyPatchExecutor>singletonList(new ImportToShelfExecutor(project)), ApplyPatchMode.APPLY, file);
    dialog.show();
  }

  public static void applySkipDirs(final List<FilePatch> patches, final int skipDirs) {
    if (skipDirs < 1) {
      return;
    }
    for (FilePatch patch : patches) {
      patch.setBeforeName(skipN(patch.getBeforeName(), skipDirs));
      patch.setAfterName(skipN(patch.getAfterName(), skipDirs));
    }
  }

  private static String skipN(final String path, final int num) {
    final String[] pieces = path.split("/");
    final StringBuilder sb = new StringBuilder();
    for (int i = num; i < pieces.length; i++) {
      final String piece = pieces[i];
      sb.append('/').append(piece);
    }
    return sb.toString();
  }

  @NotNull
  public static ApplyPatchStatus applyOnly(@Nullable final Project project,
                                           @NotNull final ApplyFilePatchBase patch,
                                           @Nullable final ApplyPatchContext context,
                                           @NotNull final VirtualFile file,
                                           @Nullable final CommitContext commitContext,
                                           boolean reverse,
                                           @Nullable String leftPanelTitle,
                                           @Nullable String rightPanelTitle) {
    final ApplyFilePatch.Result result = tryApplyPatch(project, patch, context, file, commitContext);

    final ApplyPatchStatus status = result.getStatus();
    if (ApplyPatchStatus.ALREADY_APPLIED.equals(status) || ApplyPatchStatus.SUCCESS.equals(status)) {
      return status;
    }

    final ApplyPatchForBaseRevisionTexts mergeData = result.getMergeData();
    if (mergeData == null) return status;

    if (mergeData.getBase() != null) {
      return showMergeDialog(project, file, mergeData.getBase(), mergeData.getPatched(), reverse, leftPanelTitle, rightPanelTitle);
    }
    else {
      return showBadDiffDialog(project, file, mergeData, false);
    }
  }

  @NotNull
  private static ApplyFilePatch.Result tryApplyPatch(@Nullable final Project project,
                                                     @NotNull final ApplyFilePatchBase patch,
                                                     @Nullable final ApplyPatchContext context,
                                                     @NotNull final VirtualFile file,
                                                     @Nullable final CommitContext commitContext) {
    final FilePatch patchBase = patch.getPatch();
    return ApplicationManager.getApplication().runWriteAction(
      new Computable<ApplyFilePatch.Result>() {
        @Override
        public ApplyFilePatch.Result compute() {
          try {
            return patch.apply(file, context, project, VcsUtil.getFilePath(file), new Getter<CharSequence>() {
              @Override
              public CharSequence get() {
                final BaseRevisionTextPatchEP baseRevisionTextPatchEP =
                  Extensions.findExtension(PatchEP.EP_NAME, project, BaseRevisionTextPatchEP.class);
                final String path = ObjectUtils.chooseNotNull(patchBase.getBeforeName(), patchBase.getAfterName());
                return baseRevisionTextPatchEP.provideContent(path, commitContext);
              }
            }, commitContext);
          }
          catch (IOException e) {
            LOG.error(e);
            return ApplyFilePatch.Result.createThrow(e);
          }
        }
      });
  }

  @NotNull
  private static ApplyPatchStatus showMergeDialog(@Nullable Project project,
                                                  @NotNull VirtualFile file,
                                                  @Nullable CharSequence content,
                                                  @NotNull final String patchedContent,
                                                  boolean reverse,
                                                  @Nullable String leftPanelTitle,
                                                  @Nullable String rightPanelTitle) {
    Document document = FileDocumentManager.getInstance().getDocument(file);
    if (document != null) {
      FileDocumentManager.getInstance().saveDocument(document);
    }
    CharSequence fileContent = LoadTextUtil.loadText(file);
    if (content == null) {
      return ApplyPatchStatus.FAILURE;
    }

    final String leftText = fileContent.toString();
    MergeRequest request = DiffRequestFactory.getInstance().createMergeRequest(reverse ? patchedContent : leftText,
                                                                               reverse ? leftText : patchedContent, content.toString(),
                                                                               file, project, ActionButtonPresentation.APPLY,
                                                                               ActionButtonPresentation.CANCEL_WITH_PROMPT);

    request.setVersionTitles(new String[]{
      leftPanelTitle == null ? VcsBundle.message("patch.apply.conflict.local.version") : leftPanelTitle,
      rightPanelTitle == null ? VcsBundle.message("patch.apply.conflict.merged.version") : rightPanelTitle,
      VcsBundle.message("patch.apply.conflict.patched.version")
    });
    request.setWindowTitle(VcsBundle.message("patch.apply.conflict.title", file.getPresentableUrl()));

    DiffManager.getInstance().getDiffTool().show(request);
    if (request.getResult() == DialogWrapper.OK_EXIT_CODE) {
      return ApplyPatchStatus.SUCCESS;
    }
    else {
      request.restoreOriginalContent();
      return ApplyPatchStatus.FAILURE;
    }
  }

  @NotNull
  private static ApplyPatchStatus showBadDiffDialog(@Nullable Project project,
                                                    @NotNull VirtualFile file,
                                                    @NotNull ApplyPatchForBaseRevisionTexts texts,
                                                    boolean readonly) {
    if (texts.getLocal() == null) return ApplyPatchStatus.FAILURE;

    final SimpleDiffRequest simpleRequest = createBadDiffRequest(project, file, texts, readonly);
    DiffManager.getInstance().getDiffTool().show(simpleRequest);

    return ApplyPatchStatus.SUCCESS;
  }

  @NotNull
  public static SimpleDiffRequest createBadDiffRequest(@Nullable final Project project,
                                                       @NotNull final VirtualFile file,
                                                       @NotNull ApplyPatchForBaseRevisionTexts texts,
                                                       boolean readonly) {
    final String fullPath = file.getParent() == null ? file.getPath() : file.getParent().getPath();
    final String title = "Result Of Patch Apply To " + file.getName() + " (" + fullPath + ")";

    final SimpleDiffRequest simpleRequest = new SimpleDiffRequest(project, title);
    final DocumentImpl patched = new DocumentImpl(texts.getPatched());
    patched.setReadOnly(false);

    final DocumentContent mergedContent = new DocumentContent(project, patched, file.getFileType());
    mergedContent.getDocument().setReadOnly(readonly);
    final SimpleContent originalContent = new SimpleContent(texts.getLocal().toString(), file.getFileType());

    simpleRequest.setContents(originalContent, mergedContent);
    simpleRequest.setContentTitles(VcsBundle.message("diff.title.local"), "Patched (with problems)");
    simpleRequest.addHint(DiffTool.HINT_SHOW_MODAL_DIALOG);
    simpleRequest.addHint(DiffTool.HINT_DIFF_IS_APPROXIMATE);

    if (!readonly) {
      simpleRequest.setOnOkRunnable(new Runnable() {
        @Override
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
              final String resultText = mergedContent.getDocument().getText();
              final Document document = FileDocumentManager.getInstance().getDocument(file);
              if (document == null) {
                try {
                  VfsUtil.saveText(file, resultText);
                }
                catch (IOException e) {
                  // todo bad: we had already returned success by now
                  showIOException(project, file.getName(), e);
                }
              }
              else {
                document.setText(resultText);
                FileDocumentManager.getInstance().saveDocument(document);
              }
            }
          });
        }
      });
    }
    return simpleRequest;
  }

  private static void showIOException(@Nullable Project project, @NotNull String name, @NotNull IOException e) {
    Messages.showErrorDialog(project,
                             VcsBundle.message("patch.apply.error", name, e.getMessage()),
                             VcsBundle.message("patch.apply.dialog.title"));
  }
}
