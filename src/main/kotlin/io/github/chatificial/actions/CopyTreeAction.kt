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

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.chatificial.ChatificialBundle
import java.awt.datatransfer.StringSelection

class CopyFileTreeAction : CopyTreeAction(includeFiles = true)

class CopyDirectoryTreeAction : CopyTreeAction(includeFiles = false)

abstract class CopyTreeAction(private val includeFiles: Boolean) : AnAction() {

    companion object {
        private const val NOTIFICATION_GROUP_ID = "chatificial.notification"
        private const val MIDDLE_BRANCH = "\u251c\u2500\u2500 "
        private const val LAST_BRANCH = "\u2514\u2500\u2500 "
        private const val VERTICAL_PREFIX = "\u2502   "
        private const val EMPTY_PREFIX = "    "
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val roots = getSelectedRoots(e)
        val hasSelection = project != null && roots.isNotEmpty()

        e.presentation.isVisible = hasSelection
        e.presentation.isEnabled = hasSelection && (includeFiles || roots.any { it.isValid && it.isDirectory })
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val roots = getSelectedRoots(e)
        if (roots.isEmpty()) return

        ReadAction.nonBlocking<String?> {
            if (project.isDisposed) return@nonBlocking null
            buildTree(project, roots)
        }
            .finishOnUiThread(ModalityState.defaultModalityState()) { outputOrNull ->
                if (project.isDisposed) return@finishOnUiThread

                if (outputOrNull == null) {
                    NotificationType.INFORMATION.notify(
                        project,
                        ChatificialBundle.message(noContentMessageKey())
                    )
                    return@finishOnUiThread
                }

                CopyPasteManager.getInstance().setContents(StringSelection(outputOrNull))
                NotificationType.INFORMATION.notify(
                    project,
                    ChatificialBundle.message(copiedMessageKey(), outputOrNull.length)
                )
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun buildTree(project: Project, roots: List<VirtualFile>): String? {
        val selected = roots
            .filter { it.isValid }
            .filter { includeFiles || it.isDirectory }

        if (selected.isEmpty()) return null

        val anchor = findAnchor(project, selected) ?: return null
        val rootNode = TreeNode(displayName(anchor))
        val visitedDirectories = LinkedHashSet<String>()

        selected.withoutDescendantDuplicates().forEach { selectedRoot ->
            if (selectedRoot == anchor) {
                addChildren(rootNode, selectedRoot, visitedDirectories)
            } else {
                addSelectedRoot(rootNode, anchor, selectedRoot, visitedDirectories)
            }
        }

        return renderTree(rootNode)
    }

    private fun addSelectedRoot(
        rootNode: TreeNode,
        anchor: VirtualFile,
        selectedRoot: VirtualFile,
        visitedDirectories: MutableSet<String>
    ) {
        val relativePath = VfsUtilCore.getRelativePath(selectedRoot, anchor, '/')
            ?: selectedRoot.name
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return

        var node = rootNode
        segments.forEachIndexed { index, segment ->
            node = node.children.getOrPut(segment) { TreeNode(segment) }

            if (index == segments.lastIndex && selectedRoot.isDirectory) {
                addChildren(node, selectedRoot, visitedDirectories)
            }
        }
    }

    private fun addChildren(
        targetNode: TreeNode,
        sourceDirectory: VirtualFile,
        visitedDirectories: MutableSet<String>
    ) {
        val directoryPath = sourceDirectory.canonicalPath ?: sourceDirectory.path
        if (!visitedDirectories.add(directoryPath)) return

        sourceDirectory.children
            .asSequence()
            .filter { it.isValid }
            .filter { includeFiles || it.isDirectory }
            .sortedWith(treeFileComparator())
            .forEach { child ->
                val childNode = targetNode.children.getOrPut(child.name) { TreeNode(child.name) }
                if (child.isDirectory) {
                    addChildren(childNode, child, visitedDirectories)
                }
            }
    }

    private fun findAnchor(project: Project, selected: List<VirtualFile>): VirtualFile? {
        val projectRoot = project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        if (projectRoot != null && selected.all {
                it == projectRoot || VfsUtilCore.isAncestor(
                    projectRoot,
                    it,
                    false
                )
            }) {
            return projectRoot
        }

        val parentTargets = selected.map { it.parent ?: it }
        return parentTargets.commonAncestor()
    }

    private fun renderTree(rootNode: TreeNode): String =
        buildString {
            append(rootNode.name)
            appendTreeChildren(
                nodes = rootNode.children.values.sortedWith(treeNodeComparator()),
                prefix = ""
            )
        }

    private fun StringBuilder.appendTreeChildren(nodes: List<TreeNode>, prefix: String) {
        nodes.forEachIndexed { index, node ->
            val isLast = index == nodes.lastIndex
            append('\n')
            append(prefix)
            append(if (isLast) LAST_BRANCH else MIDDLE_BRANCH)
            append(node.name)

            appendTreeChildren(
                nodes = node.children.values.sortedWith(treeNodeComparator()),
                prefix = prefix + if (isLast) EMPTY_PREFIX else VERTICAL_PREFIX
            )
        }
    }

    private fun treeFileComparator(): Comparator<VirtualFile> =
        compareBy<VirtualFile> { !it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }

    private fun treeNodeComparator(): Comparator<TreeNode> =
        compareBy<TreeNode, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }

    private fun displayName(file: VirtualFile): String =
        file.name.ifBlank { file.path }

    private fun copiedMessageKey(): String =
        if (includeFiles) {
            "chatificial.copyFileTree.copiedToClipboard"
        } else {
            "chatificial.copyDirectoryTree.copiedToClipboard"
        }

    private fun noContentMessageKey(): String =
        if (includeFiles) {
            "chatificial.copyFileTree.noSelection"
        } else {
            "chatificial.copyDirectoryTree.noDirectoriesSelected"
        }

    private fun NotificationType.notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, this)
            .notify(project)
    }

    private fun getSelectedRoots(e: AnActionEvent): List<VirtualFile> {
        val arr = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(e.dataContext)
        if (!arr.isNullOrEmpty()) return arr.toList()

        val single = CommonDataKeys.VIRTUAL_FILE.getData(e.dataContext)
        return if (single != null) listOf(single) else emptyList()
    }
}

private class TreeNode(
    val name: String,
    val children: MutableMap<String, TreeNode> = linkedMapOf()
)

private fun List<VirtualFile>.withoutDescendantDuplicates(): List<VirtualFile> {
    val result = mutableListOf<VirtualFile>()

    sortedWith(compareBy<VirtualFile> { it.path.length }.thenBy { it.path }).forEach { candidate ->
        if (result.none { existing -> VfsUtilCore.isAncestor(existing, candidate, true) }) {
            result.add(candidate)
        }
    }

    return result
}

private fun List<VirtualFile>.commonAncestor(): VirtualFile? {
    if (isEmpty()) return null

    val pathsFromRoot = map { it.pathFromRoot() }
    val shortestPathLength = pathsFromRoot.minOf { it.size }
    var common: VirtualFile? = null

    for (index in 0 until shortestPathLength) {
        val candidate = pathsFromRoot.first()[index]
        if (pathsFromRoot.all { it[index] == candidate }) {
            common = candidate
        } else {
            break
        }
    }

    return common
}

private fun VirtualFile.pathFromRoot(): List<VirtualFile> {
    val path = mutableListOf<VirtualFile>()
    var current: VirtualFile? = this

    while (current != null) {
        path.add(current)
        current = current.parent
    }

    return path.asReversed()
}
