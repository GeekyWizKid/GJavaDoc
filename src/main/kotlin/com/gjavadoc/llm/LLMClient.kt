package com.gjavadoc.llm

import com.gjavadoc.analysis.CGSliceResult
import com.gjavadoc.model.EntryPoint
import com.gjavadoc.settings.SettingsState
import com.gjavadoc.prompt.PromptBuilder
import com.gjavadoc.util.MethodCategory
import com.gjavadoc.util.classifyMethodName
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

data class LLMDoc(
    val json: String,
    val markdown: String,
)

interface LLMClient {
    fun generate(entry: EntryPoint, analysis: CGSliceResult, contextText: String): LLMDoc
}

class StubLLMClient(private val project: Project) : LLMClient {
    override fun generate(entry: EntryPoint, analysis: CGSliceResult, contextText: String): LLMDoc {
        val settings = SettingsState.getInstance(project).state
        val prompt = PromptBuilder.build(project, entry, contextText)
        val md = buildString {
            appendLine("接口名称：${entry.classFqn}.${entry.method.substringBefore('(')}（推断）")
            appendLine("接口说明：示例占位，由 Stub 生成（推断）")
            val http = when (classifyMethodName(entry.method.substringBefore('('), SettingsState.getInstance(project).state.crudPatterns)) {
                MethodCategory.CREATE -> "POST"
                MethodCategory.READ -> "GET"
                MethodCategory.UPDATE -> "PUT"
                MethodCategory.DELETE -> "DELETE"
                MethodCategory.OTHER -> "POST"
            }
            appendLine("请求方式：${http}（推断）")
            appendLine()
            appendLine("输入参数：")
            appendLine()
            appendLine("|参数名|类型|是否必须|说明|")
            appendLine("|---|---|---|---|")
            appendLine("|param|Object|否|示例占位|")
            appendLine()
            appendLine("输出参数：")
            appendLine()
            appendLine("|参数名|类型|是否必须|说明|")
            appendLine("|---|---|---|---|")
            appendLine("|code|int|是|状态码|")
            appendLine("|message|String|是|提示信息|")
        }
        // Stub: return only Markdown; keep JSON empty
        return LLMDoc(json = "", markdown = md)
    }
}

class HttpLLMClient(private val project: Project) : LLMClient {
    override fun generate(entry: EntryPoint, analysis: CGSliceResult, contextText: String): LLMDoc {
        val s = SettingsState.getInstance(project).state
        val prompt = PromptBuilder.build(project, entry, contextText)

        val isOllama = (s.llmProvider == "OLLAMA") || s.llmEndpoint.contains("/api/chat") || s.llmEndpoint.contains(":11434")
        val isDeepSeek = (s.llmProvider == "DEEPSEEK") || s.llmEndpoint.contains("api.deepseek.com")
        val body = if (isOllama) {
            """
                {
                  "model": "${s.model}",
                  "messages": [
                    {"role":"user","content": ${jsonEscape(prompt)}}
                  ],
                  "stream": false
                }
            """.trimIndent()
        } else if (isDeepSeek) {
            // DeepSeek-specific body format with their model names
            val deepSeekModel = when {
                s.model.startsWith("deepseek") -> s.model
                s.model.contains("chat") || s.model.contains("reasoner") -> s.model
                else -> "deepseek-chat" // Default to deepseek-chat
            }
            """
                {
                  "model": "$deepSeekModel",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant for Java API documentation. Answer in Chinese Markdown only."},
                    {"role":"user","content": ${jsonEscape(prompt)}}
                  ],
                  "stream": false,
                  "max_tokens": ${s.openaiMaxTokens},
                  "temperature": ${formatNum(s.openaiTemperature)},
                  "top_p": ${formatNum(s.openaiTopP)}
                }
            """.trimIndent()
        } else {
            // OpenAI-compatible chat.completions body; include max_tokens/temperature/top_p for stricter backends (e.g., vLLM)
            """
                {
                  "model": "${s.model}",
                  "messages": [
                    {"role":"system","content":"You are a helpful assistant for Java API documentation. Answer in Chinese Markdown only."},
                    {"role":"user","content": ${jsonEscape(prompt)}}
                  ],
                  "stream": false,
                  "max_tokens": ${s.openaiMaxTokens},
                  "temperature": ${formatNum(s.openaiTemperature)},
                  "top_p": ${formatNum(s.openaiTopP)}
                }
            """.trimIndent()
        }

        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(s.requestTimeoutSec.toLong()))
            .build()
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(s.llmEndpoint))
            .timeout(Duration.ofSeconds(s.requestTimeoutSec.toLong()))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val token = s.authToken?.trim()
        if (!token.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $token")
        val req = reqBuilder.build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            val snippet = resp.body().take(240)
            throw RuntimeException("HTTP ${resp.statusCode()} — ${snippet}")
        }
        val content = stripThinkTags(extractContent(resp.body()))
        // The prompt requests Markdown only, so treat whole content as Markdown
        val md = content.trim()
        return LLMDoc(json = "", markdown = md)
    }

    private fun jsonEscape(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun extractContent(body: String): String {
        // Find first "content":"..." and decode JSON string escapes including \uXXXX
        val idx = body.indexOf("\"content\"")
        if (idx < 0) return cleanupHumanReadable(body)
        val start = body.indexOf('"', idx + 9)
        if (start < 0) return cleanupHumanReadable(body)
        var i = start + 1
        var escaped = false
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            if (escaped) {
                when (c) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        // Decode \uXXXX
                        if (i + 4 < body.length) {
                            val hex = body.substring(i + 1, i + 5)
                            val ch = hex.toIntOrNull(16)?.toChar()
                            if (ch != null) {
                                sb.append(ch)
                                i += 4
                            } else sb.append("\\u").append(hex)
                        } else sb.append('u')
                    }
                    else -> sb.append(c)
                }
                escaped = false
            } else {
                if (c == '\\') escaped = true
                else if (c == '"') break
                else sb.append(c)
            }
            i++
        }
        return cleanupHumanReadable(sb.toString())
    }

    private fun formatNum(v: Double): String {
        // Ensure dot as decimal separator regardless of system locale
        val s = String.format(Locale.US, "%.4f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun cleanupHumanReadable(text: String): String {
        // Minimal cleanups: common HTML entities and internal-class '$' to '.' inside type-like tokens
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
    }

    private fun stripThinkTags(text: String): String {
        // Remove reasoning blocks like <think> ... </think> (case-insensitive, dotall, non-greedy)
        val re = Regex("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>")
        return text.replace(re, "").trim()
    }

    private fun splitJsonAndMarkdown(content: String): Pair<String, String> {
        val sep = content.indexOf("\n---")
        return if (sep >= 0) {
            val json = content.substring(0, sep).trim()
            val md = content.substring(sep + 4).trim()
            json to md
        } else {
            // try to extract first {...}
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start >= 0 && end > start) content.substring(start, end + 1) to content
            else content to content
        }
    }
}

object LLMClientFactory {
    fun create(project: Project): LLMClient {
        val s = SettingsState.getInstance(project).state
        return if (s.useHttpClient) HttpLLMClient(project) else StubLLMClient(project)
    }
}
