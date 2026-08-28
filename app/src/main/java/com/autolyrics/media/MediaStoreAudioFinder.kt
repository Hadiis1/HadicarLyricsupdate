package com.autolyrics.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log

object MediaStoreAudioFinder {

    private const val TAG = "MediaStoreFinder"

    data class AudioFile(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val path: String
    )

    fun find(
        context: Context,
        title: String,
        artist: String,
        album: String? = null
    ): AudioFile? {

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA
        )

        val selection = buildString {
            append("${MediaStore.Audio.Media.TITLE} = ?")
            append(" AND ")
            append("${MediaStore.Audio.Media.ARTIST} = ?")

            if (!album.isNullOrBlank()) {
                append(" AND ")
                append("${MediaStore.Audio.Media.ALBUM} = ?")
            }
        }

        val selectionArgs = if (!album.isNullOrBlank()) {
            arrayOf(title, artist, album)
        } else {
            arrayOf(title, artist)
        }

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->

                val idIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )
                val titleIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )
                val artistIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )
                val albumIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM
                )
                val pathIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DATA
                )

                if (cursor.moveToFirst()) {
                    val result = AudioFile(
                        id = cursor.getLong(idIndex),
                        title = cursor.getString(titleIndex),
                        artist = cursor.getString(artistIndex),
                        album = cursor.getString(albumIndex),
                        path = cursor.getString(pathIndex)
                    )

                    Log.d(TAG, "Trovato:")
                    Log.d(TAG, "  title  = ${result.title}")
                    Log.d(TAG, "  artist = ${result.artist}")
                    Log.d(TAG, "  album  = ${result.album}")
                    Log.d(TAG, "  path   = ${result.path}")

                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore", e)
        }

        return null
    }
}