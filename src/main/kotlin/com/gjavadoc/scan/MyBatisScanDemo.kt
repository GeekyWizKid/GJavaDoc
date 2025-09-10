package com.gjavadoc.scan

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * MyBatis扫描功能演示类
 * 展示如何使用改进后的MyBatis扫描代码
 */
class MyBatisScanDemo(private val project: Project) {
    
    /**
     * 演示完整的扫描流程
     */
    fun demonstrateScanning() {
        println("=== MyBatis扫描演示 ===\n")
        
        // 1. 测试XML文件特征检测
        demonstrateFeatureDetection()
        
        // 2. 演示扫描过程
        demonstrateXmlScanning()
        
        // 3. 演示集成扫描（XML + Java注解）
        demonstrateIntegratedScanning()
        
        // 4. 演示缓存功能
        demonstrateCaching()
    }
    
    /**
     * 演示MyBatis特征检测
     */
    private fun demonstrateFeatureDetection() {
        println("1. 📋 MyBatis特征检测演示")
        
        val testFiles = mapOf(
            "MyBatis Mapper (标准)" to """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.UserMapper">
    <select id="selectUser">SELECT * FROM users</select>
</mapper>""",
            
            "MyBatis Mapper (无DOCTYPE)" to """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.example.UserMapper">
    <select id="selectUser">SELECT * FROM users</select>
</mapper>""",
            
            "Spring配置文件" to """<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans">
    <bean id="dataSource" class="com.zaxxer.hikari.HikariDataSource"/>
</beans>""",
            
            "普通XML文件" to """<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="test" value="123"/>
</configuration>"""
        )
        
        testFiles.forEach { (description, content) ->
            val isMapper = MyBatisTestUtils.testMapperDetection(content)
            val status = if (isMapper) "✅ 识别为MyBatis Mapper" else "❌ 非MyBatis Mapper"
            println("   $description: $status")
        }
        println()
    }
    
    /**
     * 演示XML扫描过程
     */
    private fun demonstrateXmlScanning() {
        println("2. 🔍 XML文件扫描演示")
        
        val scanner = MyBatisXmlScanner(project)
        
        try {
            // 尝试扫描test-resources目录中的文件
            val testResourcesPath = "${project.basePath}/test-resources"
            val testResourcesDir = LocalFileSystem.getInstance().findFileByPath(testResourcesPath)
            
            if (testResourcesDir != null) {
                val results = scanner.scan()
                println("   扫描到 ${results.size} 个MyBatis入口点")
                
                // 展示扫描结果
                results.forEach { entry ->
                    println("   📄 ${entry.classFqn}#${entry.method}")
                    println("      文件: ${File(entry.file).name}:${entry.line}")
                    if (!entry.sqlStatement.isNullOrEmpty()) {
                        val sqlPreview = entry.sqlStatement!!.lines().firstOrNull()?.trim()?.take(50) ?: ""
                        println("      SQL: ${sqlPreview}${if (sqlPreview.length >= 50) "..." else ""}")
                    }
                    println()
                }
                
                // 显示缓存统计
                val (cacheSize, modifiedCacheSize) = scanner.getCacheStats()
                println("   📊 缓存统计: $cacheSize 个文件已缓存, $modifiedCacheSize 个时间戳记录")
            } else {
                println("   ⚠️  测试资源目录不存在: $testResourcesPath")
                println("   💡 创建示例XML文件来测试扫描功能")
                createSampleXmlFile()
            }
        } catch (e: Exception) {
            println("   ❌ 扫描过程出现错误: ${e.message}")
        }
        println()
    }
    
