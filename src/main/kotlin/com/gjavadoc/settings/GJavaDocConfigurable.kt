package com.gjavadoc.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JButton
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GJavaDocConfigurable() : Configurable {
    private val project: Project by lazy {
        ProjectManager.getInstance().openProjects.firstOrNull()
            ?: ProjectManager.getInstance().defaultProject
    }
    private val annotationField = JBTextField()
    private val endpointField = JBTextField()
    private val modelField = JBTextField()
    private val maxConcSpinner = JSpinner(SpinnerNumberModel(2, 1, 64, 1))
    private val rpsSpinner = JSpinner(SpinnerNumberModel(1.5, 0.1, 100.0, 0.1))
    private val queueSizeSpinner = JSpinner(SpinnerNumberModel(32, 1, 10000, 1))
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(60, 1, 600, 1))
    private val retryEnabled = JBCheckBox("Enable retry")
    private val retryAttempts = JSpinner(SpinnerNumberModel(3, 1, 10, 1))
    private val retryBackoff = JSpinner(SpinnerNumberModel(1500, 100, 60000, 100))
    private val historyLimit = JSpinner(SpinnerNumberModel(200, 10, 10000, 10))

    private val httpEnabled = JBCheckBox("Use HTTP LLM client / 使用 HTTP LLM 客户端")
    private val backendCombo = javax.swing.JComboBox(arrayOf("STUB", "WALA"))
    private val providerCombo = javax.swing.JComboBox(arrayOf("OPENAI", "OLLAMA", "DEEPSEEK"))
    private val tokenField = JBTextField()
    private val testButton = JButton("Test LLM / 测试 LLM")
    private val previewReqButton = JButton("Preview Request / 预览请求")
    private val copyCurlButton = JButton("Copy curl / 复制 curl")
    private val openaiMaxTokens = JSpinner(SpinnerNumberModel(1024, 1, 65536, 32))
    private val openaiTemperature = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.1))
    private val openaiTopP = JSpinner(SpinnerNumberModel(1.0, 0.0, 1.0, 0.05))

    // Context & Type collection options
    private val typeDepthSpinner = JSpinner(SpinnerNumberModel(2, 0, 6, 1))
    private val calledDepthSpinner = JSpinner(SpinnerNumberModel(1, 0, 6, 1))
    private val collectCalledCheckbox = JBCheckBox("Collect called methods / 收集被调方法", true)
    private val maxCharsSpinner = JSpinner(SpinnerNumberModel(20000, 2000, 200000, 1000))
    private val typeSuffixesField = JBTextField("DTO,VO")
    private val pkgKeywordsField = JBTextField(".dto,.vo,.entity")
    private val annoWhitelistField = JBTextField("Entity,jakarta.persistence.Entity,javax.persistence.Entity")

    // CRUD filter
    private val includeCreate = JBCheckBox("Include CREATE / 包含新增", true)
    private val includeRead = JBCheckBox("Include READ / 包含查询", true)
    private val includeUpdate = JBCheckBox("Include UPDATE / 包含更新", true)
    private val includeDelete = JBCheckBox("Include DELETE / 包含删除", true)
    private val includeOther = JBCheckBox("Include OTHER / 包含其他", true)
    private val patCreate = JBTextField("create,add,insert,save,new")
    private val patRead = JBTextField("get,query,list,find,select,count,load")
    private val patUpdate = JBTextField("update,set,modify,patch,enable,disable")
    private val patDelete = JBTextField("delete,remove,del,clear")
    private val perClassDoc = JBCheckBox("Per-class document / 类级文档（一个类→一个文档）", false)
    private val groupDocsByModule = JBCheckBox("Group docs by module / 文档按模块分目录", false)

    // Prompt customization
    private val customPromptEnabled = JBCheckBox("Use custom prompt / 使用自定义 Prompt", false)
    private val promptArea = JBTextArea(12, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val loadDefaultPromptBtn = JButton("Load Default / 载入默认模板")

    private val panel: JPanel = JPanel(java.awt.BorderLayout()).apply {
        val tabs = JBTabbedPane()

        // General tab (Annotation, Analysis, LLM basics)
        fun buildGeneral(): JPanel {
            val fb = FormBuilder.createFormBuilder()
            fb.addLabeledComponent(JBLabel("Annotation(s) / 注解（多个用逗号分隔）"), annotationField, 1, false)
            fb.addLabeledComponent(JBLabel("Analysis Backend / 分析后端"), backendCombo, 1, false)
            fb.addComponent(perClassDoc)
            fb.addComponent(groupDocsByModule)
            fb.addSeparator()
            fb.addComponent(httpEnabled)
            fb.addLabeledComponent(JBLabel("Provider / 提供方"), providerCombo, 1, false)
            fb.addLabeledComponent(JBLabel("LLM Endpoint / 接口地址"), endpointField, 1, false)
            fb.addLabeledComponent(JBLabel("Model / 模型"), modelField, 1, false)
            fb.addLabeledComponent(JBLabel("Authorization (Bearer) / 鉴权"), tokenField, 1, false)
            fb.addSeparator()
            fb.addLabeledComponent(JBLabel("OpenAI max_tokens"), openaiMaxTokens, 1, false)
            fb.addLabeledComponent(JBLabel("OpenAI temperature"), openaiTemperature, 1, false)
            fb.addLabeledComponent(JBLabel("OpenAI top_p"), openaiTopP, 1, false)
            // Keep test button in the right column
            fb.addComponentToRightColumn(testButton)
            // Put preview/curl row on its own line to avoid being squeezed
            val debugRow = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0))
            debugRow.add(previewReqButton); debugRow.add(copyCurlButton)
            fb.addComponent(debugRow)
            return fb.panel
        }

        // Queue & Retry tab
        fun buildQueue(): JPanel {
            val fb = FormBuilder.createFormBuilder()
            fb.addLabeledComponent(JBLabel("Max Concurrency / 最大并发"), maxConcSpinner, 1, false)
            fb.addLabeledComponent(JBLabel("Requests Per Second / 每秒请求"), rpsSpinner, 1, false)
            fb.addLabeledComponent(JBLabel("Queue Size / 队列容量"), queueSizeSpinner, 1, false)
            fb.addLabeledComponent(JBLabel("Request Timeout (s) / 请求超时(秒)"), timeoutSpinner, 1, false)
            fb.addSeparator()
            fb.addComponent(retryEnabled)
            fb.addLabeledComponent(JBLabel("Retry Attempts / 重试次数"), retryAttempts, 1, false)
            fb.addLabeledComponent(JBLabel("Retry Backoff (ms) / 重试退避(毫秒)"), retryBackoff, 1, false)
            fb.addLabeledComponent(JBLabel("History Limit / 历史上限"), historyLimit, 1, false)
            return fb.panel
        }

        // Context tab
        fun buildContext(): JPanel {
            val fb = FormBuilder.createFormBuilder()
            fb.addLabeledComponent(JBLabel("Type Depth / 类型递归深度"), typeDepthSpinner, 1, false)
            fb.addLabeledComponent(JBLabel("Called Depth / 被调方法深度"), calledDepthSpinner, 1, false)
            fb.addComponent(collectCalledCheckbox)
            fb.addLabeledComponent(JBLabel("Max Context Chars / 上下文最大字符"), maxCharsSpinner, 1, false)
            fb.addSeparator()
            fb.addLabeledComponent(JBLabel("Type Suffixes / 类型后缀(逗号分隔)"), typeSuffixesField, 1, false)
            fb.addLabeledComponent(JBLabel("Package Keywords / 包名关键字(逗号)"), pkgKeywordsField, 1, false)
            fb.addLabeledComponent(JBLabel("Annotation Whitelist / 注解白名单(逗号)"), annoWhitelistField, 1, false)
            fb.addSeparator()
            fb.addLabeledComponent(JBLabel("CRUD Filter / 方法类别过滤"), JPanel().apply {
                layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
                add(includeCreate); add(includeRead); add(includeUpdate); add(includeDelete); add(includeOther)
            }, 1, false)
            fb.addLabeledComponent(JBLabel("CRUD Patterns / 名称前缀(逗号分隔)"), JPanel().apply {
                layout = java.awt.GridLayout(2,2,8,4)
                add(JBLabel("CREATE:")); add(patCreate)
                add(JBLabel("READ:")); add(patRead)
                add(JBLabel("UPDATE:")); add(patUpdate)
                add(JBLabel("DELETE:")); add(patDelete)
            }, 1, false)
            return fb.panel
        }

        // Prompt tab
        fun buildPrompt(): JPanel {
            val fb = FormBuilder.createFormBuilder()
            fb.addLabeledComponent(JBLabel("Use custom prompt / 使用自定义 Prompt"), customPromptEnabled, 1, false)
            fb.addLabeledComponent(JBLabel("Prompt Template / 提示词模板"), JBLabel("Placeholders: ${'$'}{ENTRY_CLASS_FQN}, ${'$'}{ENTRY_METHOD}, ${'$'}{ENTRY_METHOD_BASE}, ${'$'}{HTTP_METHOD}, ${'$'}{CONTEXT}"), 1, false)
            fb.addComponentToRightColumn(loadDefaultPromptBtn)
            fb.addComponentFillVertically(JBScrollPane(promptArea), 0)
            return fb.panel
        }

        tabs.addTab("General", buildGeneral())
        tabs.addTab("Queue", buildQueue())
        tabs.addTab("Context", buildContext())
        tabs.addTab("Prompt", buildPrompt())

        add(tabs, java.awt.BorderLayout.CENTER)
    }

    override fun getDisplayName(): String = "GJavaDoc"

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        val s = SettingsState.getInstance(project).state
        return annotationField.text != s.annotation ||
                endpointField.text != s.llmEndpoint ||
                modelField.text != s.model ||
                httpEnabled.isSelected != s.useHttpClient ||
                backendCombo.selectedItem != s.analysisBackend ||
                providerCombo.selectedItem != s.llmProvider ||
                tokenField.text.trim() != (s.authToken ?: "") ||
                (typeDepthSpinner.value as Int) != s.context.typeDepth ||
                (calledDepthSpinner.value as Int) != s.context.calledDepth ||
                collectCalledCheckbox.isSelected != s.context.collectCalled ||
                (maxCharsSpinner.value as Int) != s.context.maxChars ||
                typeSuffixesField.text.trim() != s.context.typeSuffixes.joinToString(",") ||
                pkgKeywordsField.text.trim() != s.context.packageKeywords.joinToString(",") ||
                annoWhitelistField.text.trim() != s.context.annotationWhitelist.joinToString(",") ||
                includeCreate.isSelected != s.crud.includeCreate ||
                includeRead.isSelected != s.crud.includeRead ||
                includeUpdate.isSelected != s.crud.includeUpdate ||
                includeDelete.isSelected != s.crud.includeDelete ||
                includeOther.isSelected != s.crud.includeOther ||
                patCreate.text.trim() != s.crudPatterns.create.joinToString(",") ||
                patRead.text.trim() != s.crudPatterns.read.joinToString(",") ||
                patUpdate.text.trim() != s.crudPatterns.update.joinToString(",") ||
                patDelete.text.trim() != s.crudPatterns.delete.joinToString(",") ||
                perClassDoc.isSelected != s.perClassDocument ||
                groupDocsByModule.isSelected != s.groupDocsByModule ||
                (maxConcSpinner.value as Int) != s.maxConcurrentRequests ||
                (rpsSpinner.value as Double) != s.requestsPerSecond ||
                (queueSizeSpinner.value as Int) != s.queueSize ||
                (timeoutSpinner.value as Int) != s.requestTimeoutSec ||
                retryEnabled.isSelected != s.retry.enabled ||
                (retryAttempts.value as Int) != s.retry.maxAttempts ||
                (retryBackoff.value as Int) != s.retry.backoffMs.toInt() ||
                (historyLimit.value as Int) != s.persist.historyLimit ||
                customPromptEnabled.isSelected != s.customPromptEnabled ||
                promptArea.text != s.customPrompt ||
                (openaiMaxTokens.value as Int) != s.openaiMaxTokens ||
                (openaiTemperature.value as Double) != s.openaiTemperature ||
                (openaiTopP.value as Double) != s.openaiTopP
    }

    override fun apply() {
        val settings = SettingsState.getInstance(project)
        val s = settings.state
        s.annotation = annotationField.text
        s.llmEndpoint = endpointField.text
        s.model = modelField.text
        s.useHttpClient = httpEnabled.isSelected
        s.analysisBackend = backendCombo.selectedItem as String
        s.llmProvider = providerCombo.selectedItem as String
        s.authToken = tokenField.text.trim().ifEmpty { null }
        s.context.typeDepth = (typeDepthSpinner.value as Int)
        s.context.calledDepth = (calledDepthSpinner.value as Int)
        s.context.collectCalled = collectCalledCheckbox.isSelected
        s.context.maxChars = (maxCharsSpinner.value as Int)
        s.context.typeSuffixes = typeSuffixesField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.context.packageKeywords = pkgKeywordsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.context.annotationWhitelist = annoWhitelistField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.crud.includeCreate = includeCreate.isSelected
        s.crud.includeRead = includeRead.isSelected
        s.crud.includeUpdate = includeUpdate.isSelected
        s.crud.includeDelete = includeDelete.isSelected
        s.crud.includeOther = includeOther.isSelected
        s.crudPatterns.create = patCreate.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.crudPatterns.read = patRead.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.crudPatterns.update = patUpdate.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.crudPatterns.delete = patDelete.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.perClassDocument = perClassDoc.isSelected
        s.groupDocsByModule = groupDocsByModule.isSelected
        s.maxConcurrentRequests = (maxConcSpinner.value as Int)
        s.requestsPerSecond = (rpsSpinner.value as Double)
        s.queueSize = (queueSizeSpinner.value as Int)
        s.requestTimeoutSec = (timeoutSpinner.value as Int)
        s.retry.enabled = retryEnabled.isSelected
        s.retry.maxAttempts = (retryAttempts.value as Int)
        s.retry.backoffMs = (retryBackoff.value as Int).toLong()
        s.persist.historyLimit = (historyLimit.value as Int)
        s.customPromptEnabled = customPromptEnabled.isSelected
        s.customPrompt = promptArea.text
        s.openaiMaxTokens = (openaiMaxTokens.value as Int)
        s.openaiTemperature = (openaiTemperature.value as Double)
        s.openaiTopP = (openaiTopP.value as Double)
    }

    override fun reset() {
        val s = SettingsState.getInstance(project).state
        annotationField.text = s.annotation
        endpointField.text = s.llmEndpoint
        modelField.text = s.model
        httpEnabled.isSelected = s.useHttpClient
        backendCombo.selectedItem = s.analysisBackend
        providerCombo.selectedItem = s.llmProvider
        tokenField.text = s.authToken ?: ""
        typeDepthSpinner.value = s.context.typeDepth
        calledDepthSpinner.value = s.context.calledDepth
        collectCalledCheckbox.isSelected = s.context.collectCalled
        maxCharsSpinner.value = s.context.maxChars
        typeSuffixesField.text = s.context.typeSuffixes.joinToString(",")
        pkgKeywordsField.text = s.context.packageKeywords.joinToString(",")
        annoWhitelistField.text = s.context.annotationWhitelist.joinToString(",")
        includeCreate.isSelected = s.crud.includeCreate
        includeRead.isSelected = s.crud.includeRead
        includeUpdate.isSelected = s.crud.includeUpdate
        includeDelete.isSelected = s.crud.includeDelete
        includeOther.isSelected = s.crud.includeOther
        patCreate.text = s.crudPatterns.create.joinToString(",")
        patRead.text = s.crudPatterns.read.joinToString(",")
        patUpdate.text = s.crudPatterns.update.joinToString(",")
        patDelete.text = s.crudPatterns.delete.joinToString(",")
        perClassDoc.isSelected = s.perClassDocument
        groupDocsByModule.isSelected = s.groupDocsByModule
        maxConcSpinner.value = s.maxConcurrentRequests
        rpsSpinner.value = s.requestsPerSecond
        queueSizeSpinner.value = s.queueSize
        timeoutSpinner.value = s.requestTimeoutSec
        retryEnabled.isSelected = s.retry.enabled
        retryAttempts.value = s.retry.maxAttempts
        retryBackoff.value = s.retry.backoffMs.toInt()
        historyLimit.value = s.persist.historyLimit
        customPromptEnabled.isSelected = s.customPromptEnabled
        promptArea.text = s.customPrompt
        openaiMaxTokens.value = s.openaiMaxTokens
        openaiTemperature.value = s.openaiTemperature
        openaiTopP.value = s.openaiTopP
    }

    init {
        testButton.addActionListener {
            val endpoint = endpointField.text.trim()
            val model = modelField.text.trim()
            val timeoutSec = (timeoutSpinner.value as Int).toLong().coerceAtLeast(1)
            if (endpoint.isEmpty() || model.isEmpty()) {
                Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "请先填写 LLM Endpoint 和 Model", NotificationType.WARNING), project)
                return@addActionListener
            }
            ProgressManager.getInstance().run(object: Task.Backgroundable(project, "GJavaDoc: 测试 LLM 连接", false) {
                override fun run(indicator: ProgressIndicator) {
                    val started = System.currentTimeMillis()
                    try {
                        val client = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(timeoutSec))
                            .build()
                        val provider = (providerCombo.selectedItem as String?) ?: "OPENAI"
                        val isOllama = provider == "OLLAMA" || endpoint.contains("/api/chat") || endpoint.contains(":11434")
                        val isDeepSeek = provider == "DEEPSEEK" || endpoint.contains("api.deepseek.com")
                        val body = if (isOllama) {
                            """
                                {
                                  "model": "${model}",
                                  "messages": [
                                    {"role":"user","content":"ping from GJavaDoc"}
                                  ],
                                  "stream": false
                                }
                            """.trimIndent()
                        } else if (isDeepSeek) {
                            val mt = (openaiMaxTokens.value as Int)
                            val tp = String.format(java.util.Locale.US, "%.4f", (openaiTopP.value as Double)).trimEnd('0').trimEnd('.')
                            val temp = String.format(java.util.Locale.US, "%.4f", (openaiTemperature.value as Double)).trimEnd('0').trimEnd('.')
                            // Use DeepSeek default model if the user hasn't set a proper one
                            val deepSeekModel = when {
                                model.startsWith("deepseek") -> model
                                model.contains("chat") || model.contains("reasoner") -> model
                                else -> "deepseek-chat"
                            }
                            """
                                {
                                  "model": "$deepSeekModel",
                                  "messages": [
                                    {"role":"system","content":"You are a helpful assistant."},
                                    {"role":"user","content":"ping from GJavaDoc"}
                                  ],
                                  "stream": false,
                                  "max_tokens": $mt,
                                  "temperature": $temp,
                                  "top_p": $tp
                                }
                            """.trimIndent()
                        } else {
                            val mt = (openaiMaxTokens.value as Int)
                            val tp = String.format(java.util.Locale.US, "%.4f", (openaiTopP.value as Double)).trimEnd('0').trimEnd('.')
                            val temp = String.format(java.util.Locale.US, "%.4f", (openaiTemperature.value as Double)).trimEnd('0').trimEnd('.')
                            """
                                {
                                  "model": "${model}",
                                  "messages": [
                                    {"role":"system","content":"You are a helpful assistant."},
                                    {"role":"user","content":"ping from GJavaDoc"}
                                  ],
                                  "stream": false,
                                  "max_tokens": $mt,
                                  "temperature": $temp,
                                  "top_p": $tp
                                }
                            """.trimIndent()
                        }
                        val reqBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(timeoutSec))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                        val token = tokenField.text.trim()
                        if (token.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $token")
                        val req = reqBuilder.build()
                        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                        val elapsed = System.currentTimeMillis() - started
                        val ok = resp.statusCode() in 200..299
                        val snippet = resp.body().take(160).replace("\n", " ")
                        if (ok) {
                            Notifications.Bus.notify(Notification("GJavaDoc", "LLM 测试成功", "HTTP ${resp.statusCode()} (${elapsed}ms) — ${snippet}...", NotificationType.INFORMATION), project)
                        } else {
                            Notifications.Bus.notify(Notification("GJavaDoc", "LLM 测试失败", "HTTP ${resp.statusCode()} — ${snippet}", NotificationType.ERROR), project)
                        }
                    } catch (t: Throwable) {
                        Notifications.Bus.notify(Notification("GJavaDoc", "LLM 测试异常", t::class.java.simpleName + ": " + (t.message ?: ""), NotificationType.ERROR), project)
                    }
                }
            })
        }
        // Auto-fill defaults when provider changes
        providerCombo.addActionListener {
            val selected = providerCombo.selectedItem as String?
            when (selected) {
                "DEEPSEEK" -> {
                    endpointField.text = "https://api.deepseek.com/v1/chat/completions"
                    modelField.text = "deepseek-chat"
                    httpEnabled.isSelected = true
                }
                "OLLAMA" -> {
                    endpointField.text = "http://127.0.0.1:11434/api/chat"
                    modelField.text = "llama3.1"
                    httpEnabled.isSelected = true
                }
                "OPENAI" -> {
                    endpointField.text = "https://api.openai.com/v1/chat/completions"
                    modelField.text = "gpt-4o-mini"
                    httpEnabled.isSelected = true
                }
            }
        }
        
        loadDefaultPromptBtn.addActionListener {
            promptArea.text = com.gjavadoc.prompt.PromptBuilder.defaultTemplate()
        }
        previewReqButton.addActionListener {
            val endpoint = endpointField.text.trim()
            val model = modelField.text.trim()
            if (endpoint.isEmpty() || model.isEmpty()) {
                Messages.showWarningDialog(project, "请先填写 LLM Endpoint 与 Model", "GJavaDoc")
                return@addActionListener
            }
            val provider = (providerCombo.selectedItem as String?) ?: "OPENAI"
            val isOllama = provider == "OLLAMA" || endpoint.contains("/api/chat") || endpoint.contains(":11434")
            val isDeepSeek = provider == "DEEPSEEK" || endpoint.contains("api.deepseek.com")
            val body = if (isOllama) {
                """
                {
                  "model": "${model}",
                  "messages": [
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false
                }
                """.trimIndent()
            } else if (isDeepSeek) {
                val mt = (openaiMaxTokens.value as Int)
                val tp = String.format(java.util.Locale.US, "%.4f", (openaiTopP.value as Double)).trimEnd('0').trimEnd('.')
                val temp = String.format(java.util.Locale.US, "%.4f", (openaiTemperature.value as Double)).trimEnd('0').trimEnd('.')
                val deepSeekModel = when {
                    model.startsWith("deepseek") -> model
                    model.contains("chat") || model.contains("reasoner") -> model
                    else -> "deepseek-chat"
                }
                """
                {
                  "model": "$deepSeekModel",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant."},
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false,
                  "max_tokens": $mt,
                  "temperature": $temp,
                  "top_p": $tp
                }
                """.trimIndent()
            } else {
                val mt = (openaiMaxTokens.value as Int)
                val tp = String.format(java.util.Locale.US, "%.4f", (openaiTopP.value as Double)).trimEnd('0').trimEnd('.')
                val temp = String.format(java.util.Locale.US, "%.4f", (openaiTemperature.value as Double)).trimEnd('0').trimEnd('.')
                """
                {
                  "model": "${model}",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant."},
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false,
                  "max_tokens": $mt,
                  "temperature": $temp,
                  "top_p": $tp
                }
                """.trimIndent()
            }
            val token = tokenField.text.trim()
            val masked = if (token.isEmpty()) "" else (if (token.length <= 10) "***" else token.take(6) + "..." + token.takeLast(3))
            val headers = buildString {
                appendLine("Content-Type: application/json; charset=utf-8")
                appendLine("Accept: application/json")
                if (token.isNotEmpty()) appendLine("Authorization: Bearer ${masked}")
            }
            val preview = "POST ${endpoint}\n${headers}\n${body}"
            // Copy to clipboard for convenience
            CopyPasteManager.getInstance().setContents(StringSelection(preview))
            Messages.showMultilineInputDialog(project, preview, "GJavaDoc: 预览请求（已复制到剪贴板）", preview, null, null)
        }

        copyCurlButton.addActionListener {
            val endpoint = endpointField.text.trim()
            val model = modelField.text.trim()
            if (endpoint.isEmpty() || model.isEmpty()) {
                Messages.showWarningDialog(project, "请先填写 LLM Endpoint 与 Model", "GJavaDoc")
                return@addActionListener
            }
            val provider = (providerCombo.selectedItem as String?) ?: "OPENAI"
            val isOllama = provider == "OLLAMA" || endpoint.contains("/api/chat") || endpoint.contains(":11434")
            val isDeepSeek = provider == "DEEPSEEK" || endpoint.contains("api.deepseek.com")
            val mt = (openaiMaxTokens.value as Int)
            val tp = String.format(java.util.Locale.US, "%.4f", (openaiTopP.value as Double)).trimEnd('0').trimEnd('.')
            val temp = String.format(java.util.Locale.US, "%.4f", (openaiTemperature.value as Double)).trimEnd('0').trimEnd('.')
            val body = if (isOllama) {
                """
                {
                  "model": "${model}",
                  "messages": [
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false
                }
                """.trimIndent()
            } else if (isDeepSeek) {
                val deepSeekModel = when {
                    model.startsWith("deepseek") -> model
                    model.contains("chat") || model.contains("reasoner") -> model
                    else -> "deepseek-chat"
                }
                """
                {
                  "model": "$deepSeekModel",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant."},
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false,
                  "max_tokens": $mt,
                  "temperature": $temp,
                  "top_p": $tp
                }
                """.trimIndent()
            } else {
                """
                {
                  "model": "${model}",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant."},
                    {"role":"user","content":"ping from GJavaDoc"}
                  ],
                  "stream": false,
                  "max_tokens": $mt,
                  "temperature": $temp,
                  "top_p": $tp
                }
                """.trimIndent()
            }
            val token = tokenField.text.trim()
            val authHeader = if (token.isNotEmpty()) " -H \"Authorization: Bearer ${'$'}token\"" else ""
            val curlBash = "curl -sS -i --http1.1 -X POST \"${endpoint}\" -H \"Content-Type: application/json\" -H \"Accept: application/json\"${authHeader} -d '${body.replace("'", "'\\''")}'"
            val curlPS = "curl -sS -i --http1.1 -X POST \"${endpoint}\" -H \"Content-Type: application/json\" -H \"Accept: application/json\"${authHeader} -d \"${body.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            val msg = "Bash:\n${curlBash}\n\nPowerShell/cmd:\n${curlPS}"
            CopyPasteManager.getInstance().setContents(StringSelection(curlPS))
            Messages.showMultilineInputDialog(project, msg, "GJavaDoc: curl 已复制（PowerShell 版）", msg, null, null)
        }
    }
}
