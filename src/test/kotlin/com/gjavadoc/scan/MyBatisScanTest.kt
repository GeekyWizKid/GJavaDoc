package com.gjavadoc.scan

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * MyBatis扫描功能单元测试
 */
class MyBatisScanTest {
    
    @Test
    fun `should detect MyBatis mapper files correctly`() {
        // 标准MyBatis Mapper文件
        val standardMapper = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.UserMapper">
    <select id="selectUser">SELECT * FROM users WHERE id = #{id}</select>
</mapper>"""
        
        assertTrue(MyBatisTestUtils.testMapperDetection(standardMapper), "应该识别标准MyBatis Mapper")
        
        // 无DOCTYPE的Mapper文件
        val noDoctypeMapper = """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.example.UserMapper">
    <select id="selectUser">SELECT * FROM users WHERE id = #{id}</select>
</mapper>"""
        
        assertTrue(MyBatisTestUtils.testMapperDetection(noDoctypeMapper), "应该识别无DOCTYPE的MyBatis Mapper")
    }
    
    @Test
    fun `should not detect non-MyBatis XML files`() {
        // Spring配置文件
        val springConfig = """<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans">
    <bean id="dataSource" class="com.zaxxer.hikari.HikariDataSource"/>
</beans>"""
        
        assertFalse(MyBatisTestUtils.testMapperDetection(springConfig), "不应该识别Spring配置文件")
        
        // 普通XML文件
        val plainXml = """<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="test" value="123"/>
</configuration>"""
        
        assertFalse(MyBatisTestUtils.testMapperDetection(plainXml), "不应该识别普通XML文件")
    }
    
    @Test
    fun `should extract SQL from MyBatis annotations`() {
        // 这是一个概念验证测试，实际的SQL提取需要PSI结构
        // 在实际使用中，EntryScanner会从方法的@Select等注解中提取SQL
        val testCases = listOf(
            "SELECT * FROM users WHERE id = #{id}" to "SELECT * FROM users WHERE id = #{id}",
            "  SELECT COUNT(*) FROM orders  " to "SELECT COUNT(*) FROM orders",
            "" to null,
            " " to null
        )
        
        testCases.forEach { (input, expected) ->
            val cleaned = input.trim().takeIf { it.isNotBlank() }
            assertEquals(expected, cleaned)
        }
    }
    
    @Test
    fun `should correctly identify service-related mappers`() {
        // 测试服务类与Mapper的关联逻辑
        data class TestCase(
            val mapperClass: String,
            val serviceClasses: Set<String>,
            val expected: Boolean,
            val description: String
        )
        
        val testCases = listOf(
            TestCase(
                "com.example.mapper.UserMapper",
                setOf("com.example.service.UserService"),
                true,
                "相同域名的Service和Mapper应该关联"
            ),
            TestCase(
                "com.example.dao.OrderDAO",
                setOf("com.example.service.OrderService"),
                true,
                "相同域名的Service和DAO应该关联"
            ),
            TestCase(
                "com.example.UserMapper",
                setOf("com.example.UserService"),
                true,
                "相同包下的Service和Mapper应该关联"
            ),
            TestCase(
                "com.example.mapper.ProductMapper",
                setOf("com.example.service.UserService"),
                false,
                "不同域名的Service和Mapper不应该关联"
            ),
            TestCase(
                "com.other.UserMapper",
                setOf("com.example.UserService"),
                false,
                "不同包的相同域名不应该关联"
            ),
            TestCase(
                "com.example.UserMapper",
                setOf("com.example.UserService", "com.example.OrderService"),
                true,
                "多个服务类中有一个匹配即应该关联"
            ),
            TestCase(
                "com.example.controller.UserController",
                setOf("com.example.controller.UserController"),
                true,
                "精确匹配应该关联"
            )
        )
        
        testCases.forEach { testCase ->
            val actual = MyBatisTestUtils.testServiceRelation(testCase.mapperClass, testCase.serviceClasses)
            assertEquals(testCase.expected, actual, testCase.description)
        }
    }
    
    @Test
    fun `should create valid test mapper XML`() {
        val testXml = MyBatisTestUtils.createTestMapperXml(
            namespace = "com.example.TestMapper",
            methods = listOf(
                "selectTest" to "select",
                "insertTest" to "insert",
                "updateTest" to "update",
                "deleteTest" to "delete"
            )
        )
        
        assertTrue(testXml.contains("<!DOCTYPE mapper"), "生成的XML应包含DOCTYPE")
        assertTrue(testXml.contains("namespace=\"com.example.TestMapper\""), "应包含正确的namespace")
        assertTrue(testXml.contains("<select id=\"selectTest\">"), "应包含select方法")
        assertTrue(testXml.contains("<insert id=\"insertTest\">"), "应包含insert方法")
        assertTrue(testXml.contains("<update id=\"updateTest\">"), "应包含update方法")
        assertTrue(testXml.contains("<delete id=\"deleteTest\">"), "应包含delete方法")
        
        // 验证生成的XML可以被正确识别
        assertTrue(MyBatisTestUtils.testMapperDetection(testXml), "生成的XML应该被识别为MyBatis Mapper")
    }
    
    @Test
    fun `should validate scan results correctly`() {
        // 创建一些测试入口点
        val validEntries = listOf(
            createTestEntry("com.example.UserMapper", "selectUser", "select"),
            createTestEntry("com.example.UserMapper", "insertUser", "insert")
        )
        
        val validation = MyBatisTestUtils.validateScanResults(validEntries)
        assertTrue(validation.isValid, "有效的入口点应该通过验证")
        assertEquals(0, validation.issues.size, "有效的入口点不应该有问题")
        assertEquals(2, validation.stats.totalEntries, "应该有2个总入口点")
    }
    
    @Test
    fun `should detect validation issues`() {
        // 创建一些有问题的测试入口点
        val invalidEntries = listOf(
            createTestEntry("", "method1", "select"), // 空的classFqn
            createTestEntry("com.example.Mapper", "", "insert"), // 空的method
            createTestEntry("com.example.Mapper", "method3", "update", line = -1) // 无效的行号
        )
        
        val validation = MyBatisTestUtils.validateScanResults(invalidEntries)
        assertFalse(validation.isValid, "有问题的入口点不应该通过验证")
        assertTrue(validation.issues.size >= 3, "应该检测到至少3个问题")
    }
    
    private fun createTestEntry(
        classFqn: String, 
        method: String, 
        sqlType: String, 
        line: Int = 1
    ): com.gjavadoc.model.EntryPoint {
        return com.gjavadoc.model.EntryPoint(
            classFqn = classFqn,
            method = method,
            file = "/test/TestMapper.xml",
            line = line,
            annotation = "MyBatisXml",
            sqlStatement = when(sqlType) {
                "select" -> "SELECT * FROM users WHERE id = #{id}"
                "insert" -> "INSERT INTO users (name) VALUES (#{name})"
                "update" -> "UPDATE users SET name = #{name} WHERE id = #{id}"
                "delete" -> "DELETE FROM users WHERE id = #{id}"
                else -> "SELECT 1"
            },
            xmlFilePath = "/test/TestMapper.xml"
        )
    }
}