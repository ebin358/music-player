package dev.lelonio.square.desktop.local

import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Music files in folders the listener chose, played by the engine's stream
 * decoder: MP3, AAC/M4A, FLAC, Ogg Vorbis and WAV.
 *
 * Names come from the files: "Artist - Title.ext" where the name says so,
 * otherwise the folder above is taken as the album and its parent as the artist.
 */
object LocalLibrary {
    private val EXTENSIONS = setOf("mp3", "m4a", "aac", "mp4", "flac", "ogg", "oga", "wav")

    suspend fun scan(folders: List<String>): List<Track> = withContext(Dispatchers.IO) {
        folders.asSequence()
            .map(::File)
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().maxDepth(6).filter { it.isFile && it.extension.lowercase() in EXTENSIONS } }
            .map(::trackOf)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album?.lowercase() }, { it.title.lowercase() }))
            .toList()
    }

    fun trackOf(file: File): Track {
        val name = file.nameWithoutExtension
            .replace(Regex("""^\d{1,3}[\s.\-_]+"""), "") // leading track numbers
            .trim()
        val album = file.parentFile?.name
        val parentArtist = file.parentFile?.parentFile?.name
        val split = name.split(" - ", limit = 2)
        val (artist, title) = if (split.size == 2) split[0].trim() to split[1].trim() else (parentArtist ?: "Unknown artist") to name
        return Track(
            id = file.absolutePath,
            source = Source.LOCAL,
            title = title.ifBlank { file.name },
            artist = artist,
            album = album,
        )
    }
}
