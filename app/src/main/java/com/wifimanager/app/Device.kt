package com.wifimanager.app

data class Device(
    val mac: String,
    val ip: String,
    val name: String,
    val vendor: String,
    val online: Boolean,
    val blocked: Boolean
) {
    fun displayName(): String = when {
        name.isNotBlank() -> name
        vendor != "Desconocido" -> vendor
        else -> "(sin nombre)"
    }
}
