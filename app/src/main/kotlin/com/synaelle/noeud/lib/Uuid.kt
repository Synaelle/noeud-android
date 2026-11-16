// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.security.SecureRandom

private val random = SecureRandom()

/** A UUID version 7: the time in milliseconds, then random bits (RFC 9562). */
fun uuidv7(now: Long = System.currentTimeMillis()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    var ms = now
    for (i in 5 downTo 0) {
        bytes[i] = (ms and 0xff).toByte()
        ms = ms shr 8
    }
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x70).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
