package app.marlboroadvance.mpvex.driveplayer

import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.utils.media.MediaIdentifier

/**
 * Drive Player MPV (fork): cari state resume file lokal.
 *
 * Coba kunci kanonik (nama file) dulu, lalu kunci upstream lama ("nama_hashPath")
 * sebagai cadangan supaya data resume lama tidak hilang.
 */
suspend fun PlaybackStateRepository.findByLocalPath(path: String): PlaybackStateEntity? =
  getVideoDataByTitle(MediaIdentifier.forLocalPath(path))
    ?: getVideoDataByTitle(MediaIdentifier.legacyForLocalPath(path))
