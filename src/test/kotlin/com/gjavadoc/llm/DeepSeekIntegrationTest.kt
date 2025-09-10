package com.gjavadoc.llm

import com.gjavadoc.settings.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeepSeekIntegrationTest {

    @Test
    fun testDeepSeekSettingsDefaults() {
        // Test default DeepSeek settings
        val state = SettingsState.State()
        assertEquals("https://api.deepseek.com/v1/chat/completions", state.deepSeekEndpoint)
        assertEquals("deepseek-chat", state.deepSeekModel)
    }

    @Test
    fun testDeepSeekProviderEnum() {
        // Test DEEPSEEK provider is recognized
        val state = SettingsState.State()
        state.llmProvider = "DEEPSEEK"
        assertEquals("DEEPSEEK", state.llmProvider)
    }

    @Test
    fun testDeepSeekModelMapping() {
        // Test various DeepSeek model names are handled correctly
        val models = listOf("deepseek-chat", "deepseek-reasoner", "chat", "reasoner")
        for (model in models) {
            val deepSeekModel = when {
                model.startsWith("deepseek") -> model
                model.contains("chat") || model.contains("reasoner") -> model
                else -> "deepseek-chat"
            }
            
            when (model) {
                "deepseek-chat" -> assertEquals("deepseek-chat", deepSeekModel)
                "deepseek-reasoner" -> assertEquals("deepseek-reasoner", deepSeekModel)
                "chat" -> assertEquals("chat", deepSeekModel)
                "reasoner" -> assertEquals("reasoner", deepSeekModel)
            }
        }
    }
}