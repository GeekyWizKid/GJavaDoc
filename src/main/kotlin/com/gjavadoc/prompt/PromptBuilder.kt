package com.gjavadoc.prompt

import com.gjavadoc.model.EntryPoint
import com.gjavadoc.settings.SettingsState
import com.gjavadoc.util.MethodCategory
import com.gjavadoc.util.classifyMethodName
import com.intellij.openapi.project.Project

object PromptBuilder {
    fun build(project: Project, entry: EntryPoint, context: String): String {
        val s = SettingsState.getInstance(project).state
        if (s.customPromptEnabled && s.customPrompt.isNotBlank()) {
            return renderCustomTemplate(project, s.customPrompt, entry, context)
        }
        return buildDefault(project, entry, context)
    }

    fun defaultTemplate(): String = buildString {
        appendLine("你现在是\"Java 接口文档生成器\"。")
        appendLine("请将 Service 的每个 public 方法视为对外接口，按以下规则输出接口文档。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("任务")
        appendLine()
        appendLine("阅读下面的 Java 代码片段，对每个 public 方法生成接口文档，严格输出以下三部分：")
        appendLine("\t1. 接口名称/说明/请求方式")
        appendLine("\t2. 输入参数表（含 qc.*、Map 展开、DTO 字段展开）")
        appendLine("\t3. 输出参数表（直接列字段，含包装类、VO、Map 展开）")
        appendLine()
        appendLine("如果代码中包含 SQL 语句和数据库实体类信息，请额外关注：")
        appendLine("\t• SQL 语句的参数映射关系（#{param} 对应的Java参数）")
        appendLine("\t• 数据库表结构与实体类字段的对应关系")
        appendLine("\t• MyBatis 结果映射中的关联查询和嵌套对象")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("解析规则")
        appendLine()
        appendLine("1. 接口基础信息")
        appendLine("\t• 接口名称：类名.方法名（可加“（推断：xx）”）。")
        appendLine("\t• 接口说明：若注释缺失，则基于方法名/领域词语义给一句话（标注“推断”）。")
        appendLine("\t• 请求方式：按方法名推断：")
        appendLine("\t\t• GET：find/get/query/select/list/page/detail/count")
        appendLine("\t\t• POST：create/add/save/submit/import/export")
        appendLine("\t\t• PUT：update/modify/enable/disable/audit/approve")
        appendLine("\t\t• DELETE：delete/remove/clear")
        appendLine("\t\t• 不确定 → POST（推断）")
        appendLine()
        appendLine("2. 输入参数表")
        appendLine("\t• 普通参数：形参直接列出。")
        appendLine("\t• DTO/VO：展开一层字段，格式：obj.field。")
        appendLine("\t• Map/JSONObject：展开键，推断类型；若键不固定，用 map.* 行表示动态键。")
        appendLine("\t• QueryCondition (qc)：必须展开字段，字段名前加 qc.。来源包含 qc.get(\"xxx\")/getOrDefault/containsKey、SQL、Mapper、判空分支。")
        appendLine("\t• MyBatis参数：如果有SQL语句，重点关注#{param}中的参数名，确保在输入参数表中列出。")
        appendLine("\t• 类型：按转换/命名规则推断（id→String、*Date→String(yyyy-MM-dd)、*Count→Integer）。")
        appendLine("\t• 是否必须：无判空直接使用/有校验注解/SQL 强依赖 → 是；有默认值或判空分支 → 否。")
        appendLine("\t• 默认值：在说明列出（例如 qc.pageNo 默认 1）。")
        appendLine()
        appendLine("3. 输出参数表")
        appendLine("\t• 返回包装类：如 Result<T>，展开公共字段（code/message/success/timestamp）+ T。")
        appendLine("\t• 分页结构：列出 total/pageNo/pageSize/records[]。")
        appendLine("\t• VO/DTO：展开一层主要字段。")
        appendLine("\t• 实体类字段：如果返回的是数据库实体类，重点展开其字段并说明业务含义。")
        appendLine("\t• Map/JSONObject：展开键，推断类型与含义。")
        appendLine("\t• 关联对象：如果SQL中有JOIN查询，注意返回结果中的嵌套对象结构。")
        appendLine("\t• 说明：写明字段的主要业务含义，不要只写\"见 XXX\"。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("输出格式（每个方法独立，严格使用 Markdown 表格，不要使用代码块围栏）")
        appendLine("\t• 接口名称：类名.方法名（推断）")
        appendLine("\t• 接口说明：一句话描述（推断可标）")
        appendLine("\t• 请求方式：GET/POST/PUT/DELETE（推断）")
        appendLine()
        appendLine("输入参数：")
        appendLine()
        appendLine("|参数名|类型|是否必须|说明|")
        appendLine("|---|---|---|---|")
        appendLine("|xxx|String|是|主键ID|")
        appendLine("|qc.pageNo|Integer|否|页码，默认 1|")
        appendLine("|qc.pageSize|Integer|否|每页大小，默认 20|")
        appendLine("|map.id|String|否|Map 键，示例：患者ID|")
        appendLine("|obj.field|String|否|DTO 字段，示例：患者姓名|")
        appendLine()
        appendLine("输出参数：")
        appendLine()
        appendLine("|参数名|类型|是否必须|说明|")
        appendLine("|---|---|---|---|")
        appendLine("|code|int|是|状态码|")
        appendLine("|message|String|是|提示信息|")
        appendLine("|total|Long|是|总记录数|")
        appendLine("|records[]|List|是|结果列表|")
        appendLine("|records[].id|String|是|记录ID|")
        appendLine("|records[].name|String|否|姓名|")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("Java 代码粘贴区")
        appendLine()
        appendLine("```java")
        appendLine("${'$'}{CONTEXT}")
        appendLine("```")
        appendLine()
        appendLine("注意：")
        appendLine("- 严格按上面的“输出格式”输出 Markdown，表格必须使用 | 分隔的 Markdown 表格，不要输出 JSON，不要使用围栏包裹结果。")
        appendLine("- 只根据粘贴区内的代码做推断，不要编造。")
        appendLine("- 若某字段无法确定类型或是否必须，请给出合理推断并标注“推断”。")
    }

    private fun buildDefault(project: Project, entry: EntryPoint, context: String): String {
        // Keep previous default behavior
        val methodBase = entry.method.substringBefore('(')
        val cat = classifyMethodName(methodBase, SettingsState.getInstance(project).state.crudPatterns)
        val http = when (cat) {
            MethodCategory.CREATE -> "POST"
            MethodCategory.READ -> "GET"
            MethodCategory.UPDATE -> "PUT"
            MethodCategory.DELETE -> "DELETE"
            MethodCategory.OTHER -> "POST"
        }
        return defaultTemplate().replace("${'$'}{CONTEXT}", context)
    }

    private fun renderCustomTemplate(project: Project, tpl: String, entry: EntryPoint, context: String): String {
        val methodBase = entry.method.substringBefore('(')
        val http = when (classifyMethodName(methodBase, SettingsState.getInstance(project).state.crudPatterns)) {
            MethodCategory.CREATE -> "POST"
            MethodCategory.READ -> "GET"
            MethodCategory.UPDATE -> "PUT"
            MethodCategory.DELETE -> "DELETE"
            MethodCategory.OTHER -> "POST"
        }
        return tpl
            .replace("${'$'}{ENTRY_CLASS_FQN}", entry.classFqn)
            .replace("${'$'}{ENTRY_METHOD}", entry.method)
            .replace("${'$'}{ENTRY_METHOD_BASE}", methodBase)
            .replace("${'$'}{HTTP_METHOD}", http)
            .replace("${'$'}{CONTEXT}", context)
    }
}
