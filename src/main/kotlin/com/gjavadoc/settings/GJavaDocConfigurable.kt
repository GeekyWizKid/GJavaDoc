package com.gjavadoc.settings

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import java.awt.FlowLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.*

class GJavaDocConfigurable() : Configurable {
    private val project: Project by lazy {
        ProjectManager.getInstance().openProjects.firstOrNull()
            ?: ProjectManager.getInstance().defaultProject
    }
    private val annotationField = JBTextField().apply { columns = 30 }
    private val endpointField = JBTextField().apply { columns = 35 }
    private val modelField = JBTextField().apply { columns = 20 }
    private val maxConcSpinner = JSpinner(SpinnerNumberModel(2, 1, 64, 1)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }
    private val rpsSpinner = JSpinner(SpinnerNumberModel(1.5, 0.1, 100.0, 0.1)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }
    private val queueSizeSpinner = JSpinner(SpinnerNumberModel(32, 1, 10000, 1)).apply {
        preferredSize = java.awt.Dimension(100, preferredSize.height)
    }
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(60, 1, 600, 1)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }
    private val retryEnabled = JBCheckBox("Enable retry")
    private val retryAttempts = JSpinner(SpinnerNumberModel(3, 1, 10, 1)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }
    private val retryBackoff = JSpinner(SpinnerNumberModel(1500, 100, 60000, 100)).apply {
        preferredSize = java.awt.Dimension(100, preferredSize.height)
    }
    private val historyLimit = JSpinner(SpinnerNumberModel(200, 10, 10000, 10)).apply {
        preferredSize = java.awt.Dimension(100, preferredSize.height)
    }

    private val httpEnabled = JBCheckBox("Use HTTP LLM client / 使用 HTTP LLM 客户端")
    private val backendCombo = javax.swing.JComboBox(arrayOf("STUB", "WALA")).apply {
        preferredSize = java.awt.Dimension(120, preferredSize.height)
    }
    private val providerCombo = javax.swing.JComboBox(arrayOf("OPENAI", "OLLAMA", "DEEPSEEK")).apply {
        preferredSize = java.awt.Dimension(150, preferredSize.height)
    }
    private val tokenField = JBPasswordField().apply { columns = 30 }
    private val tokenToggleButton = JButton(AllIcons.Actions.Show).apply {
        preferredSize = java.awt.Dimension(30, preferredSize.height)
        toolTipText = "Show token"
    }
    private val testButton = JButton("Test LLM / 测试 LLM")
    private val openaiMaxTokens = JSpinner(SpinnerNumberModel(1024, 1, 65536, 32)).apply {
        preferredSize = java.awt.Dimension(100, preferredSize.height)
    }
    private val openaiTemperature = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.1)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }
    private val openaiTopP = JSpinner(SpinnerNumberModel(1.0, 0.0, 1.0, 0.05)).apply {
        preferredSize = java.awt.Dimension(80, preferredSize.height)
    }

    // Context & Type collection options
    private val typeDepthSpinner = JSpinner(SpinnerNumberModel(2, 0, 6, 1)).apply {
        preferredSize = java.awt.Dimension(60, preferredSize.height)
    }
    private val calledDepthSpinner = JSpinner(SpinnerNumberModel(1, 0, 6, 1)).apply {
        preferredSize = java.awt.Dimension(60, preferredSize.height)
    }
    private val collectCalledCheckbox = JBCheckBox("Collect called methods / 收集被调方法", true)
    private val maxCharsSpinner = JSpinner(SpinnerNumberModel(20000, 2000, 200000, 1000)).apply {
        preferredSize = java.awt.Dimension(120, preferredSize.height)
    }
    private val typeSuffixesField = JBTextField("DTO,VO").apply { columns = 20 }
    private val pkgKeywordsField = JBTextField(".dto,.vo,.entity").apply { columns = 25 }
    private val annoWhitelistField = JBTextField("Entity,jakarta.persistence.Entity,javax.persistence.Entity").apply { columns = 40 }

    // MyBatis configuration
    private val mybatisEnabled = JBCheckBox("Enable MyBatis scanning / 启用MyBatis扫描", true)
    private val includeXmlMappings = JBCheckBox("Include XML mappings / 包含XML映射", true)
    private val includeMybatisPlusBase = JBCheckBox("Include MyBatis-Plus BaseMapper / 包含MyBatis-Plus BaseMapper", true)
    private val strictServiceMapping = JBCheckBox("Strict service mapping / 严格服务映射（仅扫描与服务类相关的映射）", true)
    private val mapperSuffixesField = JBTextField("Mapper,DAO")
    private val xmlScanPathsField = JBTextField("src/main/resources,src/test/resources")

    // CRUD filter
    private val includeCreate = JBCheckBox("Include CREATE / 包含新增", true)
    private val includeRead = JBCheckBox("Include READ / 包含查询", true)
    private val includeUpdate = JBCheckBox("Include UPDATE / 包含更新", true)
    private val includeDelete = JBCheckBox("Include DELETE / 包含删除", true)
    private val includeOther = JBCheckBox("Include OTHER / 包含其他", true)
    private val patCreate = JBTextField("create,add,insert,save,new").apply { columns = 25 }
    private val patRead = JBTextField("get,query,list,find,select,count,load").apply { columns = 25 }
    private val patUpdate = JBTextField("update,set,modify,patch,enable,disable").apply { columns = 25 }
    private val patDelete = JBTextField("delete,remove,del,clear").apply { columns = 25 }
    private val perClassDoc = JBCheckBox("Per-class document / 类级文档（一个类→一个文档）", false)
    private val groupDocsByModule = JBCheckBox("Group docs by module / 文档按模块分目录", false)

    // Prompt customization
    private val customPromptEnabled = JBCheckBox("Use custom prompt / 使用自定义 Prompt", false)
    private val promptArea = JBTextArea(12, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        maximumSize = java.awt.Dimension(800, 300)
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
            fb.addLabeledComponent(JBLabel("Authorization (Bearer) / 鉴权"), JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(tokenField)
                add(tokenToggleButton)
            }, 1, false)
            fb.addSeparator()
            fb.addLabeledComponent(JBLabel("OpenAI max_tokens"), openaiMaxTokens, 1, false)
            fb.addLabeledComponent(JBLabel("OpenAI temperature"), openaiTemperature, 1, false)
            fb.addLabeledComponent(JBLabel("OpenAI top_p"), openaiTopP, 1, false)
            // Keep test button in the right column
            fb.addComponentToRightColumn(testButton)
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
            // MyBatis Configuration
            fb.addComponent(mybatisEnabled)
            fb.addComponent(includeXmlMappings)
            fb.addComponent(includeMybatisPlusBase)
            fb.addComponent(strictServiceMapping)
            fb.addLabeledComponent(JBLabel("Mapper Suffixes / Mapper后缀(逗号分隔)"), mapperSuffixesField, 1, false)
            fb.addLabeledComponent(JBLabel("XML Scan Paths / XML扫描路径(逗号分隔)"), xmlScanPathsField, 1, false)
            fb.addSeparator()
            fb.addLabeledComponent(JBLabel("CRUD Filter / 方法类别过滤"), JPanel().apply {
                layout = java.awt.BorderLayout()
                val crudPanel = JPanel().apply {
                    layout = java.awt.GridBagLayout()
                    val gbc = java.awt.GridBagConstraints().apply {
                        insets = java.awt.Insets(2, 4, 2, 4)
                        anchor = java.awt.GridBagConstraints.WEST
                        fill = java.awt.GridBagConstraints.NONE
                    }

                    // CREATE row
                    gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.gridwidth = 1
                    add(includeCreate, gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    patCreate.maximumSize = java.awt.Dimension(300, patCreate.preferredSize.height)
                    add(patCreate, gbc)

                    // READ row
                    gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
                    add(includeRead, gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    patRead.maximumSize = java.awt.Dimension(300, patRead.preferredSize.height)
                    add(patRead, gbc)

                    // UPDATE row
                    gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
                    add(includeUpdate, gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    patUpdate.maximumSize = java.awt.Dimension(300, patUpdate.preferredSize.height)
                    add(patUpdate, gbc)

                    // DELETE row
                    gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
                    add(includeDelete, gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    patDelete.maximumSize = java.awt.Dimension(300, patDelete.preferredSize.height)
                    add(patDelete, gbc)

                    // OTHER row
                    gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0
                    add(includeOther, gbc)
                    gbc.gridx = 1; gbc.weightx = 1.0
                    add(JBLabel("(Other methods not matching above patterns)").apply {
                        foreground = java.awt.Color.GRAY
                        font = font.deriveFont(font.size - 1.0f)
                    }, gbc)
                }
                add(crudPanel, java.awt.BorderLayout.CENTER)
            }, 1, false)
            return fb.panel
        }

        // Prompt tab
        fun buildPrompt(): JPanel {
            val fb = FormBuilder.createFormBuilder()
            fb.addLabeledComponent(JBLabel("Use custom prompt / 使用自定义 Prompt"), customPromptEnabled, 1, false)
            fb.addLabeledComponent(JBLabel("Prompt Template / 提示词模板"), JBLabel("Placeholders: ${'$'}{ENTRY_CLASS_FQN}, ${'$'}{ENTRY_METHOD}, ${'$'}{ENTRY_METHOD_BASE}, ${'$'}{HTTP_METHOD}, ${'$'}{CONTEXT}"), 1, false)
            fb.addComponentToRightColumn(loadDefaultPromptBtn)

            // Create a constrained scroll pane for the prompt area
            val scrollPane = JBScrollPane(promptArea).apply {
                preferredSize = java.awt.Dimension(800, 300)
                maximumSize = java.awt.Dimension(800, 300)
                minimumSize = java.awt.Dimension(400, 200)
            }
            fb.addComponentFillVertically(scrollPane, 0)
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
                String(tokenField.password).trim() != (s.authToken ?: "") ||
                typeDepthSpinner.value != s.context.typeDepth ||
                calledDepthSpinner.value != s.context.calledDepth ||
                collectCalledCheckbox.isSelected != s.context.collectCalled ||
                maxCharsSpinner.value != s.context.maxChars ||
                typeSuffixesField.text != s.context.typeSuffixes.joinToString(",") ||
                pkgKeywordsField.text != s.context.packageKeywords.joinToString(",") ||
                annoWhitelistField.text != s.context.annotationWhitelist.joinToString(",") ||
                (maxCharsSpinner.value as Int) != s.context.maxChars ||
                typeSuffixesField.text.trim() != s.context.typeSuffixes.joinToString(",") ||
                pkgKeywordsField.text.trim() != s.context.packageKeywords.joinToString(",") ||
                annoWhitelistField.text.trim() != s.context.annotationWhitelist.joinToString(",") ||
                mybatisEnabled.isSelected != s.mybatis.enabled ||
                includeXmlMappings.isSelected != s.mybatis.includeXmlMappings ||
                includeMybatisPlusBase.isSelected != s.mybatis.includeMybatisPlusBaseMethods ||
                strictServiceMapping.isSelected != s.mybatis.strictServiceMapping ||
                mapperSuffixesField.text.trim() != s.mybatis.mapperSuffixes.joinToString(",") ||
                xmlScanPathsField.text.trim() != s.mybatis.xmlScanPaths.joinToString(",") ||
                includeCreate.isSelected != s.crud.includeCreate ||
                includeRead.isSelected != s.crud.includeRead ||
                includeUpdate.isSelected != s.crud.includeUpdate ||
                includeDelete.isSelected != s.crud.includeDelete ||
                includeOther.isSelected != s.crud.includeOther ||
                patCreate.text != s.crudPatterns.create.joinToString(",") ||
                patRead.text != s.crudPatterns.read.joinToString(",") ||
                patUpdate.text != s.crudPatterns.update.joinToString(",") ||
                patDelete.text != s.crudPatterns.delete.joinToString(",") ||
                perClassDoc.isSelected != s.perClassDocument ||
                groupDocsByModule.isSelected != s.groupDocsByModule ||
                maxConcSpinner.value != s.maxConcurrentRequests ||
                rpsSpinner.value != s.requestsPerSecond ||
                queueSizeSpinner.value != s.queueSize ||
                timeoutSpinner.value != s.requestTimeoutSec ||
                retryEnabled.isSelected != s.retry.enabled ||
                retryAttempts.value != s.retry.maxAttempts ||
                retryBackoff.value != s.retry.backoffMs.toInt() ||
                historyLimit.value != s.persist.historyLimit ||
                customPromptEnabled.isSelected != s.customPromptEnabled ||
                promptArea.text != s.customPrompt ||
                openaiMaxTokens.value != s.openaiMaxTokens ||
                openaiTemperature.value != s.openaiTemperature ||
                openaiTopP.value != s.openaiTopP
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
        s.authToken = String(tokenField.password).trim().ifEmpty { null }
        s.context.typeDepth = (typeDepthSpinner.value as Int)
        s.context.calledDepth = (calledDepthSpinner.value as Int)
        s.context.collectCalled = collectCalledCheckbox.isSelected
        s.context.maxChars = (maxCharsSpinner.value as Int)
        s.context.typeSuffixes = typeSuffixesField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.context.packageKeywords = pkgKeywordsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.context.annotationWhitelist = annoWhitelistField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.mybatis.enabled = mybatisEnabled.isSelected
        s.mybatis.includeXmlMappings = includeXmlMappings.isSelected
        s.mybatis.includeMybatisPlusBaseMethods = includeMybatisPlusBase.isSelected
        s.mybatis.strictServiceMapping = strictServiceMapping.isSelected
        s.mybatis.mapperSuffixes = mapperSuffixesField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        s.mybatis.xmlScanPaths = xmlScanPathsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
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
        isResetting = true  // Set flag before resetting
        try {
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
            mybatisEnabled.isSelected = s.mybatis.enabled
            includeXmlMappings.isSelected = s.mybatis.includeXmlMappings
            includeMybatisPlusBase.isSelected = s.mybatis.includeMybatisPlusBaseMethods
            strictServiceMapping.isSelected = s.mybatis.strictServiceMapping
            mapperSuffixesField.text = s.mybatis.mapperSuffixes.joinToString(",")
            xmlScanPathsField.text = s.mybatis.xmlScanPaths.joinToString(",")
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
        } finally {
            isResetting = false  // Clear flag after resetting
        }
    }

    private var isResetting = false  // Flag to prevent auto-fill during reset

    init {
        tokenToggleButton.addActionListener {
            val currentEchoChar = tokenField.echoChar
            if (currentEchoChar == 0.toChar()) {
                // Currently visible, hide it
                tokenField.echoChar = '•'
                tokenToggleButton.icon = AllIcons.Actions.Show
                tokenToggleButton.toolTipText = "Show token"
            } else {
                // Currently hidden, show it
                tokenField.echoChar = 0.toChar()
                tokenToggleButton.icon = AllIcons.General.HideToolWindow
                tokenToggleButton.toolTipText = "Hide token"
            }
        }

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
                        val token = String(tokenField.password).trim()
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
            // Don't auto-fill during reset
            if (isResetting) return@addActionListener

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
    }
}