    /**
     * 演示集成扫描（XML + Java注解 + MyBatis-Plus）
     */
    private fun demonstrateIntegratedScanning() {
        println("3. 🔗 集成扫描演示")
        
        val entryScanner = EntryScanner(project)
        val allResults = entryScanner.scan()
        
        println("   总扫描结果: ${allResults.size} 个入口点")
        
        // 按注解类型分类统计
        val stats = allResults.groupBy { it.annotation }
        stats.forEach { (annotation, entries) ->
            println("   📊 $annotation: ${entries.size} 个")
        }
        
        // 显示去重和合并的结果示例
        val xmlEntries = allResults.filter { it.annotation == "MyBatisXml" }
        val javaEntries = allResults.filter { it.annotation != "MyBatisXml" && it.annotation != "MyBatis-Plus BaseMapper" }
        val baseMapperEntries = allResults.filter { it.annotation == "MyBatis-Plus BaseMapper" }
        
        println("   🔄 去重合并结果:")
        println("      MyBatis XML条目: ${xmlEntries.size}")
        println("      Java注解条目: ${javaEntries.size}")  
        println("      BaseMapper条目: ${baseMapperEntries.size}")
        
        // 显示有SQL语句的条目
        val entriesWithSql = allResults.filter { !it.sqlStatement.isNullOrEmpty() }
        println("      包含SQL语句: ${entriesWithSql.size}")
        
        println()
    }
    
    /**
     * 演示缓存功能
     */
    private fun demonstrateCaching() {
        println("4. ⚡ 缓存功能演示")
        
        val scanner = MyBatisXmlScanner(project)
        
        println("   第一次扫描...")
        val start1 = System.currentTimeMillis()
        val results1 = scanner.scan()
        val time1 = System.currentTimeMillis() - start1
        
        println("   第二次扫描（使用缓存）...")
        val start2 = System.currentTimeMillis()
        val results2 = scanner.scan()
        val time2 = System.currentTimeMillis() - start2
        
        println("   📈 性能对比:")
        println("      第一次扫描: ${time1}ms, 发现 ${results1.size} 个入口点")
        println("      第二次扫描: ${time2}ms, 发现 ${results2.size} 个入口点")
        
        val speedup = if (time2 > 0) String.format("%.1f", time1.toDouble() / time2) else "∞"
        println("      加速倍数: ${speedup}x")
        
        // 清理缓存演示
        scanner.clearCache()
        val (cacheSize, modifiedCacheSize) = scanner.getCacheStats()
        println("   🧹 缓存清理后: $cacheSize 个文件缓存, $modifiedCacheSize 个时间戳记录")
        
        println()
    }
    
    /**
     * 创建示例XML文件用于测试
     */
    private fun createSampleXmlFile() {
        val sampleXml = MyBatisTestUtils.createTestMapperXml(
            namespace = "com.example.DemoMapper",
            methods = listOf(
                "selectDemo" to "select",
                "insertDemo" to "insert",
                "updateDemo" to "update",
                "deleteDemo" to "delete"
            )
        )
        
        println("   📝 示例MyBatis XML内容:")
        println("   " + sampleXml.lines().take(10).joinToString("\n   "))
        println("   ... (共 ${sampleXml.lines().size} 行)")
    }
    
    /**
     * 验证和诊断功能
     */
    fun runDiagnostics() {
        println("=== MyBatis扫描诊断 ===\n")
        
        val entryScanner = EntryScanner(project)
        val results = entryScanner.scan()
        
        // 使用测试工具验证结果
        MyBatisTestUtils.printScanStats(results)
        
        // 额外的诊断信息
        println("\n=== 额外诊断信息 ===")
        
        // 检查项目中的XML文件
        val xmlFiles = FilenameIndex.getAllFilesByExt(project, "xml", GlobalSearchScope.projectScope(project))
        println("项目中XML文件总数: ${xmlFiles.size}")
        
        val mybatisXmlCount = xmlFiles.count { xmlFile ->
            try {
                val content = String(xmlFile.contentsToByteArray())
                MyBatisTestUtils.testMapperDetection(content)
            } catch (e: Exception) {
                false
            }
        }
        println("MyBatis Mapper文件数: $mybatisXmlCount")
        
        // 性能建议
        if (results.size > 100) {
            println("\n💡 性能建议:")
            println("   - 发现大量入口点(${results.size}个)，建议启用缓存")
            println("   - 考虑使用更精确的扫描范围来提高性能")
        }
        
        if (xmlFiles.size > mybatisXmlCount * 2) {
            println("\n💡 过滤建议:")
            println("   - 项目中包含较多非MyBatis XML文件")
            println("   - 文件过滤逻辑正在有效工作，避免了不必要的解析")
        }
    }
}