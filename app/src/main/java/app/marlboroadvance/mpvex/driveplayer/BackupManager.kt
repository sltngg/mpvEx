package app.marlboroadvance.mpvex.driveplayer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.get

/**
 * Drive Player MPV (fork): backup / restore data resume + riwayat ke satu file JSON.
 *
 * - Kunci resume adalah nama file kanonik, jadi backup ini tetap berguna di perangkat baru
 *   walau URL stream sudah berganti.
 * - Restore bersifat MENGGABUNG: data yang sudah ada di perangkat tidak ditimpa
 *   (kecuali penanda "sudah ditonton" digabung dengan OR, dan riwayat yang lebih baru menang).
 * - Subtitle eksternal tidak ikut dibackup (URI-nya hanya berlaku di perangkat asal).
 */
object BackupManager {
  private const val FORMAT = "driveplayer-backup"
  private const val VERSION = 1
  private const val MAX_HISTORY_ROWS = 5000

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  @Serializable
  data class PlaybackDto(
    val mediaTitle: String,
    val lastPosition: Int,
    val playbackSpeed: Double,
    val videoZoom: Float = 0f,
    val sid: Int,
    val secondarySid: Int = -1,
    val subDelay: Int,
    val subSpeed: Double,
    val aid: Int,
    val audioDelay: Int,
    val timeRemaining: Int = 0,
    val hasBeenWatched: Boolean = false,
  )

  @Serializable
  data class HistoryDto(
    val filePath: String,
    val fileName: String,
    val videoTitle: String? = null,
    val duration: Long = 0,
    val fileSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val timestamp: Long,
    val launchSource: String? = null,
  )

  @Serializable
  data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val playback: List<PlaybackDto> = emptyList(),
    val history: List<HistoryDto> = emptyList(),
  )

  data class Result(val playback: Int, val history: Int)

  private fun db(): MpvExDatabase = get(MpvExDatabase::class.java)

  suspend fun export(context: Context, target: Uri): Result {
    val database = db()
    val states = database.videoDataDao().getAllPlaybackStates()
    val history = database.recentlyPlayedDao().getAllStandalone()

    val file = BackupFile(
      exportedAt = System.currentTimeMillis(),
      playback = states.map {
        PlaybackDto(
          it.mediaTitle, it.lastPosition, it.playbackSpeed, it.videoZoom, it.sid, it.secondarySid,
          it.subDelay, it.subSpeed, it.aid, it.audioDelay, it.timeRemaining, it.hasBeenWatched,
        )
      },
      history = history.map {
        HistoryDto(
          it.filePath, it.fileName, it.videoTitle, it.duration, it.fileSize,
          it.width, it.height, it.timestamp, it.launchSource,
        )
      },
    )

    val out = context.contentResolver.openOutputStream(target, "wt")
      ?: error("Tidak bisa menulis ke file tujuan")
    out.use { it.write(json.encodeToString(BackupFile.serializer(), file).toByteArray(Charsets.UTF_8)) }
    return Result(file.playback.size, file.history.size)
  }

  suspend fun import(context: Context, source: Uri): Result {
    val text = context.contentResolver.openInputStream(source)
      ?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: error("Tidak bisa membaca file backup")
    val file = json.decodeFromString(BackupFile.serializer(), text)
    require(file.format == FORMAT) { "Bukan file backup Drive Player MPV" }
    require(file.version <= VERSION) { "Versi backup lebih baru dari aplikasi ini" }

    val database = db()
    val stateDao = database.videoDataDao()
    val historyDao = database.recentlyPlayedDao()
    var playbackAdded = 0
    var historyAdded = 0

    database.withTransaction {
      // --- Posisi resume: tambahkan yang belum ada; penanda ditonton digabung (OR) ---
      val existing = file.playback.map { it.mediaTitle }.distinct().chunked(500)
        .flatMap { stateDao.getByTitles(it) }
        .associateBy { it.mediaTitle }
      for (dto in file.playback) {
        val old = existing[dto.mediaTitle]
        if (old == null) {
          stateDao.upsert(
            PlaybackStateEntity(
              mediaTitle = dto.mediaTitle,
              lastPosition = dto.lastPosition,
              playbackSpeed = dto.playbackSpeed,
              videoZoom = dto.videoZoom,
              sid = dto.sid,
              secondarySid = dto.secondarySid,
              subDelay = dto.subDelay,
              subSpeed = dto.subSpeed,
              aid = dto.aid,
              audioDelay = dto.audioDelay,
              timeRemaining = dto.timeRemaining,
              externalSubtitles = "",
              hasBeenWatched = dto.hasBeenWatched,
            ),
          )
          playbackAdded++
        } else if (dto.hasBeenWatched && !old.hasBeenWatched) {
          stateDao.upsert(old.copy(hasBeenWatched = true))
        }
      }

      // --- Riwayat: satu entri per file; yang lebih baru menang ---
      for (dto in file.history) {
        val pattern = ResumeIdentity.streamSuffixPattern(dto.filePath)
        val match = historyDao.getByFilePath(dto.filePath)
          ?: pattern?.let { historyDao.findByPathSuffix(it.exact, it.withQuery, it.withFragment).firstOrNull() }
        if (match != null && match.timestamp >= dto.timestamp) continue
        historyDao.insert(
          RecentlyPlayedEntity(
            id = match?.id ?: 0,
            filePath = dto.filePath,
            fileName = dto.fileName,
            videoTitle = dto.videoTitle ?: match?.videoTitle,
            duration = dto.duration,
            fileSize = dto.fileSize,
            width = dto.width,
            height = dto.height,
            timestamp = dto.timestamp,
            launchSource = match?.launchSource ?: dto.launchSource,
          ),
        )
        if (match == null) historyAdded++
        if (pattern != null && match != null) historyDao.deleteDuplicatesByPathSuffix(pattern.exact, pattern.withQuery, pattern.withFragment, match.id)
      }
      if (historyDao.count() > MAX_HISTORY_ROWS) historyDao.trimTo(MAX_HISTORY_ROWS)
    }
    return Result(playbackAdded, historyAdded)
  }
}
