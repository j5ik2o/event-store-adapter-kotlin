package com.github.j5ik2o.event.store.adapter.kotlin.apisurface

import com.fasterxml.jackson.databind.JsonNode
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance
import kotlin.reflect.full.memberFunctions

/** Checks the Kotlin public store interfaces against surface.json using the mapping in kotlin.json. */
class ApiSurfaceChecker(
    private val surface: JsonNode,
    private val mapping: JsonNode,
    private val storeOverrides: Map<String, KClass<*>> = emptyMap(),
) {
    private val issues = mutableListOf<String>()
    private val kotlinTypes = setOf("kotlin.Long", "kotlin.String", "kotlin.Unit", "kotlin.collections.List")

    fun check(): List<String> {
        issues.clear()
        checkMappingCompleteness()
        checkNames()
        for (store in mapping.path("stores")) {
            checkStore(store)
        }
        return issues.toList()
    }

    private fun text(
        node: JsonNode,
        what: String,
    ): String? {
        if (!node.isTextual) {
            issues.add("mapping: $what is missing")
            return null
        }
        return node.asText()
    }

    private fun checkMappingCompleteness() {
        for (tp in surface.path("store").path("type_parameters")) {
            text(mapping.path("type_parameters").path(tp.asText()), "type_parameters.${tp.asText()}")
        }
        for (name in surface.path("value_types").fieldNames()) {
            text(mapping.path("value_types").path(name), "value_types.$name")
        }
        for (type in surface.path("types")) {
            val name = type.path("name").asText()
            text(mapping.path("types").path(name), "types.$name")
        }
        for (op in surface.path("store").path("operations")) {
            val name = op.path("name").asText()
            text(mapping.path("operations").path(name), "operations.$name")
        }
        for (name in surface.path("error_categories").path("names")) {
            text(mapping.path("error_categories").path("names").path(name.asText()), "error_categories.names.${name.asText()}")
        }
        text(mapping.path("error_categories").path("base"), "error_categories.base")
        // The Kotlin surface promises both a synchronous and a suspending store; a missing entry would skip half the check.
        val stores = mapping.path("stores")
        if (stores.none { !it.path("suspend").asBoolean(false) }) {
            issues.add("mapping: stores has no synchronous store")
        }
        if (stores.none { it.path("suspend").asBoolean(false) }) {
            issues.add("mapping: stores has no suspending store")
        }
        for (key in listOf("none", "optional", "list")) {
            text(mapping.path("containers").path(key), "containers.$key")
        }
        if (mapping.path("containers").path("optional").asText() != "nullable") {
            issues.add("mapping: containers.optional must be nullable")
        }
        if (mapping.path("error_categories").path("failure").asText() != "exception") {
            issues.add("mapping: error_categories.failure must be exception")
        }
    }

    private fun checkNames() {
        val javaPackage = mapping.path("java_package").asText()
        val names = mutableListOf<Pair<String, String>>()
        for (type in surface.path("types")) {
            val name = type.path("name").asText()
            mapping
                .path("types")
                .path(name)
                .takeIf { it.isTextual }
                ?.let { names.add(name to "$javaPackage.${it.asText()}") }
        }
        mapping.path("error_categories").path("base").takeIf { it.isTextual }?.let {
            names.add("error_categories.base" to "$javaPackage.${it.asText()}")
        }
        for ((category, cls) in mapping.path("error_categories").path("names").fields()) {
            names.add(category to "$javaPackage.${cls.asText()}")
        }
        for ((label, fqn) in names) {
            if (!javaClassExists(fqn)) issues.add("$label: class $fqn not found")
        }
        for ((label, fqn) in mapping
            .path("value_types")
            .fields()
            .asSequence()
            .map { it.key to it.value.asText() }) {
            val found = if (fqn.startsWith("kotlin.")) fqn in kotlinTypes else javaClassExists(fqn)
            if (!found) issues.add("$label: class $fqn not found")
        }
    }

    private fun javaClassExists(fqn: String): Boolean =
        try {
            Class.forName(fqn)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    private fun resolveStore(name: String): KClass<*>? =
        storeOverrides[name]
            ?: try {
                Class.forName("${mapping.path("package").asText()}.$name").kotlin
            } catch (_: ClassNotFoundException) {
                issues.add("event_store: class ${mapping.path("package").asText()}.$name not found")
                null
            }

    private fun checkStore(store: JsonNode) {
        val name = store.path("interface").asText()
        val wantSuspend = store.path("suspend").asBoolean()
        val cls = resolveStore(name) ?: return
        checkTypeParameters(name, cls)
        for (op in surface.path("store").path("operations")) {
            checkOperation(name, cls, wantSuspend, op)
        }
    }

    private fun checkTypeParameters(
        name: String,
        cls: KClass<*>,
    ) {
        val want = surface.path("store").path("type_parameters").map { mapping.path("type_parameters").path(it.asText()).asText() }
        val got = cls.typeParameters.map { it.name }
        if (got != want) {
            issues.add("event_store: $name has type parameters $got, want $want")
        }
        for (tp in cls.typeParameters) {
            val bounds = tp.upperBounds.map { render(it) }
            if (bounds != listOf("kotlin.Any?")) {
                issues.add("event_store: type parameter ${tp.name} of $name is bounded by $bounds; want no bound")
            }
        }
    }

    private fun checkOperation(
        store: String,
        cls: KClass<*>,
        wantSuspend: Boolean,
        op: JsonNode,
    ) {
        val opName = op.path("name").asText()
        val fnName = mapping.path("operations").path(opName).asText()
        val fns = cls.memberFunctions.filter { it.name == fnName }
        if (fns.isEmpty()) {
            issues.add("$opName: $store has no function $fnName")
            return
        }
        val fn: KFunction<*> = fns.first()
        val label = "$opName: $store.$fnName"
        if (fn.isSuspend != wantSuspend) {
            issues.add(if (wantSuspend) "$label is not suspend, want suspend" else "$label is suspend, want not suspend")
        }
        val params = fn.parameters.drop(1)
        val inputs = op.path("inputs")
        if (params.size != inputs.size()) {
            issues.add("$label takes ${params.size} parameters, want ${inputs.size()}")
        } else {
            params.zip(inputs).forEach { (param, input) ->
                val want = expected(input.path("type").asText())
                val got = render(param.type)
                if (got != want) issues.add("$label parameter ${param.name} is $got, want $want")
            }
        }
        val wantReturn = expected(op.path("output").asText())
        val gotReturn = render(fn.returnType)
        if (gotReturn != wantReturn) issues.add("$label returns $gotReturn, want $wantReturn")
    }

    private fun expected(expr: String): String {
        val open = expr.indexOf('<')
        val head = if (open < 0) expr else expr.substring(0, open)
        val args = if (open < 0) emptyList() else splitArgs(expr.substring(open + 1, expr.lastIndexOf('>')))
        val containers = mapping.path("containers")
        val javaPackage = mapping.path("java_package").asText()
        return when {
            head == "none" -> {
                containers.path("none").asText()
            }

            head == "optional" -> {
                expected(args.single()) + "?"
            }

            head == "list" -> {
                containers.path("list").asText() + "<" + expected(args.single()) + ">"
            }

            mapping.path("type_parameters").has(head) -> {
                mapping.path("type_parameters").path(head).asText()
            }

            mapping.path("value_types").has(head) -> {
                mapping.path("value_types").path(head).asText()
            }

            mapping.path("types").has(head) -> {
                "$javaPackage.${mapping.path("types").path(head).asText()}" +
                    if (args.isEmpty()) "" else args.joinToString(", ", "<", ">") { expected(it) }
            }

            else -> {
                error("unknown surface type $head")
            }
        }
    }

    private fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        s.forEachIndexed { i, c ->
            when (c) {
                '<' -> {
                    depth++
                }

                '>' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        out.add(s.substring(start, i).trim())
                        start = i + 1
                    }
                }
            }
        }
        out.add(s.substring(start).trim())
        return out
    }

    private fun render(type: KType): String {
        val base =
            when (val c = type.classifier) {
                is KClass<*> -> c.qualifiedName ?: c.toString()
                is KTypeParameter -> c.name
                else -> type.toString()
            }
        val args =
            if (type.arguments.isEmpty()) {
                ""
            } else {
                type.arguments.joinToString(", ", "<", ">") { arg ->
                    val t = arg.type ?: return@joinToString "*"
                    when (arg.variance) {
                        KVariance.OUT -> "out ${render(t)}"
                        KVariance.IN -> "in ${render(t)}"
                        else -> render(t)
                    }
                }
            }
        return base + args + if (type.isMarkedNullable) "?" else ""
    }
}
