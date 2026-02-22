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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import io.github.chatificial.ChatificialBundle
import io.github.chatificial.template.TemplatePlaceholders
import javax.swing.JComponent

class ChatificialSettingsConfigurable : BoundConfigurable(
    ChatificialBundle.message("settings.chatificial.displayName")
) {

    private val settings = ChatificialSettings.getInstance()

    private val graph = PropertyGraph()

    private val maxTotalCharsDefault = 20_000
    private val fileTemplateDefault = ChatificialSettings.DEFAULT_TEMPLATE

    private val maxTotalChars = graph.property(maxTotalCharsDefault)
    private val fileTemplate = graph.property(fileTemplateDefault)

    override fun createPanel() = panel {
        group(ChatificialBundle.message("settings.copyFileContent.group")) {

            row(ChatificialBundle.message("settings.copyFileContent.maxTotalChars")) {
                intTextField()
                    .bindIntText(maxTotalChars)
                    .comment(ChatificialBundle.message("settings.copyFileContent.maxTotalChars.comment"))

                resetSettingButton(
                    property = maxTotalChars,
                    defaultValue = maxTotalCharsDefault
                )
            }

            row(ChatificialBundle.message("settings.copyFileContent.template")) {
                textArea()
                    .bindText(fileTemplate)
                    .rows(10)
                    .align(AlignX.FILL)
                    .comment(
                        ChatificialBundle.message(
                            "settings.copyFileContent.template.comment",
                            TemplatePlaceholders.ALL.joinToString(", ")
                        )
                    )

                resetSettingButton(
                    property = fileTemplate,
                    defaultValue = fileTemplateDefault
                )
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return maxTotalChars.get() != s.maxTotalChars || fileTemplate.get() != s.fileTemplate
    }

    override fun reset() {
        val s = settings.state
        maxTotalChars.set(s.maxTotalChars)
        fileTemplate.set(s.fileTemplate)
    }

    override fun apply() {
        super.apply()

        settings.setState(
            settings.state.copy(
                maxTotalChars = maxTotalChars.get(),
                fileTemplate = fileTemplate.get()
            )
        )
    }
}

fun <T> Row.resetSettingButton(
    property: GraphProperty<T>,
    defaultValue: T
) {
    lateinit var buttonComponent: JComponent

    val action = object : DumbAwareAction(
        ChatificialBundle.message("settings.common.reset"),
        ChatificialBundle.message("settings.common.reset.description"),
        AllIcons.Diff.Revert
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            property.set(defaultValue)
            buttonComponent.isVisible = false
        }
    }

    buttonComponent = actionButton(action).component.apply {
        toolTipText = ChatificialBundle.message("settings.common.reset.tooltip")
    }

    fun updateVisibility() {
        buttonComponent.isVisible = property.get() != defaultValue
    }

    updateVisibility()
    property.afterChange { updateVisibility() }
}
