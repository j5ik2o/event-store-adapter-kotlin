package com.github.j5ik2o.event.store.adapter.kotlin.apisurface

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiSurfaceTest {
    @Test
    fun `current public API matches the surface`() {
        val issues = ApiSurfaceChecker(ApiSurfaceFiles.surface(), ApiSurfaceFiles.mapping()).check()
        assertEquals(emptyList(), issues)
    }
}
