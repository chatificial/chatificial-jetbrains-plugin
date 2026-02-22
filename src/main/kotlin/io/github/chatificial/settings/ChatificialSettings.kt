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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.chatificial.template.TemplatePlaceholders

@Service(Service.Level.APP)
@State(
    name = "ChatificialSettings",
    storages = [Storage("chatificial.xml")]
)
class ChatificialSettings : PersistentStateComponent<ChatificialSettings.State> {

    data class State(
        var maxTotalChars: Int = 20_000,
        var fileTemplate: String = DEFAULT_TEMPLATE
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.normalized()
    }

    fun setState(newState: State) {
        myState = newState.normalized()
    }

    companion object {
        const val DEFAULT_TEMPLATE: String =
            "`" + TemplatePlaceholders.PATH + "`\n" +
                    "```\n" +
                    "" + TemplatePlaceholders.CONTENT + "\n" +
                    "```"

        @JvmStatic
        fun getInstance(): ChatificialSettings =
            ApplicationManager.getApplication().getService(ChatificialSettings::class.java)
                ?: error("ChatificialSettings service is not registered")
    }
}

private fun ChatificialSettings.State.normalized(): ChatificialSettings.State =
    copy(
        maxTotalChars = maxTotalChars.coerceAtLeast(1),
        fileTemplate = fileTemplate
            .ifBlank { ChatificialSettings.DEFAULT_TEMPLATE }
            .validatedTemplate()
    )

private fun String.validatedTemplate(): String {
    val tpl = ifBlank { ChatificialSettings.DEFAULT_TEMPLATE }
    return if (tpl.contains(TemplatePlaceholders.PATH) && tpl.contains(TemplatePlaceholders.CONTENT)) tpl
    else ChatificialSettings.DEFAULT_TEMPLATE
}
