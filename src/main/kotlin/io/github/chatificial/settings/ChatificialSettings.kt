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
import com.intellij.openapi.components.*

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

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.normalized()
    }

    companion object {
        const val DEFAULT_TEMPLATE: String =
            "`{path}`\n" +
                    "```\n" +
                    "{content}\n" +
                    "```"

        fun getInstance(): ChatificialSettings =
            ApplicationManager.getApplication().service()
    }
}

private fun ChatificialSettings.State.normalized(): ChatificialSettings.State =
    copy(
        maxTotalChars = maxTotalChars.coerceAtLeast(1),
        fileTemplate = fileTemplate.ifBlank { ChatificialSettings.DEFAULT_TEMPLATE }
    )
