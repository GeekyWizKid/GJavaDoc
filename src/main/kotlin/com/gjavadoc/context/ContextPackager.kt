package com.gjavadoc.context

import com.gjavadoc.analysis.CGSliceResult
import com.gjavadoc.model.EntryPoint
import com.gjavadoc.io.OutputWriter
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiTypesUtil

data class ContextBundle(
    val text: String,
    val path: String,
)

class ContextPackager(private val project: Project) {
    fun build(entry: EntryPoint, analysis: CGSliceResult, outPath: String): ContextBundle {
        val ctx = ReadAction.compute<String, RuntimeException> {
            val cfg = SettingsState.getInstance(project).state.context
            val sb = StringBuilder()
            sb.appendLine("# Entry Method")
            sb.appendLine("${entry.classFqn}#${entry.method}")
            // Include SQL statement if available
            if (!entry.sqlStatement.isNullOrEmpty()) {
                sb.appendLine()
                sb.appendLine("# SQL Statement")
                sb.appendLine("```sql")
                sb.appendLine(entry.sqlStatement)
                sb.appendLine("```")
                if (!entry.xmlFilePath.isNullOrEmpty()) {
                    sb.appendLine("// Origin: ${entry.xmlFilePath}")
                }
            }
            sb.appendLine()
            // Include method source if we can locate it
            tryIncludeMethodSource(sb, entry)
            sb.appendLine()
            sb.appendLine("# Callgraph Summary")
            sb.appendLine(analysis.summary)
            sb.appendLine()
            sb.appendLine("# Slices")
            val psiManager = PsiManager.getInstance(project)
            val docManager = PsiDocumentManager.getInstance(project)
            val seen = HashSet<String>()
            for ((file, start, end) in analysis.anchors.map { Triple(it.file, it.startLine, it.endLine) }) {
                val vf = LocalFileSystem.getInstance().findFileByPath(file) ?: continue
                val psi = psiManager.findFile(vf) ?: continue
                val doc = docManager.getDocument(psi)
                val content = if (doc != null) doc.text else String(vf.contentsToByteArray())
                val lines = content.lines()
                val s = (start - 1).coerceAtLeast(0)
                val e = (end - 1).coerceAtMost(lines.lastIndex)
                val key = "$file:$s-$e"
                if (!seen.add(key)) continue
                sb.appendLine("## File: $file [$start-$end]")
                for (i in s..e) {
                    val ln = i + 1
                    sb.append(String.format("%6d | ", ln))
                    sb.appendLine(lines[i])
                }
                sb.appendLine()
            }
            // Related DTO/VO/Entity/Enum types
            val entryMethod = findEntryMethod(entry)
            if (entryMethod != null) {
                val types = TypeCollector(project).collectForMethod(entryMethod, maxDepth = cfg.typeDepth)
                // If it's a MyBatis-Plus BaseMapper, try to get the entity type
                if (entry.annotation == "MyBatis-Plus BaseMapper") {
                    findPsiClass(entry.classFqn)?.let { mapperClass ->
                        val baseMapperRef = mapperClass.implementsList?.referencedTypes?.firstOrNull {
                            it.resolve()?.qualifiedName == "com.baomidou.mybatisplus.core.mapper.BaseMapper"
                        }
                        baseMapperRef?.parameters?.firstOrNull()?.let {
                            val entityClass = PsiTypesUtil.getPsiClass(it)
                            if (entityClass != null && !types.classes.contains(entityClass)) {
                                types.classes.add(entityClass)
                            }
                        }
                    }
                }
                if (types.classes.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("# Related Types (DTO/VO/Entity/Enum)")
                    for (cls in types.classes) {
                        val file = cls.containingFile?.virtualFile
                        val doc = file?.let { PsiDocumentManager.getInstance(project).getDocument(PsiManager.getInstance(project).findFile(it)!!) }
                        sb.appendLine("## ${cls.qualifiedName ?: cls.name}")
                        if (file != null && doc != null) {
                            val r = cls.textRange
                            val start = doc.getLineNumber(r.startOffset)
                            val end = doc.getLineNumber(r.endOffset)
                            val lines = doc.text.lines()
                            sb.appendLine("// File: ${file.path} [${start+1}-${end+1}]")
                            for (i in start..end) {
                                val ln = i + 1
                                sb.append(String.format("%6d | ", ln))
                                sb.appendLine(lines.getOrNull(i) ?: "")
                            }
                        } else {
                            // Fallback to PSI text
                            sb.appendLine(cls.text)
                        }
                        sb.appendLine()
                    }
                }
            }

            // Called methods (configurable)
            if (entryMethod != null && cfg.collectCalled && cfg.calledDepth > 0) {
                val called = CalledMethodsCollector(project).collect(entryMethod, maxDepth = cfg.calledDepth)
                if (called.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("# Called Methods / 被调方法")
                    for (m in called) {
                        val file = m.containingFile?.virtualFile ?: continue
                        val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: continue
                        val r = m.textRange
                        val start = doc.getLineNumber(r.startOffset)
                        val end = doc.getLineNumber(r.endOffset)
                        val lines = doc.text.lines()
                        sb.appendLine("## ${m.containingClass?.qualifiedName ?: ""}#${m.name}")
                        sb.appendLine("// File: ${file.path} [${start+1}-${end+1}]")
                        for (i in start..end) {
                            val ln = i + 1
                            sb.append(String.format("%6d | ", ln))
                            sb.appendLine(lines.getOrNull(i) ?: "")
                        }
                        if (sb.length >= cfg.maxChars) break
                    }
                }
            }

            // Enforce context size limit
            if (sb.length > cfg.maxChars) {
                sb.setLength(cfg.maxChars)
                sb.appendLine()
                sb.appendLine("... [truncated]")
            }

            sb.toString()
        }
        val writer = OutputWriter(project)
        val abs = writer.writeRelative(outPath, ctx)
        return ContextBundle(text = ctx, path = abs)
    }

