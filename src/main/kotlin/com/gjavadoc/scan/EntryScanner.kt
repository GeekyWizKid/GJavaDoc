package com.gjavadoc.scan

import com.gjavadoc.model.EntryPoint
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.JavaPsiFacade

class EntryScanner(private val project: Project) {

    companion object {
        private const val BASE_MAPPER_FQN = "com.baomidou.mybatisplus.core.mapper.BaseMapper"
        private val MYBATIS_ANNOTATIONS = setOf(
            "org.apache.ibatis.annotations.Select",
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete"
        )
        private val DEFAULT_BASE_MAPPER_METHODS = setOf(
            "insert", "deleteById", "selectById", "updateById", "selectList", 
            "selectOne", "update", "delete", "selectPage", "selectMaps", "selectObjs"
        )
    }

    fun scan(scope: GlobalSearchScope? = null): List<EntryPoint> {
        val settings = SettingsState.getInstance(project).state
        val targets = settings.annotation
            .split(',', ';', '\n', '\t', ' ')
            .map { it.trim().trimStart('@') }
            .filter { it.isNotEmpty() }
        val searchScope = scope ?: GlobalSearchScope.projectScope(project)
        
        // Scan Java files for annotated classes and methods
        val javaResults = FilenameIndex.getAllFilesByExt(project, "java", searchScope)
            .mapNotNull { vf ->
                val psi = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile
                psi?.classes?.flatMap { cls ->
                    scanClass(vf, cls, targets)
                }
            }
            .flatten()
        
        // Scan XML files for MyBatis mappers
        val xmlResults = MyBatisXmlScanner(project).scan(searchScope)
        
        // Combine and deduplicate results
        return (javaResults + xmlResults)
            .deduplicateByMethod()
    }

    private fun scanClass(vf: VirtualFile, cls: PsiClass, targets: List<String>): List<EntryPoint> {
        val classTagged = hasAnyAnnotation(cls.modifierList, targets)
        val isMyBatisMapper = cls.isInterface && 
            cls.implementsList?.referencedTypes?.any { 
                it.resolve()?.qualifiedName == BASE_MAPPER_FQN 
            } == true

        return cls.methods.mapNotNull { method ->
            when {
                classTagged || hasAnyAnnotation(method.modifierList, targets) -> {
                    entryPointFor(vf, cls, method, SettingsState.getInstance(project).state.annotation)
                }
                isMyBatisMapper && method.name in getBaseMapperMethods() -> {
                    entryPointFor(vf, cls, method, "MyBatis-Plus BaseMapper")
                }
                else -> null
            }
        }
    }

    private fun List<EntryPoint>.deduplicateByMethod(): List<EntryPoint> {
        return this
            .distinctBy { it.classFqn + "#" + it.method.substringBefore('(') }
            .groupBy { it.classFqn + "#" + it.method.substringBefore('(') }
            .values
            .map { group ->
                group.maxBy { entry ->
                    when (entry.annotation) {
                        "MyBatisXml" -> 1
                        "MyBatis-Plus BaseMapper" -> 2
                        else -> 3 // Java annotations have highest priority
                    }
                }
            }
    }

    private fun hasAnyAnnotation(modifierList: PsiModifierList?, targets: List<String>): Boolean {
        modifierList?.annotations?.forEach { ann ->
            val qn = ann.qualifiedName ?: ""
            val sn = ann.nameReferenceElement?.referenceName ?: ""
            
            // Check for target annotations
            targets.forEach { t ->
                if (qn.endsWith(".$t") || sn == t || qn == t) return true
            }
            
            // Check for common MyBatis annotations
            if (qn in MYBATIS_ANNOTATIONS) return true
        }
        return false
    }

    private fun entryPointFor(vf: VirtualFile, cls: PsiClass, method: PsiMethod, rawAnnotation: String): EntryPoint {
        val doc = PsiDocumentManager.getInstance(project).getDocument(method.containingFile)
        val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 1
        val signature = buildString {
            append(method.name)
            append('(')
            append(method.parameterList.parameters.joinToString(",") { it.type.presentableText })
            append(')')
        }
        val classFqn = cls.qualifiedName ?: cls.name ?: "UnknownClass"
        return EntryPoint(
            classFqn = classFqn,
            method = signature,
            file = vf.path,
            line = line,
            annotation = rawAnnotation,
        )
    }

    private fun getBaseMapperMethods(): Set<String> {
        return findPsiClass(BASE_MAPPER_FQN)?.methods
            ?.filter { method ->
                method.hasModifierProperty(PsiModifier.PUBLIC) &&
                method.containingClass?.qualifiedName != "java.lang.Object"
            }
            ?.map { it.name }
            ?.toSet()
            ?: DEFAULT_BASE_MAPPER_METHODS
    }

    private fun findPsiClass(fqn: String): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        return JavaPsiFacade.getInstance(project).findClass(fqn, scope)
    }
}
