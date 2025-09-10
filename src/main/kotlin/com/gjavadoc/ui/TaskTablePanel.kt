package com.gjavadoc.ui

import com.gjavadoc.model.TaskModel
import com.gjavadoc.services.TaskRepository
import com.gjavadoc.settings.SettingsState
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import javax.swing.*

class TaskTablePanel(private val project: Project) {
    private val tableModel = TaskTableModel(project)
    private val table = JBTable(tableModel)
    private val details = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val queueStatusLabel = javax.swing.JLabel("Queue: Stopped")
    private val queueSpinner = javax.swing.JLabel(AnimatedIcon.Default()).apply { isVisible = false }
    private val busConn = project.messageBus.connect()
    @Volatile private var lastQueueUiUpdateMs: Long = 0L

    val component: JComponent

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openOutputForSelected()
            }
        })
        table.selectionModel.addListSelectionListener { updateDetails() }

        val repo = project.getService(TaskRepository::class.java)

        val openJson = object : com.intellij.openapi.actionSystem.AnAction("Open JSON", null, AllIcons.FileTypes.Json) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                openPath(task.result?.jsonPath)
            }
        }
        val openMd = object : com.intellij.openapi.actionSystem.AnAction("Open Markdown", null, AllIcons.FileTypes.Text) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                openPath(task.result?.mdPath)
            }
        }
        val openCtx = object : com.intellij.openapi.actionSystem.AnAction("Open Context", null, AllIcons.Actions.Preview) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                openPath(task.result?.ctxPath)
            }
        }

        val openSource = object : com.intellij.openapi.actionSystem.AnAction("Open Source", null, AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                val anchor = task.sliceAnchors?.firstOrNull()
                if (anchor != null) {
                    openPath(anchor.first, anchor.second.first)
                }
            }
        }

        // Will read selected module from settings (updated by UI combo below)
        val runScan = object : com.intellij.openapi.actionSystem.AnAction("Run Scan", null, AllIcons.Actions.Execute) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val mod = com.gjavadoc.settings.SettingsState.getInstance(project).state.ui.lastModule
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).startFullScan(moduleName = if (mod == null || mod == "ALL") null else mod)
            }
        }
        val stopQueue = object : com.intellij.openapi.actionSystem.AnAction("Stop", null, AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).stop()
            }
        }

        val retry = object : com.intellij.openapi.actionSystem.AnAction("Retry", null, AllIcons.Actions.Restart) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).retry(task.taskId)
            }
        }
        val retryFailed = object : com.intellij.openapi.actionSystem.AnAction("Retry Failed", null, AllIcons.Actions.ForceRefresh) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).retryAllFailed()
            }
        }
        val retryTimeouts = object : com.intellij.openapi.actionSystem.AnAction("Retry Timeouts", null, AllIcons.Actions.Rerun) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).retryAllTimeouts()
            }
        }
        val requeue = object : com.intellij.openapi.actionSystem.AnAction("Requeue", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).requeue(task.taskId)
            }
        }

        val editPrompt = object : com.intellij.openapi.actionSystem.AnAction("Edit Prompt", null, AllIcons.Actions.Edit) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "GJavaDoc")
            }
        }

        val clearAll = object : com.intellij.openapi.actionSystem.AnAction("Clear", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).resetAll()
            }
        }

        val cancelSelected = object : com.intellij.openapi.actionSystem.AnAction("Cancel", null, AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val idx = table.selectedRow
                val task = tableModel.getItemAt(idx) ?: return
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).cancel(task.taskId)
            }
        }

        val cancelAll = object : com.intellij.openapi.actionSystem.AnAction("Cancel All", null, AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).cancelAll()
            }
        }

        val resumeFromList = object : com.intellij.openapi.actionSystem.AnAction("Resume From List", null, AllIcons.Actions.MoveDown) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val tip = "请输入要继续的任务列表（每行一个）：\n" +
                        "• com.pkg.Class#method\n" +
                        "• com.pkg.Class#method(argTypes...)\n" +
                        "• com.pkg.Class#CLASS（类级文档）\n" +
                        "• 或直接粘贴输出文件前缀/文件名（将尽力解析）"
                val input = Messages.showMultilineInputDialog(project, tip, "GJavaDoc: 根据列表继续任务", "", null, null)
                if (input == null || input.isBlank()) return
                val skip = Messages.showYesNoDialog(project, "是否跳过已成功的任务？", "GJavaDoc", "是", "否", null) == Messages.YES
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).resumeFromList(input, skip)
            }
        }

        val decorator = ToolbarDecorator.createDecorator(table)
            .addExtraActions(runScan, stopQueue, cancelSelected, cancelAll, retry, retryFailed, retryTimeouts, requeue, clearAll, resumeFromList, openSource, openCtx, openJson, openMd, editPrompt)

        val filterPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val sUi = SettingsState.getInstance(project).state.ui

            // Row 1: filters; Row 2: queue status (left-aligned)
            val rowTop = JPanel(BorderLayout())
            val leftTop = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
            val rightTop = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT))
            val statusBox = javax.swing.JComboBox(arrayOf("ALL","QUEUED","RUNNING","CANCELLED","FAILED","PARTIAL","SUCCEEDED"))
            statusBox.selectedItem = sUi.lastStatusFilter
            val search = javax.swing.JTextField(20)
            search.text = sUi.lastSearchText
            val apply = javax.swing.JButton("Apply")
            // Module selector
            val moduleNames = listOf("ALL") + com.intellij.openapi.module.ModuleManager.getInstance(project).modules.map { it.name }.sorted()
            val moduleBox = javax.swing.JComboBox(moduleNames.toTypedArray())
            if (moduleNames.contains(sUi.lastModule)) moduleBox.selectedItem = sUi.lastModule else moduleBox.selectedItem = "ALL"
            // Compact mode toggle: hide JSON/Markdown columns
            val cbCompact = JBCheckBox("Compact", true)
            leftTop.add(javax.swing.JLabel("Module:")); leftTop.add(moduleBox)
            leftTop.add(javax.swing.JLabel("Status:")); leftTop.add(statusBox)
            leftTop.add(javax.swing.JLabel("Search:")); leftTop.add(search)
            leftTop.add(apply)
            leftTop.add(cbCompact)
            rowTop.add(leftTop, BorderLayout.WEST)
            rowTop.add(rightTop, BorderLayout.EAST)
            add(rowTop)

            // (Queue status moved to bottom panel)

            // Advanced panel (collapsed by default): CRUD + Run
            val defaults = SettingsState.getInstance(project).state.crud
            val cbCreate = JBCheckBox("CREATE", defaults.includeCreate)
            val cbRead = JBCheckBox("READ", defaults.includeRead)
            val cbUpdate = JBCheckBox("UPDATE", defaults.includeUpdate)
            val cbDelete = JBCheckBox("DELETE", defaults.includeDelete)
            val cbOther = JBCheckBox("OTHER", defaults.includeOther)
            val advancedPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
            advancedPanel.add(javax.swing.JLabel("CRUD:"))
            advancedPanel.add(cbCreate); advancedPanel.add(cbRead); advancedPanel.add(cbUpdate); advancedPanel.add(cbDelete); advancedPanel.add(cbOther)
            runScan.templatePresentation.text = "Run Scan"
            val runBtn = javax.swing.JButton("Run")
            advancedPanel.add(runBtn)
            val toggleAdvanced = JToggleButton("Advanced")
            toggleAdvanced.addActionListener {
                advancedPanel.isVisible = toggleAdvanced.isSelected
                advancedPanel.revalidate(); advancedPanel.repaint()
            }
            // put the toggle near filters (left side)
            leftTop.add(toggleAdvanced)
            advancedPanel.isVisible = false
            add(advancedPanel)

            // Persist module choice
            moduleBox.addActionListener {
                SettingsState.getInstance(project).state.ui.lastModule = moduleBox.selectedItem as String
            }

            // Run with CRUD overrides
            runBtn.addActionListener {
                val override = SettingsState.CrudFilter(
                    includeCreate = cbCreate.isSelected,
                    includeRead = cbRead.isSelected,
                    includeUpdate = cbUpdate.isSelected,
                    includeDelete = cbDelete.isSelected,
                    includeOther = cbOther.isSelected,
                )
                val mod = moduleBox.selectedItem as String
                SettingsState.getInstance(project).state.ui.lastModule = mod
                project.getService(com.gjavadoc.services.GJavaDocService::class.java).startFullScan(override, moduleName = if (mod == "ALL") null else mod)
            }

            fun applyFiltersToModel() {
                tableModel.setFilters(statusBox.selectedItem as String, search.text)
                val ui = SettingsState.getInstance(project).state.ui
                ui.lastStatusFilter = statusBox.selectedItem as String
                ui.lastSearchText = search.text
            }
            apply.addActionListener { applyFiltersToModel() }

            // Compact mode handler: hide or show heavy columns
            fun setColumnVisible(colIndex: Int, visible: Boolean) {
                val cm = table.columnModel
                if (colIndex < 0 || colIndex >= cm.columnCount) return
                val col = cm.getColumn(colIndex)
                if (visible) {
                    col.minWidth = 50
                    col.preferredWidth = if (colIndex == 1) 600 else 140
                    col.maxWidth = Int.MAX_VALUE
                    col.resizable = true
                } else {
                    col.minWidth = 0
                    col.preferredWidth = 0
                    col.maxWidth = 0
                    col.resizable = false
                }
            }
            fun applyCompactMode() {
                val compact = cbCompact.isSelected
                // Hide JSON, Markdown columns when compact
                setColumnVisible(5, !compact)
                setColumnVisible(6, !compact)
                // Favor Entry column width
                setColumnVisible(1, true)
                table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            }
            cbCompact.addActionListener { applyCompactMode() }

            // Row 2: sort + pagination
            val rowBottom = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
            rowBottom.add(javax.swing.JLabel("Sort:"))
            val sortByBox = javax.swing.JComboBox(arrayOf("CreatedAt","Entry","Status","Progress"))
            sortByBox.selectedItem = when (sUi.sortBy) {
                "ENTRY" -> "Entry"
                "STATUS" -> "Status"
                "PROGRESS" -> "Progress"
                else -> "CreatedAt"
            }
            val sortOrderBox = javax.swing.JComboBox(arrayOf("Desc","Asc"))
            sortOrderBox.selectedItem = if (sUi.sortAsc) "Asc" else "Desc"
            rowBottom.add(sortByBox); rowBottom.add(sortOrderBox)

            fun applySortToModel() {
                val sortBy = when (sortByBox.selectedItem as String) {
                    "Entry" -> TaskTableModel.SortBy.ENTRY
                    "Status" -> TaskTableModel.SortBy.STATUS
                    "Progress" -> TaskTableModel.SortBy.PROGRESS
                    else -> TaskTableModel.SortBy.CREATED_AT
                }
                val asc = (sortOrderBox.selectedItem as String) == "Asc"
                tableModel.setSort(sortBy, asc)
                val ui = SettingsState.getInstance(project).state.ui
                ui.sortBy = when (sortBy) {
                    TaskTableModel.SortBy.ENTRY -> "ENTRY"
                    TaskTableModel.SortBy.STATUS -> "STATUS"
                    TaskTableModel.SortBy.PROGRESS -> "PROGRESS"
                    TaskTableModel.SortBy.CREATED_AT -> "CREATED_AT"
                }
                ui.sortAsc = asc
            }
            applyFiltersToModel()
            applySortToModel()
            sortByBox.addActionListener { applySortToModel() }
            sortOrderBox.addActionListener { applySortToModel() }

            rowBottom.add(javax.swing.JLabel("Page Size:"))
            val pageSizeSpinner = JSpinner(SpinnerNumberModel(sUi.pageSize, 5, 500, 5))
            rowBottom.add(pageSizeSpinner)
            val btnFirst = javax.swing.JButton("首页")
            val btnPrev = javax.swing.JButton("上一页")
            val btnNext = javax.swing.JButton("下一页")
            val btnLast = javax.swing.JButton("末页")
            rowBottom.add(btnFirst); rowBottom.add(btnPrev); rowBottom.add(btnNext); rowBottom.add(btnLast)
            val pageLabel = javax.swing.JLabel("")
            rowBottom.add(pageLabel)
            fun updatePageLabel() {
                val totalPages = tableModel.totalPages()
                val current = if (totalPages == 0) 0 else tableModel.pageIndex + 1
                pageLabel.text = "Page ${current} / ${totalPages}  Total: ${tableModel.totalItems()}"
            }
            pageSizeSpinner.addChangeListener {
                val size = (pageSizeSpinner.value as Int)
                tableModel.setPageSize(size)
                updatePageLabel()
                SettingsState.getInstance(project).state.ui.pageSize = size
            }
            btnFirst.addActionListener { tableModel.setPage(0); updatePageLabel() }
            btnPrev.addActionListener { tableModel.prevPage(); updatePageLabel() }
            btnNext.addActionListener { tableModel.nextPage(); updatePageLabel() }
            btnLast.addActionListener { tableModel.setPage(kotlin.math.max(0, tableModel.totalPages() - 1)); updatePageLabel() }
            tableModel.setPageSize(sUi.pageSize)
            tableModel.onDataChanged = { javax.swing.SwingUtilities.invokeLater { updatePageLabel() } }
            updatePageLabel()
            // Keyboard shortcuts for pagination
            val whenCond = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
            rowBottom.registerKeyboardAction({ tableModel.prevPage(); updatePageLabel() }, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), whenCond)
            rowBottom.registerKeyboardAction({ tableModel.nextPage(); updatePageLabel() }, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), whenCond)
            rowBottom.registerKeyboardAction({ tableModel.setPage(0); updatePageLabel() }, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), whenCond)
            rowBottom.registerKeyboardAction({ tableModel.setPage(kotlin.math.max(0, tableModel.totalPages() - 1)); updatePageLabel() }, KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), whenCond)

            add(rowBottom)
            // initialize compact mode after table is realized
            javax.swing.SwingUtilities.invokeLater { applyCompactMode() }
        }

        val main = JPanel(BorderLayout())
        main.add(filterPanel, BorderLayout.NORTH)
        main.add(decorator.createPanel(), BorderLayout.CENTER)
        // Bottom panel: queue live status above selection/details area
        val statusRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        statusRow.add(queueSpinner)
        statusRow.add(queueStatusLabel)
        val detailsScroll = JBScrollPane(details)
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(statusRow, BorderLayout.NORTH)
        bottomPanel.add(detailsScroll, BorderLayout.CENTER)
        main.add(bottomPanel, BorderLayout.SOUTH)
        component = main

        // Subscribe to queue events for live status updates
        busConn.subscribe(com.gjavadoc.bus.GJavaDocBusTopics.QUEUE_EVENTS, object: com.gjavadoc.bus.QueueEventsListener {
            override fun onQueueStarted() {
                javax.swing.SwingUtilities.invokeLater {
                    queueStatusLabel.text = "Queue: Running"
                    queueStatusLabel.foreground = JBColor(Color(0x2E7D32), Color(0x81C784))
                    queueSpinner.isVisible = true
                }
            }
            override fun onQueueStopped() {
                javax.swing.SwingUtilities.invokeLater {
                    queueStatusLabel.text = "Queue: Stopped"
                    queueStatusLabel.foreground = JBColor(Color(0xB71C1C), Color(0xEF9A9A))
                    queueSpinner.isVisible = false
                }
            }
            override fun onQueueHeartbeat(status: com.gjavadoc.bus.QueueStatus) {
                javax.swing.SwingUtilities.invokeLater {
                    val now = System.currentTimeMillis()
                    if (now - lastQueueUiUpdateMs < 250) return@invokeLater
                    lastQueueUiUpdateMs = now
                    val queued = status.backlogSize + status.queueSize
                    queueStatusLabel.text = "Queue: " + (if (status.running) "Running" else "Stopped") +
                            " | Running: ${status.runningCount}/${status.maxConcurrent}" +
                            " | Waiting: ${queued} (remCap=${status.queueRemainingCapacity})" +
                            " | RPS: ${"%.2f".format(status.requestsPerSecond)}"
                    if (status.running) {
                        queueStatusLabel.foreground = JBColor(Color(0x2E7D32), Color(0x81C784))
                    } else {
                        queueStatusLabel.foreground = JBColor(Color(0xB71C1C), Color(0xEF9A9A))
                    }
                    queueSpinner.isVisible = status.running && (status.runningCount > 0 || queued > 0)
                }
            }
        })
    }

    private fun openOutputForSelected() {
        val idx = table.selectedRow
        val task = tableModel.getItemAt(idx) ?: return
        openPath(task.result?.mdPath ?: task.result?.jsonPath)
    }

    private fun openPath(path: String?, line: Int? = null) {
        if (path == null) return
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        if (line != null) OpenFileDescriptor(project, vf, line - 1, 0).navigate(true)
        else OpenFileDescriptor(project, vf).navigate(true)
    }

    private fun updateDetails() {
        val idx = table.selectedRow
        val task = tableModel.getItemAt(idx)
        details.text = formatTask(task)
    }

    private fun formatTask(t: TaskModel?): String {
        if (t == null) return "No selection"
        return buildString {
            appendLine("Task: ${t.taskId}")
            appendLine("Status: ${t.status}")
            appendLine("Progress: ${(t.progress.fraction*100).toInt()}% ${t.progress.message?:""}")
            if (t.cgSummary != null) appendLine("CG: ${t.cgSummary}")
            if (t.result?.jsonPath != null) appendLine("JSON: ${t.result?.jsonPath}")
            if (t.result?.mdPath != null) appendLine("MD: ${t.result?.mdPath}")
            if (t.error != null) appendLine("Error: ${t.error?.type} ${t.error?.message}")
        }
    }
}
