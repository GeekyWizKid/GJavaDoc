package com.gjavadoc.scan

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

class MyBatisXmlScanner(private val project: Project) {
    // 缓存已检查过的文件，避免重复解析
    private val fileCache = mutableMapOf<String, List<EntryPoint>>()
    private val lastModifiedCache = mutableMapOf<String, Long>()

    /**
     * 扫描MyBatis XML映射文件
     * @param scope 搜索范围
     * @param serviceClasses 服务类列表，只扫描与这些类相关的映射文件。如果为null则扫描所有
     */
    fun scan(scope: GlobalSearchScope? = null, serviceClasses: Set<String>? = null): List<EntryPoint> {
        val searchScope = scope ?: GlobalSearchScope.projectScope(project)
        return FilenameIndex.getAllFilesByExt(project, "xml", searchScope)
            .filter { isValidXmlFile(it) }
            .mapNotNull { xmlFile ->
                try {
                    val filePath = xmlFile.path
                    val lastModified = xmlFile.timeStamp
                    
                    // 检查缓存
                    val cachedTime = lastModifiedCache[filePath]
                    if (cachedTime != null && cachedTime == lastModified) {
                        val cachedResult = fileCache[filePath]
                        // 如果指定了服务类过滤，需要重新过滤缓存结果
                        return@mapNotNull if (serviceClasses != null) {
                            cachedResult?.filter { entry -> isServiceRelated(entry.classFqn, serviceClasses) }
                        } else {
                            cachedResult
                        }
                    }
                    
                    val content = String(xmlFile.contentsToByteArray())
                    if (isMyBatisMapperFile(content)) {
                        val allResults = parseXmlFile(filePath, content)
                        // 更新缓存（缓存完整结果）
                        fileCache[filePath] = allResults
                        lastModifiedCache[filePath] = lastModified
                        
                        // 根据服务类过滤结果
                        if (serviceClasses != null) {
                            allResults.filter { entry -> isServiceRelated(entry.classFqn, serviceClasses) }
                        } else {
                            allResults
                        }
                    } else {
                        // 缓存空结果，避免重复检查
                        fileCache[filePath] = emptyList()
                        lastModifiedCache[filePath] = lastModified
                        null
                    }
                } catch (e: Exception) {
                    println("Error reading file ${xmlFile.path}: ${e.message}")
                    null
                }
            }
            .flatten()
    }

    private fun isValidXmlFile(xmlFile: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val path = xmlFile.canonicalPath ?: return false
        // 排除构建目录、测试资源、临时文件等
        val excludePatterns = listOf(
            "/build/", "/target/", "/.gradle/", "/.idea/",
            "/test/", "/tests/", "/node_modules/",
            "/.git/", "/tmp/", "/temp/"
        )
        return excludePatterns.none { path.contains(it, ignoreCase = true) }
    }

    internal fun isMyBatisMapperFile(content: String): Boolean {
        // 快速检查MyBatis Mapper文件特征
        return (content.contains("<!DOCTYPE mapper") || 
                (content.contains("<mapper") && content.contains("namespace="))) &&
               !content.contains("spring-beans", ignoreCase = true) // 排除Spring配置文件
    }

