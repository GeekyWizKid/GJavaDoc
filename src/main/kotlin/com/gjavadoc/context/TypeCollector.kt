package com.gjavadoc.context

import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil

class TypeCollector(private val project: Project) {
    data class Result(val classes: MutableList<PsiClass>)

    fun collectForMethod(method: PsiMethod, maxDepth: Int = 2): Result {
        val toVisit = ArrayDeque<PsiClass>()
        val visited = LinkedHashSet<String>()

        fun enqueue(cls: PsiClass?) {
            if (cls == null) return
            val fqn = cls.qualifiedName ?: cls.name ?: return
            if (visited.add(fqn)) toVisit.add(cls)
        }

        fun addFromType(t: PsiType?) {
            if (t == null) return
            when (t) {
                is PsiArrayType -> addFromType(t.componentType)
                is PsiClassType -> {
                    enqueue(PsiUtil.resolveClassInType(t))
                    for (arg in t.parameters) addFromType(arg)
                }
            }
        }

        // seed from params and return type
        for (p in method.parameterList.parameters) addFromType(p.type)
        addFromType(method.returnType)

        val picked = mutableListOf<PsiClass>()
        var depth = 0
        while (toVisit.isNotEmpty() && depth <= maxDepth) {
            val size = toVisit.size
            for (i in 0 until size) {
                val c = toVisit.removeFirst()
                // Always explore supertypes to capture Entity inheritance like VO extends Entity
                enqueue(c.superClass)
                for (itf in c.interfaces) enqueue(itf)
                if (!isTargetClass(c)) {
                    continue
                }
                picked += c
                // Explore field types to extend closure
                for (field in c.allFields) {
                    addFromType(field.type)
                }
                // Explore inner classes
                for (inner in c.innerClasses) enqueue(inner)
            }
            depth++
        }
        return Result(picked.distinctBy { it.qualifiedName ?: it.name }.toMutableList())
    }

    private fun isTargetClass(c: PsiClass): Boolean {
        val cfg = SettingsState.getInstance(project).state.context
        if (c.isEnum) return true
        val name = c.name ?: ""
        val ends = cfg.typeSuffixes.any { name.endsWith(it, true) }
        if (ends) return true
        val pkg = (c.containingFile as? PsiJavaFile)?.packageName ?: ""
        if (cfg.packageKeywords.any { pkg.contains(it) }) return true
        val annos = c.modifierList?.annotations ?: emptyArray()
        for (a in annos) {
            val qn = a.qualifiedName ?: a.nameReferenceElement?.referenceName ?: ""
            if (cfg.annotationWhitelist.any { qn.endsWith(it) || qn == it }) return true
        }
        return false
    }
}
