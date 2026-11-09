package com.keeper.vault.presence.face

import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceMatcher

/**
 * L2-distance matcher.
 *
 * The threshold is configuration, not a security constant: embeddings are spoofable by a photo or
 * a replay. This matcher only feeds lock behavior; it never sees a [VaultKey][com.keeper.vault.contracts.crypto.VaultKey]
 * and never decrypts anything.
 */
class L2FaceMatcher : FaceMatcher {
    override fun distance(a: FaceEmbedding, b: FaceEmbedding): Float = a.l2Distance(b)

    override fun isOwner(candidate: FaceEmbedding, enrolled: FaceEmbedding, threshold: Float): Boolean =
        distance(candidate, enrolled) <= threshold
}
