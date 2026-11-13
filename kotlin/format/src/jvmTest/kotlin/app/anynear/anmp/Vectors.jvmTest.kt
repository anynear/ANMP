package app.anynear.anmp

import java.io.File

internal actual fun writeVectorsIfRequested(json: String): Boolean {
    if (System.getProperty("anmp.updateVectors") != "1") return false
    val path = System.getProperty("anmp.vectors") ?: error("anmp.vectors not set")
    File(path).apply { parentFile.mkdirs() }.writeText(json)
    return true
}

internal actual val signaturesAreDeterministic: Boolean = true
