package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope

/** Domain state has no library inheritance requirement. */
data class UserAccount(
    val name: String,
) {
    companion object {
        fun replay(
            events: List<EventEnvelope<UserAccountEvent>>,
            snapshot: UserAccount?,
        ): UserAccount =
            events.fold(snapshot) { _, event -> UserAccount(event.payload().name) }
                ?: error("An existing account needs a snapshot or its creation event")
    }
}
