package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.github.j5ik2o.event.store.adapter.java.Event
import java.time.Instant

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(name = "created", value = UserAccountEvent.Created::class),
    JsonSubTypes.Type(name = "renamed", value = UserAccountEvent.Renamed::class),
)
interface UserAccountEvent : Event<UserAccountId> {
    override fun getAggregateId(): UserAccountId
    override fun getSequenceNumber(): Long

    @JsonTypeName("created")
    @JsonIgnoreProperties(value = ["created"], allowGetters = true)
    class Created(
        @JsonProperty("id") private val id: String,
        @JsonProperty("aggregateId") private val aggregateId: UserAccountId,
        @JsonProperty("sequenceNumber") private val sequenceNumber: Long,
        @JsonProperty("name") val name: String,
        @JsonProperty("occurredAt") private val occurredAt: Instant,
    ) : UserAccountEvent {

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        override fun isCreated(): Boolean {
            return true
        }

        override fun getId(): String {
            return id
        }

        override fun getAggregateId(): UserAccountId {
            return aggregateId
        }

        override fun getSequenceNumber(): Long {
            return sequenceNumber
        }

        override fun getOccurredAt(): Instant {
            return occurredAt
        }
    }

    @JsonTypeName("renamed")
    @JsonIgnoreProperties(value = ["created"], allowGetters = true)
    class Renamed(
        @JsonProperty("id") private val id: String,
        @JsonProperty("aggregateId") private val aggregateId: UserAccountId,
        @JsonProperty("sequenceNumber") private val sequenceNumber: Long,
        @JsonProperty("name") val name: String,
        @JsonProperty("occurredAt") private val occurredAt: Instant,
    ) : UserAccountEvent {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        override fun isCreated(): Boolean {
            return false
        }

        override fun getId(): String {
            return id
        }

        override fun getAggregateId(): UserAccountId {
            return aggregateId
        }

        override fun getSequenceNumber(): Long {
            return sequenceNumber
        }

        override fun getOccurredAt(): Instant {
            return occurredAt
        }
    }
}