    private fun parseXmlFile(filePath: String, content: String): List<EntryPoint> {
        val handler = MyBatisMapperHandler(filePath)
        val factory = SAXParserFactory.newInstance().apply {
            // Disable XXE attacks
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
        }
        
        return try {
            val parser = factory.newSAXParser()
            parser.parse(InputSource(StringReader(content)), handler)
            handler.entryPoints
        } catch (e: org.xml.sax.SAXParseException) {
            // Log specific parsing errors for debugging
            if (isDebugEnabled()) {
                println("SAX parsing failed at line ${e.lineNumber}, column ${e.columnNumber} in $filePath: ${e.message}")
            }
            emptyList()
        } catch (e: java.io.IOException) {
            println("IO error reading $filePath: ${e.message}")
            emptyList()
        } catch (e: javax.xml.parsers.ParserConfigurationException) {
            println("Parser configuration error for $filePath: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            // Catch-all for unexpected errors
            println("Unexpected error parsing $filePath: ${e.javaClass.simpleName} - ${e.message}")
            emptyList()
        }
    }

    private fun isDebugEnabled(): Boolean {
        // Check if debug logging is enabled (can be configured via settings)
        return java.lang.System.getProperty("gjavadoc.debug", "false").toBoolean()
    }

    /**
     * 清理缓存以释放内存
     */
    fun clearCache() {
        fileCache.clear()
        lastModifiedCache.clear()
    }

    /**
     * 获取缓存统计信息
     */
    /**
     * 判断MyBatis映射的类是否与服务类相关
     * 匹配策略：
     * 1. 精确匹配：mapper类就是服务类
     * 2. 包级关联：mapper类与服务类在相同包或子包中
     * 3. 命名关联：mapper类名包含服务类的简名（如UserService -> UserMapper）
     */
    private fun isServiceRelated(mapperClass: String, serviceClasses: Set<String>): Boolean {
        // 精确匹配
        if (serviceClasses.contains(mapperClass)) {
            return true
        }
        
        // 包级和命名关联检查
        return serviceClasses.any { serviceClass ->
            val serviceSimpleName = serviceClass.substringAfterLast('.')
            val servicePackage = serviceClass.substringBeforeLast('.', "")
            val mapperSimpleName = mapperClass.substringAfterLast('.')
            val mapperPackage = mapperClass.substringBeforeLast('.', "")
            
            // 包级关联：同包或都在相同的顶级包下
            val packageRelated = if (servicePackage.isNotEmpty() && mapperPackage.isNotEmpty()) {
                val serviceRootPkg = servicePackage.split(".").take(2).joinToString(".")
                val mapperRootPkg = mapperPackage.split(".").take(2).joinToString(".")
                serviceRootPkg == mapperRootPkg || 
                mapperPackage.startsWith(servicePackage) || 
                servicePackage.startsWith(mapperPackage)
            } else {
                servicePackage == mapperPackage
            }
            
            // 命名关联：mapper名包含service名的核心部分
            val serviceBaseName = serviceSimpleName.removeSuffix("Service").removeSuffix("Controller")
            val mapperBaseName = mapperSimpleName.removeSuffix("Mapper").removeSuffix("DAO")
            val nameRelated = mapperBaseName.contains(serviceBaseName, ignoreCase = true) ||
                             serviceBaseName.contains(mapperBaseName, ignoreCase = true)
            
            packageRelated && nameRelated
        }
    }
    
    fun getCacheStats(): Pair<Int, Int> = fileCache.size to lastModifiedCache.size

    private class MyBatisMapperHandler(private val filePath: String) : DefaultHandler() {
        val entryPoints = mutableListOf<EntryPoint>()
        val relatedEntities = mutableSetOf<String>()  // 🆕 收集相关实体类
        private var currentNamespace: String? = null
        private var locator: Locator? = null
        private var currentElementName: String? = null
        private var currentSqlStatement = StringBuilder()
        private var currentMethodName: String? = null
        private var currentElementStartLine: Int = 1

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            currentElementName = qName
            currentElementStartLine = locator?.lineNumber ?: 1
            
            when {
                qName == "mapper" -> {
                    currentNamespace = attributes.getValue("namespace")
                }
                // 🆕 ResultMap实体关系分析
                qName == "resultMap" -> {
                    val type = attributes.getValue("type")
                    if (!type.isNullOrEmpty()) {
                        relatedEntities.add(type)
                    }
                }
                qName == "association" -> {
                    val javaType = attributes.getValue("javaType")
                    if (!javaType.isNullOrEmpty()) {
                        relatedEntities.add(javaType)
                    }
                }
                qName == "collection" -> {
                    val ofType = attributes.getValue("ofType")
                    if (!ofType.isNullOrEmpty()) {
                        relatedEntities.add(ofType)
                    }
                }
                // 🆕 SQL元素处理（包含实体分析）
                currentNamespace != null && qName in SQL_ELEMENT_NAMES -> {
                    val methodName = attributes.getValue("id")
                    if (!methodName.isNullOrEmpty()) {
                        currentMethodName = methodName
                        currentSqlStatement.clear()
                    }
                    
                    // 分析resultType/resultMap中的实体类
                    val resultType = attributes.getValue("resultType")
                    val resultMap = attributes.getValue("resultMap")
                    if (!resultType.isNullOrEmpty()) {
                        relatedEntities.add(resultType)
                    }
                    // resultMap引用暂时存储，后续可以通过映射关联到具体类型
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (currentNamespace != null && shouldCollectText()) {
                val text = String(ch, start, length).trim()
                if (text.isNotEmpty()) {
                    // 为动态SQL标签添加适当的空格分隔
                    if (currentSqlStatement.isNotEmpty() && !currentSqlStatement.endsWith(" ")) {
                        currentSqlStatement.append(" ")
                    }
                    currentSqlStatement.append(text)
                }
            }
        }

        private fun shouldCollectText(): Boolean {
            return currentElementName in SQL_ELEMENT_NAMES || 
                   currentElementName in DYNAMIC_SQL_TAGS ||
                   currentElementName in SQL_FRAGMENT_TAGS
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
            if (currentNamespace != null && qName in SQL_ELEMENT_NAMES) {
                val methodName = currentMethodName
                val sqlStatement = currentSqlStatement.toString().trim()
                
                if (!methodName.isNullOrEmpty() && sqlStatement.isNotEmpty()) {
                    addEntryPoint(methodName, sqlStatement, currentElementStartLine)
                }
                
                currentMethodName = null
                currentSqlStatement.clear()
            }
            
            currentElementName = null
        }

        private fun addEntryPoint(methodName: String, sqlStatement: String, line: Int) {
            currentNamespace?.takeIf { it.isNotEmpty() }?.let { namespace ->
                entryPoints.add(
                    EntryPoint(
                        classFqn = namespace,
                        method = methodName,
                        file = filePath,
                        line = line,
                        annotation = "MyBatisXml",
                        sqlStatement = sqlStatement,
                        xmlFilePath = filePath
                    )
                )
            }
        }

        companion object {
            private val SQL_ELEMENT_NAMES = setOf("select", "insert", "update", "delete")
            private val DYNAMIC_SQL_TAGS = setOf("if", "choose", "when", "otherwise", "foreach", "where", "set", "trim", "bind")
            private val SQL_FRAGMENT_TAGS = setOf("sql", "include")
        }
    }
}