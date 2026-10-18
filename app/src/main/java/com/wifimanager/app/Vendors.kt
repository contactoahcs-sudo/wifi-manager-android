package com.wifimanager.app

/**
 * Fabricante a partir de la MAC (OUI = primeros 3 bytes).
 * Tabla con los fabricantes más comunes. Detecta también MAC aleatoria.
 */
object Vendors {
    private val table: Map<String, String> = mapOf(
        "FCFBFB" to "Apple", "A4C361" to "Apple", "F0989D" to "Apple", "DC2B2A" to "Apple",
        "001B63" to "Apple", "3C0754" to "Apple", "88665A" to "Apple", "A85C2C" to "Apple",
        "9CFC01" to "Apple", "6C4008" to "Apple", "F0766F" to "Apple", "A860B6" to "Apple",
        "C82A14" to "Apple", "D0E140" to "Apple", "A4B197" to "Apple",
        "001A11" to "Google", "F4F5E8" to "Google", "3C5AB4" to "Google",
        "D83134" to "Roku", "B0A737" to "Roku",
        "F0272D" to "Samsung", "5001BB" to "Samsung", "8425DB" to "Samsung", "FCA13E" to "Samsung",
        "0012FB" to "Samsung", "E8508B" to "Samsung", "C4731E" to "Samsung",
        "B827EB" to "Raspberry Pi", "DCA632" to "Raspberry Pi", "E45F01" to "Raspberry Pi",
        "D83ADD" to "Raspberry Pi",
        "0011D8" to "ASUS", "AC220B" to "ASUS", "50465D" to "ASUS", "2C56DC" to "ASUS",
        "001FC6" to "ASUS", "3497F6" to "ASUS", "AC9E17" to "ASUS",
        "C46E1F" to "TP-Link", "50C7BF" to "TP-Link", "A42BB0" to "TP-Link", "9C5322" to "TP-Link",
        "60E327" to "TP-Link", "EC086B" to "TP-Link", "003192" to "TP-Link", "E894F6" to "TP-Link",
        "984827" to "TP-Link", "F4EC38" to "TP-Link", "14CC20" to "TP-Link",
        "80B686" to "Huawei", "48435A" to "Huawei", "00E0FC" to "Huawei", "283CE4" to "Huawei",
        "F4559C" to "Huawei", "D0D04B" to "Huawei", "1C1D67" to "Huawei",
        "F09FC2" to "Ubiquiti", "245A4C" to "Ubiquiti", "788A20" to "Ubiquiti", "B4FBE4" to "Ubiquiti",
        "002722" to "Ubiquiti",
        "F8E903" to "D-Link", "1CBDB9" to "D-Link", "C8BE19" to "D-Link", "00265A" to "D-Link",
        "14D64D" to "D-Link", "B0C554" to "D-Link", "001346" to "D-Link",
        "6038E0" to "Belkin", "08863B" to "Belkin",
        "0017C2" to "Sony", "FCF152" to "Sony", "A8E3EE" to "Sony",
        "30F772" to "Amazon", "44650D" to "Amazon", "68543D" to "Amazon", "FCA667" to "Amazon",
        "F0D2F1" to "Amazon",
        "001E2A" to "Netgear", "20E52A" to "Netgear", "A040A0" to "Netgear", "C03F0E" to "Netgear",
        "9C3DCF" to "Netgear",
        "8C705A" to "Intel", "A0A8CD" to "Intel", "7C7A91" to "Intel", "E4A471" to "Intel",
        "34E6D7" to "Dell", "F8BC12" to "Dell", "D4BED9" to "Dell", "18DBF2" to "Dell",
        "B083FE" to "Dell", "D067E5" to "Dell",
        "001DD8" to "Microsoft", "0017FA" to "Microsoft", "7C1E52" to "Microsoft",
        "582F40" to "Nintendo", "0019FD" to "Nintendo", "CC9E00" to "Nintendo",
        "286C07" to "Xiaomi", "64CC2E" to "Xiaomi", "F48B32" to "Xiaomi", "A4A930" to "Xiaomi",
        "7C1DD9" to "Xiaomi", "D4970B" to "Xiaomi", "F0B429" to "Xiaomi", "50647A" to "Xiaomi",
        "8CBEBE" to "Xiaomi", "FC64BA" to "Xiaomi", "C46AB7" to "Xiaomi",
        "ACE2D3" to "HP", "3C52A1" to "Foxconn", "089E01" to "Quanta",
        "4C17EB" to "Sagemcom", "A0648F" to "Askey", "D4356D" to "Sagemcom"
    )

    fun lookup(mac: String): String {
        val h = mac.uppercase().filter { it in "0123456789ABCDEF" }
        if (h.length < 6) return "Desconocido"
        val first = h.substring(0, 2).toIntOrNull(16) ?: return "Desconocido"
        if (first and 0x02 != 0) return "MAC aleatoria"
        return table[h.substring(0, 6)] ?: "Desconocido"
    }
}
