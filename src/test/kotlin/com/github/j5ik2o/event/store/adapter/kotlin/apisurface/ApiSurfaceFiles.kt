package com.github.j5ik2o.event.store.adapter.kotlin.apisurface

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.file.Path

/** Reads the shared surface and the Kotlin mapping; duplicate keys fail. */
object ApiSurfaceFiles {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    fun surface(): JsonNode = mapper.readTree(Path.of("api-surface/surface.json").toFile())

    fun mapping(): JsonNode = mapper.readTree(Path.of("api-surface/kotlin.json").toFile())
}
