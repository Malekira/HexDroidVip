/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import com.boxlabs.hexdroid.data.NetworkProfile

/** An unsaved draft: do not share network-scoped certificates or learned server identity. */
internal fun duplicateNetworkProfile(source: NetworkProfile, names: Collection<String>): NetworkProfile {
    val base = source.name + " — copia"
    var name = base
    var number = 2
    while (names.any { it.equals(name, ignoreCase = true) }) name = "$base ${number++}"
    return source.copy(
        id = "net_" + java.util.UUID.randomUUID().toString().replace("-", ""),
        name = name,
        autoConnect = false,
        tlsClientCertId = null,
        tlsClientCertLabel = null,
        bouncerNetworkId = null,
        tlsTofuFingerprint = null,
        tlsTofuFingerprints = emptySet(),
        tlsAcceptedIdentities = emptySet(),
        tlsHostnameGrace = false,
    )
}
