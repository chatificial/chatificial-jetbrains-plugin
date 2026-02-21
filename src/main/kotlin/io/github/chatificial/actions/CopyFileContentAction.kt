/*
 * Copyright 2026-present The Chatificial Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.chatificial.actions

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.chatificial.ChatificialBundle
import io.github.chatificial.settings.ChatificialSettings
import java.awt.datatransfer.StringSelection

class CopyFileContentAction : AnAction() {

    companion object {
        private const val NOTIFICATION_GROUP_ID = "chatificial.notification"

        private const val TEMPLATE_PATH = "{path}"
        private const val TEMPLATE_CONTENT = "{content}"

        private const val SCRATCH_FILE_NAME = "chatificial.content.md"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && getSelectedRoots(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val roots = getSelectedRoots(e)
        if (roots.isEmpty()) return

        val settingsState = ChatificialSettings.getInstance().state
        val maxTotalChars = settingsState.maxTotalChars.coerceAtLeast(1)
        val template = normalizeTemplate(settingsState.fileTemplate)

        ReadAction.nonBlocking<String?> {
            if (project.isDisposed) return@nonBlocking null

            val files = collectFilesFromSelection(project, roots)
            if (files.isEmpty()) return@nonBlocking null

            buildString {
                files.forEachIndexed { idx, file ->
                    append(formatOneFile(project, file, template))
                    if (idx != files.lastIndex) append("\n\n")
                }
            }
        }
            .finishOnUiThread(ModalityState.defaultModalityState()) { outputOrNull ->
                if (project.isDisposed) return@finishOnUiThread

                if (outputOrNull == null) {
                    NotificationType.INFORMATION.notify(
                        project,
                        ChatificialBundle.message("chatificial.copyFileContent.noTextFilesFound")
                    )
                    return@finishOnUiThread
                }

                if (outputOrNull.length > maxTotalChars) {
                    val scratchVFile = writeToScratchFileInWriteAction(outputOrNull)
                    FileEditorManager.getInstance(project).openFile(scratchVFile, true, true)

                    NotificationType.INFORMATION.notify(
                        project,
                        ChatificialBundle.message(
                            "chatificial.copyFileContent.tooLargeSavedAndOpened",
                            outputOrNull.length,
                            maxTotalChars
                        )
                    )
                } else {
                    CopyPasteManager.getInstance().setContents(StringSelection(outputOrNull))
                    NotificationType.INFORMATION.notify(
                        project,
                        ChatificialBundle.message("chatificial.copyFileContent.copiedToClipboard", outputOrNull.length)
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun normalizeTemplate(templateFromSettings: String): String {
        val tpl = templateFromSettings.ifBlank { ChatificialSettings.DEFAULT_TEMPLATE }
        return if (tpl.contains(TEMPLATE_PATH) && tpl.contains(TEMPLATE_CONTENT)) tpl
        else ChatificialSettings.DEFAULT_TEMPLATE
    }

    private fun NotificationType.notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, this)
            .notify(project)
    }

    private fun formatOneFile(project: Project, file: VirtualFile, template: String): String {
        val relPath = getRelativePathFromProjectRoot(project, file)
        val contentOrPlaceholder =
            readFileAsUtf8(file) ?: ChatificialBundle.message("chatificial.copyFileContent.couldNotReadFileContent")

        val normalizedContent =
            if (contentOrPlaceholder.endsWith('\n')) contentOrPlaceholder else "$contentOrPlaceholder\n"

        return template
            .replace(TEMPLATE_PATH, relPath)
            .replace(TEMPLATE_CONTENT, normalizedContent)
            .trimEnd()
    }

    private fun collectFilesFromSelection(project: Project, selected: List<VirtualFile>): List<VirtualFile> {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val out = LinkedHashSet<VirtualFile>()

        fun addIfOk(vf: VirtualFile) {
            if (!vf.isValid) return
            if (vf.isDirectory) return
            if (fileIndex.isExcluded(vf)) return
            if (isBinary(vf)) return
            out.add(vf)
        }

        for (vf in selected) {
            if (!vf.isValid) continue
            if (fileIndex.isExcluded(vf)) continue

            if (vf.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(
                    vf,
                    { child -> child.isValid && !fileIndex.isExcluded(child) },
                    { child ->
                        addIfOk(child)
                        true
                    }
                )
            } else {
                addIfOk(vf)
            }
        }

        return out.asSequence()
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    private fun isBinary(file: VirtualFile): Boolean =
        FileTypeManager.getInstance().getFileTypeByFile(file).isBinary

    private fun readFileAsUtf8(file: VirtualFile): String? =
        try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }

    private fun getRelativePathFromProjectRoot(project: Project, file: VirtualFile): String {
        val basePath = project.basePath
        val baseVf = basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val rel = if (baseVf != null) VfsUtilCore.getRelativePath(file, baseVf) else null
        return rel ?: file.path
    }

    private fun writeToScratchFileInWriteAction(text: String): VirtualFile {
        val app = ApplicationManager.getApplication()
        return app.runWriteAction<VirtualFile> {
            val scratchService = ScratchFileService.getInstance()
            val rootType = ScratchRootType.getInstance()

            val vf = scratchService.findFile(
                rootType,
                SCRATCH_FILE_NAME,
                ScratchFileService.Option.create_if_missing
            ) ?: error(
                ChatificialBundle.message(
                    "chatificial.copyFileContent.failedToCreateScratch",
                    SCRATCH_FILE_NAME
                )
            )

            VfsUtilCore.saveText(vf, text)
            vf
        }
    }

    private fun getSelectedRoots(e: AnActionEvent): List<VirtualFile> {
        val arr = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(e.dataContext)
        if (!arr.isNullOrEmpty()) return arr.toList()

        val single = CommonDataKeys.VIRTUAL_FILE.getData(e.dataContext)
        return if (single != null) listOf(single) else emptyList()
    }
}
