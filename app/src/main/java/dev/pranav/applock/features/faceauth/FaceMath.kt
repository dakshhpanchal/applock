package dev.pranav.applock.features.faceauth

import kotlin.math.sqrt

/** Small vector helpers for face embeddings. Pure Kotlin so they can be unit tested on the JVM. */
object FaceMath {

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Cosine similarity. Works for any vectors; for L2-normalized ones it is just the dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Best similarity of [probe] against any enrolled template. */
    fun bestMatch(probe: FloatArray, templates: List<FloatArray>): Float =
        templates.maxOfOrNull { cosine(probe, it) } ?: 0f

    /** Normalized mean of several embeddings. */
    fun mean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty())
        val out = FloatArray(vectors[0].size)
        for (v in vectors) for (i in v.indices) out[i] += v[i]
        return l2Normalize(out)
    }
}
