package com.hivecore.runtime.models

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build check (swarm plan §4A.2): the APK must package QAIRT 2.50.0's libQnnHtp.so and ORT 1.29.0's
 * libonnxruntime.so from src/main/jniLibs. AAR-vs-AAR pickFirst once silently kept sherpa's ORT 1.28.2.
 */
class PackagedLibsTest {
    private fun lib(name: String): File {
        val candidates = listOf("src/main/jniLibs/arm64-v8a", "../app/src/main/jniLibs/arm64-v8a").map { File(it, name) }
        return candidates.firstOrNull { it.isFile } ?: throw AssertionError("$name not found in ${candidates.map { it.absolutePath }}")
    }

    private fun contains(f: File, needle: String): Boolean {
        val pat = needle.toByteArray(Charsets.ISO_8859_1)
        val bytes = f.readBytes()
        outer@ for (i in 0..bytes.size - pat.size) {
            for (j in pat.indices) if (bytes[i + j] != pat[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun qnnHtpIsQairt250() {
        assertTrue("libQnnHtp.so is not QAIRT v2.50.0", contains(lib("libQnnHtp.so"), "v2.50.0"))
    }

    @Test fun onnxRuntimeIs129() {
        assertTrue("libonnxruntime.so is not ORT 1.29.0", contains(lib("libonnxruntime.so"), "1.29.0"))
    }
}
