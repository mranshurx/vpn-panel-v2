package com.example.ffcapture

/**
 * Free Fire's position-keyed XOR stream. Self-inverse - apply() both encrypts
 * and decrypts. The keystream is keyed by position, so flipping a ciphertext
 * byte flips exactly the matching plaintext byte.
 */
class FfCipher(private val key: ByteArray) {
    fun apply(data: ByteArray, from: Int, to: Int) {
        if (key.isEmpty()) return
        var j = 0
        for (i in from until to) {
            data[i] = (data[i].toInt() xor key[j % key.size].toInt()).toByte()
            j++
        }
    }
}
