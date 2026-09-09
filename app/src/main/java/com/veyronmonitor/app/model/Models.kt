package com.veyronmonitor.app.model

data class AuthState(
    val token: String,
    val vrtKey: String
)

data class Device(
    val serialNumber: String,
    val displayName: String,
    val isOnline: Boolean
)
