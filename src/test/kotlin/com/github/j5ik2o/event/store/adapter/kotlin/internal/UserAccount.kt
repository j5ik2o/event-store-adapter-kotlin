package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.event.store.adapter.java.Aggregate
import java.time.Instant

data class UserAccount private constructor(
    @JsonProperty("id") private val id: UserAccountId,
    @JsonProperty("sequenceNumber") private var sequenceNumber: Long,
    @JsonProperty("name") val name: String,
    @JsonProperty("version") private var version: Long,
) : Aggregate<UserAccount, UserAccountId> {
    fun applyEvent(event: UserAccountEvent): UserAccount =
        if (event is UserAccountEvent.Renamed) {
            changeName(event.name).first
        } else {
            throw IllegalArgumentException()
        }

    fun changeName(name: String): Pair<UserAccount, UserAccountEvent> {
        val userAccount = UserAccount(id, sequenceNumber, name, version)
        userAccount.sequenceNumber++
        val event =
            UserAccountEvent.Renamed(
                IdGenerator.generate().toString(),
                userAccount.id,
                userAccount.sequenceNumber,
                name,
                Instant.now(),
            )
        return Pair(userAccount, event)
    }

    override fun getId(): UserAccountId = id

    override fun getSequenceNumber(): Long = sequenceNumber

    override fun getVersion(): Long = version

    override fun withVersion(version: Long): UserAccount = copy(version = version)

    companion object {
        fun replay(
            events: List<UserAccountEvent>,
            snapshot: UserAccount,
        ): UserAccount =
            events.fold(snapshot) { obj: UserAccount, event: UserAccountEvent ->
                obj.applyEvent(
                    event,
                )
            }

        fun create(
            id: UserAccountId,
            name: String,
        ): Pair<UserAccount, UserAccountEvent> {
            val userAccount = UserAccount(id, 0L, name, 1L)
            userAccount.sequenceNumber++
            val event =
                UserAccountEvent.Created(
                    IdGenerator.generate().toString(),
                    userAccount.id,
                    userAccount.sequenceNumber,
                    name,
                    Instant.now(),
                )
            return Pair(userAccount, event)
        }
    }
}
