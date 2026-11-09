package com.keeper.vault.auth

/**
 * Converts pattern-grid visits into the canonical `CharArray` used as a PBKDF2 secret.
 *
 * Nodes are numbered row-major, 0-8. A pattern must visit four to nine distinct nodes. Invalid
 * input returns an empty array so the caller can reject it without a partially built secret.
 */
object PatternCanonicalizer {
    const val MIN_NODES: Int = 4
    const val MAX_NODES: Int = 9

    fun canonicalize(nodes: IntArray): CharArray {
        if (nodes.size < MIN_NODES || nodes.size > MAX_NODES) return CharArray(0)
        val seen = BooleanArray(MAX_NODES)
        val out = CharArray(nodes.size)
        for (index in nodes.indices) {
            val node = nodes[index]
            if (node !in 0 until MAX_NODES || seen[node]) return CharArray(0)
            seen[node] = true
            out[index] = '0' + node
        }
        return out
    }
}
