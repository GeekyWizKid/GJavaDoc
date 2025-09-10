package com.gjavadoc.lifecycle

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 在项目打开后执行的活动：自动恢复未完成任务（静默）。
 * 采用 ProjectActivity（suspend execute）以兼容 2024+，并通过反射避免类解析早期失败。
 */
class AutoResumeStartup : ProjectActivity {
    private val log = Logger.getInstance(AutoResumeStartup::class.java)

    override suspend fun execute(project: Project) {
        try {
            val cls = Class.forName("com.gjavadoc.queue.QueueManager") as Class<Any>
            val svc = project.getService(cls)
            if (svc != null) {
                val m = cls.getMethod("autoResumePending")
                m.invoke(svc)
            } else {
                log.warn("QueueManager service not available at startup")
            }
        } catch (t: Throwable) {
            // 静默降级，避免因类加载问题影响 IDE 启动
            log.warn("AutoResumeStartup failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }
}
