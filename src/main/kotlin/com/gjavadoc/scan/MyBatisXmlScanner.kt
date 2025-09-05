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

    fun scan(scope: GlobalSearchScope? = null): List<EntryPoint> {
        val searchScope = scope ?: GlobalSearchScope.projectScope(project)
        return FilenameIndex.getAllFilesByExt(project, "xml", searchScope)
            .filterNot { it.canonicalPath?.contains("build") == true }
            .mapNotNull { xmlFile ->
                try {
                    val content = String(xmlFile.contentsToByteArray())
                    parseXmlFile(xmlFile.path, content)
                } catch (e: Exception) {
                    println("Error reading file ${xmlFile.path}: ${e.message}")
                    null
                }
            }
            .flatten()
    }

    private fun parseXmlFile(filePath: String, content: String): List<EntryPoint> {
        val handler = MyBatisMapperHandler(filePath)
        val factory = SAXParserFactory.newInstance().apply {
            // Disable XXE
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
        }
        
        return try {
            val parser = factory.newSAXParser()
            parser.parse(InputSource(StringReader(content)), handler)
            handler.entryPoints
        } catch (_: Exception) {
            // Not all XML files are MyBatis mapper files, just skip if parsing fails
            emptyList()
        }
    }

    private class MyBatisMapperHandler(private val filePath: String) : DefaultHandler() {
        val entryPoints = mutableListOf<EntryPoint>()
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
                currentNamespace != null && qName in SQL_ELEMENT_NAMES -> {
                    val methodName = attributes.getValue("id")
                    if (!methodName.isNullOrEmpty()) {
                        currentMethodName = methodName
                        currentSqlStatement.clear()
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (currentNamespace != null && currentElementName in SQL_ELEMENT_NAMES) {
                currentSqlStatement.append(String(ch, start, length))
            }
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
        }
    }
}