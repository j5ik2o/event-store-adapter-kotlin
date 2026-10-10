package com.github.j5ik2o.event.store.adapter.kotlin.internal

/** Only domain data is serialized as the event payload. */
data class UserAccountEvent(
    val name: String,
)
