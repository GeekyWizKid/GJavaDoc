package com.gjavadoc.model

data class EntryPoint(
    val classFqn: String,
    val method: String,
    val file: String,
    val line: Int,
    val annotation: String,
    val sqlStatement: String? = null,
    val xmlFilePath: String? = null,
)

enum class TaskStatus { QUEUED, RUNNING, CANCELLED, FAILED, PARTIAL, SUCCEEDED }

enum class TaskScope { METHOD, CLASS }

data class TaskProgress(
    val fraction: Double,
    val message: String? = null,
)

data class TaskResult(
    val jsonPath: String? = null,
    val mdPath: String? = null,
    val ctxPath: String? = null,
)

data class TaskError(
    val type: String,
    val message: String,
)

data class TaskModel(
    val taskId: String,
    val entry: EntryPoint,
    val createdAt: Long = System.currentTimeMillis(),
    var status: TaskStatus = TaskStatus.QUEUED,
    var progress: TaskProgress = TaskProgress(0.0),
    var scope: TaskScope = TaskScope.METHOD,
    var cgSummary: String? = null,
    var sliceAnchors: List<Pair<String, IntRange>>? = null,
    var result: TaskResult? = null,
    var error: TaskError? = null,
    var attempt: Int = 0,
)