    fun buildForClass(entry: EntryPoint, analysis: CGSliceResult, outPath: String): ContextBundle {
        val ctx = ReadAction.compute<String, RuntimeException> {
            val cfg = com.gjavadoc.settings.SettingsState.getInstance(project).state.context
            val sb = StringBuilder()
            sb.appendLine("# Entry Class")
            sb.appendLine(entry.classFqn)
            sb.appendLine()
            val cls = findPsiClass(entry.classFqn)
            if (cls != null) {
                val file = cls.containingFile?.virtualFile
                val doc = file?.let { PsiDocumentManager.getInstance(project).getDocument(PsiManager.getInstance(project).findFile(it)!!) }
                if (file != null && doc != null) {
                    val r = cls.textRange
                    val start = doc.getLineNumber(r.startOffset)
                    val end = doc.getLineNumber(r.endOffset)
                    val lines = doc.text.lines()
                    sb.appendLine("# Class Source")
                    sb.appendLine("// File: ${file.path} [${start+1}-${end+1}]")
                    for (i in start..end) {
                        val ln = i + 1
                        sb.append(String.format("%6d | ", ln))
                        sb.appendLine(lines.getOrNull(i) ?: "")
                        if (sb.length >= cfg.maxChars) break
                    }
                    sb.appendLine()
                    sb.appendLine("# Public Methods")
                    for (m in cls.methods.filter { it.hasModifierProperty(PsiModifier.PUBLIC) }) {
                        sb.appendLine("- ${m.name}(${m.parameterList.parameters.joinToString(",") { it.type.presentableText }})")
                    }
                }

                // Related types from all public methods
                val collector = TypeCollector(project)
                val types = cls.methods.filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
                    .flatMap { collector.collectForMethod(it, maxDepth = cfg.typeDepth).classes }
                    .distinctBy { it.qualifiedName ?: it.name }
                if (types.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("# Related Types (DTO/VO/Entity/Enum)")
                    for (c in types) {
                        val f = c.containingFile?.virtualFile
                        val d = f?.let { PsiDocumentManager.getInstance(project).getDocument(PsiManager.getInstance(project).findFile(it)!!) }
                        sb.appendLine("## ${c.qualifiedName ?: c.name}")
                        if (f != null && d != null) {
                            val rr = c.textRange
                            val s = d.getLineNumber(rr.startOffset)
                            val e = d.getLineNumber(rr.endOffset)
                            val ls = d.text.lines()
                            sb.appendLine("// File: ${f.path} [${s+1}-${e+1}]")
                            for (i in s..e) {
                                val ln = i + 1
                                sb.append(String.format("%6d | ", ln))
                                sb.appendLine(ls.getOrNull(i) ?: "")
                                if (sb.length >= cfg.maxChars) break
                            }
                        } else {
                            sb.appendLine(c.text)
                        }
                        if (sb.length >= cfg.maxChars) break
                    }
                }
            }

            if (sb.length > cfg.maxChars) {
                sb.setLength(cfg.maxChars)
                sb.appendLine()
                sb.appendLine("... [truncated]")
            }
            sb.toString()
        }
        val writer = OutputWriter(project)
        val abs = writer.writeRelative(outPath, ctx)
        return ContextBundle(text = ctx, path = abs)
    }

    private fun tryIncludeMethodSource(sb: StringBuilder, entry: EntryPoint) {
        val methodPsi = findEntryMethod(entry) ?: return
        val psiFile = methodPsi.containingFile
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        if (methodPsi != null) {
            sb.appendLine("# Method Source")
            val r = methodPsi.textRange
            val content = doc.text
            val lines = content.lines()
            val start = doc.getLineNumber(r.startOffset)
            val end = doc.getLineNumber(r.endOffset)
            for (i in start..end) {
                val ln = i + 1
                sb.append(String.format("%6d | ", ln))
                sb.appendLine(lines.getOrNull(i) ?: "")
            }
        }
    }

    private fun findEntryMethod(entry: EntryPoint): PsiMethod? {
        val vf = LocalFileSystem.getInstance().findFileByPath(entry.file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineIndex = (entry.line - 1).coerceAtLeast(0)
        val classes = psiFile.children.filterIsInstance<com.intellij.psi.PsiClass>()
        val targetName = entry.method.substringBefore('(')
        for (cls in classes) {
            val m = cls.methods.firstOrNull { m ->
                val r = m.textRange
                val startLine = doc.getLineNumber(r.startOffset)
                val endLine = doc.getLineNumber(r.endOffset)
                lineIndex in startLine..endLine || m.name == targetName
            }
            if (m != null) return m
        }
        return null
    }

    private fun findPsiClass(fqn: String): com.intellij.psi.PsiClass? {
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        return com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqn, scope)
    }
}
