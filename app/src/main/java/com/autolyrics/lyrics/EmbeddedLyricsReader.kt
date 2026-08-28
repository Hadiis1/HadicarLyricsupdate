package com.autolyrics.lyrics

import android.util.Log
import aman.taglib.TagLib
import java.util.HashMap

object EmbeddedLyricsReader {

    private const val TAG = "EmbeddedLyrics"

    data class Result(
        val lyrics: String?,
        val metadata: HashMap<String, String>
    )

    fun read(filePath: String): Result? {
        return try {
            val metadata: HashMap<String, String> =
                TagLib.getMetadata(filePath)

            Log.d(TAG, "===== TAGLIB METADATA =====")

            for ((key, value) in metadata) {
                Log.d(TAG, "$key = $value")
            }

            Log.d(TAG, "===== END TAGLIB METADATA =====")

            val lyrics = metadata["LYRICS"]
                ?.takeIf { it.isNotBlank() }

            Log.d(TAG, "LYRICS = ${lyrics ?: "<none>"}")

            Result(
                lyrics = lyrics,
                metadata = metadata
            )

        } catch (e: Exception) {
            Log.e(TAG, "Errore leggendo metadata: $filePath", e)
            null
        }
    }
}