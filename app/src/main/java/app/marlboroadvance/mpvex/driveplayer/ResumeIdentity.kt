package app.marlboroadvance.mpvex.driveplayer

import android.content.Intent
import java.text.Normalizer
import java.util.Locale

/**
 * Drive Player MPV (fork): satu identitas resume berbasis NAMA FILE.
 *
 * Format kanonik: nama file lengkap dengan ekstensi, Unicode NFC, huruf kecil,
 * spasi ujung dibuang. Tanpa path, tanpa URL, tanpa hash.
 *
 * Dipakai untuk:
 *  - stream dengan intent extra [EXTRA_RESUME_KEY] (kunci = nama file dari PWA), dan
 *  - file lokal (nama file-nya), sehingga stream dan file hasil unduhan berbagi posisi.
 *
 * Stream TANPA resume_key tetap memakai identitas upstream (nama + hash URI).
 *
 * Semua kode fork ditaruh di package ini; PlayerActivity hanya memberi hook tipis.
 */
object ResumeIdentity {
  /** Extra intent: kunci resume = nama file video yang sudah dinormalisasi PWA. */
  const val EXTRA_RESUME_KEY = "resume_key"

  /** Posisi tersimpan di atas rasio ini dianggap "sudah selesai": putar ulang dari awal. */
  const val RESTORE_LIMIT_RATIO = 0.95

  /** Interval simpan berkala untuk jalur resume_key (jaga-jaga tertutup paksa dari recents). */
  const val PERIODIC_SAVE_MS = 15_000L

  /** Bentuk kanonik dari sebuah nama file. */
  fun canonical(name: String): String =
    Normalizer
      .normalize(name.trim(), Normalizer.Form.NFC)
      .lowercase(Locale.ROOT)
      .trim()

  /**
   * Identitas dari extra `resume_key`, atau null jika extra tidak ada / kosong
   * (artinya: perilaku upstream, tidak berubah).
   *
   * Nilai TIDAK didecode lagi: `Intent.parseUri` sudah mendecode `S.resume_key=...`
   * dari URI intent://. Mendecode dua kali akan merusak nama file yang memang
   * mengandung '%'.
   */
  fun fromIntent(intent: Intent): String? =
    intent
      .getStringExtra(EXTRA_RESUME_KEY)
      ?.let(::canonical)
      ?.takeIf { it.isNotEmpty() }

  /**
   * Apakah posisi tersimpan boleh di-restore?
   *
   * Hanya berlaku untuk identitas kanonik (resume_key / file lokal). Untuk identitas
   * upstream selalu true supaya perilakunya tetap persis seperti mpvEx asli.
   */
  fun shouldRestore(
    isCanonicalIdentity: Boolean,
    lastPosition: Int,
    timeRemaining: Int,
  ): Boolean {
    if (!isCanonicalIdentity) return true
    val duration = lastPosition + timeRemaining
    if (duration <= 0) return true
    return lastPosition < duration * RESTORE_LIMIT_RATIO
  }
}
