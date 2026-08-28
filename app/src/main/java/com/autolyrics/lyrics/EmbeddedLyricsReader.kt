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

            val lyrics = metadata["LYRICS"]
                ?.takeIf { it.isNotBlank() }

            Result(
                lyrics = lyrics,
                metadata = metadata
            )

        } catch (e: Exception) {
            null
        }
    }
}