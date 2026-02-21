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

package io.github.chatificial.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.*
import io.github.chatificial.ChatificialBundle
import javax.swing.JComponent

class ChatificialSettingsConfigurable : Configurable {

    private val settings = ChatificialSettings.getInstance()
    private var snapshot = settings.state.copy()
    private var component: JComponent? = null

    override fun getDisplayName(): String =
        ChatificialBundle.message("settings.chatificial.displayName")

    override fun createComponent(): JComponent {
        component = panel {
            group(ChatificialBundle.message("settings.copyFileContent.group")) {
                row(ChatificialBundle.message("settings.copyFileContent.maxTotalChars")) {
                    intTextField()
                        .bindIntText(
                            getter = { snapshot.maxTotalChars },
                            setter = { snapshot.maxTotalChars = it }
                        )
                        .comment(ChatificialBundle.message("settings.copyFileContent.maxTotalChars.comment"))
                }

                row(ChatificialBundle.message("settings.copyFileContent.template")) {
                    textArea()
                        .bindText(
                            getter = { snapshot.fileTemplate },
                            setter = { snapshot.fileTemplate = it }
                        )
                        .rows(10)
                        .align(AlignX.FILL)
                        .comment(ChatificialBundle.message("settings.copyFileContent.template.comment"))
                }.resizableRow()
            }
        }
        return component!!
    }

    override fun isModified(): Boolean = snapshot != settings.state

    override fun apply() {
        settings.loadState(
            settings.state.copy(
                maxTotalChars = snapshot.maxTotalChars.coerceAtLeast(1),
                fileTemplate = snapshot.fileTemplate.validatedTemplate()
            )
        )
        snapshot = settings.state.copy()
    }

    override fun reset() {
        snapshot = settings.state.copy()
    }

    override fun disposeUIResources() {
        component = null
    }
}

private const val TEMPLATE_PATH = "{path}"
private const val TEMPLATE_CONTENT = "{content}"

private fun String.validatedTemplate(): String {
    val tpl = ifBlank { ChatificialSettings.DEFAULT_TEMPLATE }
    return if (tpl.contains(TEMPLATE_PATH) && tpl.contains(TEMPLATE_CONTENT)) tpl
    else ChatificialSettings.DEFAULT_TEMPLATE
}
