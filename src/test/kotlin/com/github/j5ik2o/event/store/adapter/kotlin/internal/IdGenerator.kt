package com.github.j5ik2o.event.store.adapter.kotlin.internal

import de.huxhorn.sulky.ulid.ULID


object IdGenerator {
    private val ulid: ULID = ULID()
    private var prevValue: ULID.Value? = null

    @Synchronized
    fun generate(): ULID.Value {
        if (prevValue == null) {
            prevValue = ulid.nextValue()
        } else {
            prevValue = ulid.nextMonotonicValue(prevValue)
        }
        return prevValue!!
    }
}

