package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.event.store.adapter.java.AggregateId

data class UserAccountId(
    @JsonProperty("value") private val value: String,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private val typeName: String = "user-account",
) : AggregateId {
    override fun getTypeName(): String = typeName

    override fun getValue(): String = value

    override fun asString(): String = String.format("%s-%s", getTypeName(), getValue())
}
