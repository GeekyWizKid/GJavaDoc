package com.gjavadoc.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExistingOutputsTest {

    @Test
    fun parseMethodFileName_withTimestampSuffix() {
        val base = createTempDir(prefix = "gjavadoc-test-")
        try {
            val docs = File(base, "docs"); docs.mkdirs()
            File(docs, "com.xxx.xxx.xxx.xxx.service.XxxxService_checkEnableBill_String_String__1756819782590.md").writeText("dummy")

            val res = ExistingOutputs.index(base.absolutePath)
            assertTrue(res.methodsExact.contains("com.xxx.xxx.xxx.xxx.service.XxxxService" to "checkEnableBill_String_String"))
            // Also present by base name
            assertTrue(res.methodsByName.contains("com.xxx.xxx.xxx.xxx.service.XxxxService" to "checkEnableBill"))
            // Should not treat it as class-level
            assertFalse(res.classSet.contains("com.xxx.xxx.xxx.xxx.service.XxxxService"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun parseClassFileName_withTimestampSuffix() {
        val base = createTempDir(prefix = "gjavadoc-test-")
        try {
            val docs = File(base, "docs"); docs.mkdirs()
            File(docs, "com.example.demo.UserService_CLASS__123456.md").writeText("dummy")

            val res = ExistingOutputs.index(base.absolutePath)
            assertTrue(res.classSet.contains("com.example.demo.UserService"))
            // No method entries should be present for class-level doc
            assertFalse(res.methodsByName.contains("com.example.demo.UserService" to "CLASS"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun parseMethodFileName_singleUnderscoreTimestamp() {
        val base = createTempDir(prefix = "gjavadoc-test-")
        try {
            val docs = File(base, "docs"); docs.mkdirs()
            File(docs, "com.pkg.Service_doIt_Int_String_9999.md").writeText("dummy")

            val res = ExistingOutputs.index(base.absolutePath)
            assertTrue(res.methodsExact.contains("com.pkg.Service" to "doIt_Int_String"))
            assertTrue(res.methodsByName.contains("com.pkg.Service" to "doIt"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun parseGenericTypeAtEnd_andVarargs() {
        val base = createTempDir(prefix = "gjavadoc-test-")
        try {
            val docs = File(base, "docs"); docs.mkdirs()
            // List<String> at the end -> trailing '_' removed by parser
            File(docs, "com.demo.AService_process_List_String__123.md").writeText("dummy")
            // Varargs String... -> represented by underscores then trimmed
            File(docs, "com.demo.AService_log_String__456.md").writeText("dummy")

            val res = ExistingOutputs.index(base.absolutePath)
            assertTrue(res.methodsExact.contains("com.demo.AService" to "process_List_String"))
            assertTrue(res.methodsExact.contains("com.demo.AService" to "log_String"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun parseArrayAndInnerClassParam() {
        val base = createTempDir(prefix = "gjavadoc-test-")
        try {
            val docs = File(base, "docs"); docs.mkdirs()
            // int[] -> underscores trimmed at end
            File(docs, "com.demo.BService_sum_int___789.md").writeText("dummy")
            // Inner class in parameter should not break classFqn split
            File(docs, "com.demo.BService_use_Outer.Inner__888.md").writeText("dummy")

            val res = ExistingOutputs.index(base.absolutePath)
            assertTrue(res.methodsExact.contains("com.demo.BService" to "sum_int"))
            assertTrue(res.methodsExact.contains("com.demo.BService" to "use_Outer.Inner"))
        } finally {
            base.deleteRecursively()
        }
    }
}
