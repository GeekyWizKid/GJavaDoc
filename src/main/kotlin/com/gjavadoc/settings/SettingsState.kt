package com.gjavadoc.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(name = "GJavaDocSettings", storages = [Storage("gjavadoc.xml")])
@Service(Service.Level.PROJECT)
class SettingsState : PersistentStateComponent<SettingsState.State> {

    data class RetryConfig(
        var enabled: Boolean = true,
        var maxAttempts: Int = 3,
        var backoffMs: Long = 1500,
    )

    data class PersistConfig(
        var historyLimit: Int = 200,
    )

    data class ContextConfig(
        var typeDepth: Int = 2,
        var calledDepth: Int = 1,
        var collectCalled: Boolean = true,
        var maxChars: Int = 20000,
        var typeSuffixes: MutableList<String> = mutableListOf("DTO", "VO"),
        var packageKeywords: MutableList<String> = mutableListOf(".dto", ".vo", ".entity"),
        var annotationWhitelist: MutableList<String> = mutableListOf("Entity", "jakarta.persistence.Entity", "javax.persistence.Entity"),
    )

    data class CrudFilter(
        var includeCreate: Boolean = true,
        var includeRead: Boolean = true,
        var includeUpdate: Boolean = true,
        var includeDelete: Boolean = true,
        var includeOther: Boolean = true,
    )

    /**
     * Customizable CRUD name prefixes used to classify method names.
     * Values are case-insensitive; matching uses startsWith on the lower-cased method name.
     */
    data class CrudPatterns(
        var create: MutableList<String> = mutableListOf("create", "add", "insert", "save", "new"),
        var read: MutableList<String> = mutableListOf("get", "query", "list", "find", "select", "count", "load"),
        var update: MutableList<String> = mutableListOf("update", "set", "modify", "patch", "enable", "disable"),
        var delete: MutableList<String> = mutableListOf("delete", "remove", "del", "clear"),
    )

    data class UIConfig(
        var lastStatusFilter: String = "ALL",
        var lastSearchText: String = "",
        var pageSize: Int = 20,
        var sortBy: String = "CREATED_AT", // CREATED_AT, ENTRY, STATUS, PROGRESS
        var sortAsc: Boolean = false,
        var lastModule: String = "ALL",
    )

    data class State(
        var annotation: String = "@RpcService",
        var llmEndpoint: String = "http://127.0.0.1:8000/v1/chat/completions",
        var model: String = "Qwen-32B",
        var useHttpClient: Boolean = false,
        var analysisBackend: String = "STUB", // STUB or WALA
        var llmProvider: String = "OPENAI", // OPENAI, OLLAMA, DEEPSEEK
        var authToken: String? = null,
        var context: ContextConfig = ContextConfig(),
        var crud: CrudFilter = CrudFilter(),
        var crudPatterns: CrudPatterns = CrudPatterns(),
        var perClassDocument: Boolean = false,
        var maxConcurrentRequests: Int = 2,
        var requestsPerSecond: Double = 1.5,
        var queueSize: Int = 32,
        var requestTimeoutSec: Int = 60,
        var retry: RetryConfig = RetryConfig(),
        var persist: PersistConfig = PersistConfig(),
        var customPromptEnabled: Boolean = false,
        var customPrompt: String = "",
        var ui: UIConfig = UIConfig(),
        var groupDocsByModule: Boolean = false,
        // OpenAI-compatible defaults (some servers require explicit values)
        var openaiMaxTokens: Int = 4096,
        var openaiTemperature: Double = 0.7,
        var openaiTopP: Double = 1.0,
        // DeepSeek-specific settings for preset configurations
        var deepSeekEndpoint: String = "https://api.deepseek.com/v1/chat/completions",
        var deepSeekModel: String = "deepseek-chat",
    )

    private var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): SettingsState = project.getService(SettingsState::class.java)
    }
}
