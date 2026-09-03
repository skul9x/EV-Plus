package com.evcs.favorites.data.crypto

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for HMAC-SHA256 request signing required by EVCS background search API.
 * Replicates the decompiled logic from `y/a.java`:
 * - Secret key: `"I3mrBi6aytR0q3P1o01O3HJIUU0P3YYdFzA9n1psJoht5ZOvn5FYE49pOalRGpk2vaplKyOM57LDwBkjtjts0X"`
 * - Algorithm: `HmacSHA256(body + timestamp)`
 * - Output: lowercase hex string
 * - Headers: `User-Agent: EVCS/A1.57`, `X-App-Timestamp`, `X-App-Signature`, `Referer`, `Origin`
 */
object EvcsHmacSigner {
    const val SECRET_KEY: String =
        "I3mrBi6aytR0q3P1o01O3HJIUU0P3YYdFzA9n1psJoht5ZOvn5FYE49pOalRGpk2vaplKyOM57LDwBkjtjts0X"

    const val USER_AGENT: String = "EVCS/A1.57"
    const val DEFAULT_ORIGIN: String = "https://evcs.vn"
    const val DEFAULT_REFERER: String = "https://evcs.vn/"

    /**
     * Signs the request body and timestamp using HMAC-SHA256.
     *
     * @param body Raw JSON body string
     * @param timestamp Epoch timestamp in milliseconds as string
     * @param secret Optional secret override (defaults to EVCS app secret)
     * @return Lowercase hexadecimal signature string
     */
    fun sign(body: String, timestamp: String, secret: String = SECRET_KEY): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val dataToSign = (body + timestamp).toByteArray(StandardCharsets.UTF_8)
        val hmacBytes = mac.doFinal(dataToSign)
        val sb = StringBuilder(hmacBytes.size * 2)
        for (b in hmacBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Creates all necessary headers for authenticated search API calls.
     */
    fun createSignedHeaders(
        body: String,
        timestamp: String = System.currentTimeMillis().toString()
    ): Map<String, String> {
        val signature = sign(body, timestamp)
        return mapOf(
            "User-Agent" to USER_AGENT,
            "X-App-Timestamp" to timestamp,
            "X-App-Signature" to signature,
            "Referer" to DEFAULT_REFERER,
            "Origin" to DEFAULT_ORIGIN,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }
}
