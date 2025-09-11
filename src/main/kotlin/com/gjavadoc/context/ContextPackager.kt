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
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiTypesUtil
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

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
                
                // 🆕 如果是MyBatis XML映射，添加XML中发现的实体类
                if (entry.annotation == "MyBatisXml" && !entry.xmlFilePath.isNullOrEmpty()) {
                    val additionalEntities = collectMyBatisXmlEntities(entry.xmlFilePath!!)
                    additionalEntities.forEach { entityClassName ->
                        val entityClass = findPsiClassByName(entityClassName)
                        if (entityClass != null && !types.classes.contains(entityClass)) {
                            types.classes.add(entityClass)
                        }
                    }
                }
                
                // 🆕 如果是MyBatis相关的Mapper接口，分析接口方法的实体类型
                if (entry.annotation in setOf("MyBatisXml", "MyBatis-Plus BaseMapper") || 
                    entry.annotation.contains("mybatis", ignoreCase = true)) {
                    val mapperEntities = collectMyBatisMapperEntities(entry.classFqn)
                    mapperEntities.forEach { entityClass ->
                        if (!types.classes.contains(entityClass)) {
                            types.classes.add(entityClass)
                        }
                    }
                }
                
                // 🆕 如果有SQL语句，分析SQL参数中的实体类型
                if (!entry.sqlStatement.isNullOrEmpty() && entryMethod != null) {
                    val sqlParameterEntities = analyzeSqlParameterEntities(entry.sqlStatement!!, entryMethod)
                    sqlParameterEntities.forEach { entityClass ->
                        if (!types.classes.contains(entityClass)) {
                            types.classes.add(entityClass)
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
    
    /**
     * 🆕 根据类名查找PSI类（支持简单类名和全限定名）
     */
    private fun findPsiClassByName(className: String): com.intellij.psi.PsiClass? {
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        
        // 首先尝试全限定名查找
        facade.findClass(className, scope)?.let { return it }
        
        // 如果是简单类名，尝试通过名称索引查找
        if (!className.contains('.')) {
            val classes = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                .getClassesByName(className, scope)
            return classes.firstOrNull()
        }
        
        return null
    }
    
    /**
     * 🆕 从MyBatis XML文件中收集实体类信息
     * 重用MyBatisXmlScanner的解析逻辑，但只提取实体类信息
     */
    private fun collectMyBatisXmlEntities(xmlFilePath: String): Set<String> {
        return try {
            val xmlFile = LocalFileSystem.getInstance().findFileByPath(xmlFilePath) ?: return emptySet()
            val content = String(xmlFile.contentsToByteArray())
            
            // 创建临时的SAX处理器来提取实体信息
            val factory = SAXParserFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            
            val parser = factory.newSAXParser()
            val handler = EntityExtractorHandler()
            
            parser.parse(ByteArrayInputStream(content.toByteArray()), handler)
            handler.entities
        } catch (e: Exception) {
            println("Error extracting entities from XML: ${e.message}")
            emptySet()
        }
    }
    
    /**
     * 🆕 专门用于提取实体类的SAX处理器
     */
    private class EntityExtractorHandler : org.xml.sax.helpers.DefaultHandler() {
        val entities = mutableSetOf<String>()
        
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
            when (qName) {
                "resultMap" -> {
                    val type = attributes?.getValue("type")
                    if (!type.isNullOrEmpty()) {
                        entities.add(type)
                    }
                }
                "association" -> {
                    val javaType = attributes?.getValue("javaType")
                    if (!javaType.isNullOrEmpty()) {
                        entities.add(javaType)
                    }
                }
                "collection" -> {
                    val ofType = attributes?.getValue("ofType")
                    if (!ofType.isNullOrEmpty()) {
                        entities.add(ofType)
                    }
                }
                "select", "insert", "update", "delete" -> {
                    val resultType = attributes?.getValue("resultType")
                    if (!resultType.isNullOrEmpty()) {
                        entities.add(resultType)
                    }
                }
            }
        }
    }
    
    /**
     * 🆕 收集MyBatis Mapper接口中的实体类信息
     * 分析Mapper接口中所有方法的参数类型和返回值类型，提取其中的实体类
     */
    private fun collectMyBatisMapperEntities(mapperClassName: String): List<com.intellij.psi.PsiClass> {
        val mapperClass = findPsiClass(mapperClassName) ?: return emptyList()
        val entityClasses = mutableListOf<com.intellij.psi.PsiClass>()
        val typeCollector = TypeCollector(project)
        val cfg = SettingsState.getInstance(project).state.context
        
        // 分析Mapper接口中的所有方法
        mapperClass.methods.forEach { method ->
            // 使用TypeCollector来收集方法相关的类型，但只保留实体类
            val methodTypes = typeCollector.collectForMethod(method, maxDepth = 1)
            
            // 过滤出真正的实体类（而不是基础类型、工具类等）
            val methodEntities = methodTypes.classes.filter { cls ->
                isEntityClass(cls, cfg)
            }
            
            entityClasses.addAll(methodEntities)
        }
        
        return entityClasses.distinctBy { it.qualifiedName ?: it.name }
    }
    
    /**
     * 🆕 判断一个类是否为实体类
     * 基于配置的注解白名单、包名关键字和类名后缀进行判断
     */
    private fun isEntityClass(cls: com.intellij.psi.PsiClass, cfg: SettingsState.ContextConfig): Boolean {
        // 1. 检查实体注解（JPA Entity、MyBatis相关注解等）
        val annos = cls.modifierList?.annotations ?: emptyArray()
        for (a in annos) {
            val qn = a.qualifiedName ?: a.nameReferenceElement?.referenceName ?: ""
            if (cfg.annotationWhitelist.any { qn.endsWith(it) || qn == it }) return true
        }
        
        // 2. 检查包名关键字（.entity, .model, .domain, .po等）
        val pkg = (cls.containingFile as? PsiJavaFile)?.packageName ?: ""
        val entityPackageKeywords = cfg.packageKeywords + listOf(".entity", ".model", ".domain", ".po", ".pojo")
        if (entityPackageKeywords.any { pkg.contains(it, ignoreCase = true) }) return true
        
        // 3. 检查类名后缀（Entity, DO, PO等）
        val name = cls.name ?: ""
        val entitySuffixes = cfg.typeSuffixes + listOf("Entity", "DO", "PO", "POJO")
        if (entitySuffixes.any { name.endsWith(it, ignoreCase = true) }) return true
        
        // 4. 排除明显的非实体类
        val excludePatterns = listOf(
            "java.lang", "java.util", "java.time", "java.math",
            "org.springframework", "com.baomidou.mybatisplus.core",
            "Mapper", "Service", "Controller", "Config", "Utils"
        )
        val fullName = cls.qualifiedName ?: ""
        if (excludePatterns.any { fullName.startsWith(it) || name.contains(it) }) return false
        
        return false
    }
    
    /**
     * 🆕 分析SQL参数中的实体类型
     * 分析 #{user.name} 中的 user 对象类型，以及方法参数中的实体类型
     */
    private fun analyzeSqlParameterEntities(sql: String, method: PsiMethod): List<com.intellij.psi.PsiClass> {
        val entityClasses = mutableListOf<com.intellij.psi.PsiClass>()
        val cfg = SettingsState.getInstance(project).state.context
        
        try {
            // 1. 提取SQL中的参数引用模式 #{param.property} 和 ${param.property}
            val parameterPattern = """[#$]\{(\w+)(?:\.[\w.]*)?}""".toRegex()
            val parameterNames = parameterPattern.findAll(sql)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            
            // 2. 分析方法参数中的实体类型
            method.parameterList.parameters.forEach { param ->
                val paramType = param.type
                val paramName = param.name
                
                // 如果SQL中引用了这个参数名，且参数类型是自定义类，则可能是实体类
                if (parameterNames.contains(paramName) || isLikelyEntityParameter(param)) {
                    val paramClass = PsiTypesUtil.getPsiClass(paramType)
                    if (paramClass != null && isEntityClass(paramClass, cfg)) {
                        entityClasses.add(paramClass)
                    }
                }
                
                // 分析复杂类型（如List<Entity>、Map<String, Entity>等）
                if (paramType is PsiClassType) {
                    paramType.parameters.forEach { typeParam ->
                        val typeParamClass = PsiTypesUtil.getPsiClass(typeParam)
                        if (typeParamClass != null && isEntityClass(typeParamClass, cfg)) {
                            entityClasses.add(typeParamClass)
                        }
                    }
                }
            }
            
            // 3. 分析返回值类型中的实体类
            val returnType = method.returnType
            if (returnType is PsiClassType) {
                val returnClass = PsiTypesUtil.getPsiClass(returnType)
                if (returnClass != null && isEntityClass(returnClass, cfg)) {
                    entityClasses.add(returnClass)
                }
                
                // 分析泛型返回类型（如List<Entity>、Optional<Entity>等）
                returnType.parameters.forEach { typeParam ->
                    val typeParamClass = PsiTypesUtil.getPsiClass(typeParam)
                    if (typeParamClass != null && isEntityClass(typeParamClass, cfg)) {
                        entityClasses.add(typeParamClass)
                    }
                }
            }
            
        } catch (e: Exception) {
            println("Error analyzing SQL parameter entities: ${e.message}")
        }
        
        return entityClasses.distinctBy { it.qualifiedName ?: it.name }
    }
    
    /**
     * 🆕 判断方法参数是否可能是实体参数
     * 基于参数名称、类型等特征进行判断
     */
    private fun isLikelyEntityParameter(param: PsiParameter): Boolean {
        val paramName = param.name ?: ""
        val paramType = param.type
        
        // 1. 参数名称特征（常见实体参数名）
        val entityParamNames = listOf("entity", "model", "record", "data", "user", "order", "product")
        if (entityParamNames.any { paramName.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // 2. 非基础类型且非框架类型
        if (paramType is PsiClassType) {
            val typeName = paramType.resolve()?.qualifiedName ?: ""
            val isFrameworkType = listOf(
                "java.lang", "java.util", "java.time", "java.math",
                "org.springframework", "com.baomidou.mybatisplus"
            ).any { typeName.startsWith(it) }
            
            return !isFrameworkType && !(paramType.resolve()?.isInterface ?: false)
        }
        
        return false
    }
}
