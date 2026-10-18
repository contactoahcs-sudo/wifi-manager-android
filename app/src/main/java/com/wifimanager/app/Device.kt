package com.wifimanager.app

data class Device(
    val mac: String,
    val ip: String,
    val name: String,
    val vendor: String,
    val online: Boolean,
    val blocked: Boolean,
    // Nombre interno que el usuario asigna (apodo). Se guarda en el teléfono.
    val alias: String = ""
) {
    fun displayName(): String = when {
        alias.isNotBlank() -> alias
        name.isNotBlank() -> name
        vendor != "Desconocido" -> vendor
        else -> "(sin nombre)"
    }
}
