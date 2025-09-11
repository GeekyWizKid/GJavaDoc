package com.gjavadoc.scan

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.project.Project

/**
 * MyBatis扫描功能的测试工具类
 */
object MyBatisTestUtils {
    
    /**
     * 测试XML文件是否被正确识别为MyBatis Mapper
     */
    fun testMapperDetection(content: String): Boolean {
        // 直接测试MyBatis特征检测逻辑
        return (content.contains("<!DOCTYPE mapper") || 
                (content.contains("<mapper") && content.contains("namespace="))) &&
               !content.contains("spring-beans", ignoreCase = true)
    }
    
    /**
     * 验证扫描结果的完整性
     */
    fun validateScanResults(results: List<EntryPoint>): ScanValidationResult {
        val issues = mutableListOf<String>()
        
        results.forEach { entry ->
            // 检查必要字段
            if (entry.classFqn.isBlank()) {
                issues.add("Entry has blank classFqn: $entry")
            }
            if (entry.method.isBlank()) {
                issues.add("Entry has blank method: $entry")
            }
            if (entry.file.isBlank()) {
                issues.add("Entry has blank file: $entry")
            }
            if (entry.line <= 0) {
                issues.add("Entry has invalid line number: $entry")
            }
            
            // 检查MyBatis特定字段
            if (entry.annotation == "MyBatisXml" && entry.sqlStatement.isNullOrBlank()) {
                issues.add("MyBatis XML entry missing SQL statement: $entry")
            }
        }
        
        // 统计信息
        val stats = ScanStats(
            totalEntries = results.size,
            xmlEntries = results.count { it.annotation == "MyBatisXml" },
            javaAnnotationEntries = results.count { it.annotation != "MyBatisXml" && it.annotation != "MyBatis-Plus BaseMapper" },
            baseMapperEntries = results.count { it.annotation == "MyBatis-Plus BaseMapper" },
            entriesWithSql = results.count { !it.sqlStatement.isNullOrEmpty() }
        )
        
        return ScanValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            stats = stats
        )
    }
    
    /**
     * 创建测试用的MyBatis Mapper XML内容
     */
    fun createTestMapperXml(namespace: String, methods: List<Pair<String, String>>): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">""")
            appendLine("""<mapper namespace="$namespace">""")
            
            methods.forEach { (methodName, sqlType) ->
                appendLine("""    <$sqlType id="$methodName">""")
                when (sqlType) {
                    "select" -> appendLine("""        SELECT * FROM users WHERE id = #{id}""")
                    "insert" -> appendLine("""        INSERT INTO users (name, email) VALUES (#{name}, #{email})""")
                    "update" -> appendLine("""        UPDATE users SET name = #{name} WHERE id = #{id}""")
                    "delete" -> appendLine("""        DELETE FROM users WHERE id = #{id}""")
                }
                appendLine("""    </$sqlType>""")
            }
            
            appendLine("""</mapper>""")
        }
    }
    
    /**
     * 打印扫描统计信息
     */
    fun printScanStats(results: List<EntryPoint>) {
        val validation = validateScanResults(results)
        
        println("=== MyBatis扫描统计 ===")
        println("总入口点数: ${validation.stats.totalEntries}")
        println("XML映射条目: ${validation.stats.xmlEntries}")
        println("Java注解条目: ${validation.stats.javaAnnotationEntries}")
        println("BaseMapper条目: ${validation.stats.baseMapperEntries}")
        println("包含SQL语句: ${validation.stats.entriesWithSql}")
        
        if (!validation.isValid) {
            println("\n=== 发现的问题 ===")
            validation.issues.forEach { println("- $it") }
        } else {
            println("\n✅ 扫描结果验证通过")
        }
    }
    
    /**
     * 测试MyBatis映射类与服务类的关联逻辑
     * 这个方法模拟MyBatisXmlScanner中的isServiceRelated逻辑用于测试
     */
    fun testServiceRelation(mapperClass: String, serviceClasses: Set<String>): Boolean {
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
}

data class ScanValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val stats: ScanStats
)

data class ScanStats(
    val totalEntries: Int,
    val xmlEntries: Int,
    val javaAnnotationEntries: Int,
    val baseMapperEntries: Int,
    val entriesWithSql: Int
)