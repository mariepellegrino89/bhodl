package io.bhodl.desktop.wallet

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Canonical form of an account-level extended public key, matching what
 * the core stores in the registry descriptor: SLIP-132 `zpub`/`ypub`
 * become `xpub`, `vpub`/`upub` become `tpub` (same key, BIP-32 version
 * bytes). Anything else — plain `xpub`/`tpub`, multisig prefixes, or a
 * string that is not valid base58check — is returned trimmed and
 * unchanged, so the core keeps reporting its own validation errors.
 */
public object AccountKey {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val XPUB = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
    private val TPUB = byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())

    public fun normalize(raw: String): String {
        val key = raw.trim()
        val target = when (key.take(4)) {
            "zpub", "ypub" -> XPUB
            "vpub", "upub" -> TPUB
            else -> return key
        }
        val payload = decodeCheck(key)?.takeIf { it.size == 78 } ?: return key
        target.copyInto(payload)
        return encodeCheck(payload)
    }

    private fun checksum(data: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(sha.digest(data)).copyOf(4)
    }

    internal fun decodeCheck(s: String): ByteArray? {
        var n = BigInteger.ZERO
        for (c in s) {
            val d = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(d.toLong()))
        }
        val body = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val zeros = s.takeWhile { it == '1' }.length
        val full = ByteArray(zeros) + if (n.signum() == 0) ByteArray(0) else body
        if (full.size < 5) return null
        val data = full.copyOfRange(0, full.size - 4)
        return if (checksum(data).contentEquals(full.copyOfRange(full.size - 4, full.size))) data else null
    }

    internal fun encodeCheck(data: ByteArray): String {
        val full = data + checksum(data)
        var n = BigInteger(1, full)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(full.takeWhile { it == 0.toByte() }.size) { sb.append('1') }
        return sb.reverse().toString()
    }
}
