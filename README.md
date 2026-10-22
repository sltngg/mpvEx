# Drive Player MPV

Fork pribadi dari [mpvEx](https://github.com/marlboro-advance/mpvEx) (lisensi Apache-2.0, lihat `LICENSE`; README asli ada di `README.upstream.md`).

Satu fitur baru: **resume berdasarkan nama file**, seperti nPlayer. Posisi tonton tetap nyambung walau URL stream berganti (link bertanda tangan kedaluwarsa), dan walau file yang sama kemudian diunduh lalu diputar dari penyimpanan HP (dan sebaliknya). Tidak ada sinkron cloud, akun, atau perubahan jaringan.

> **Status build:** perubahan kode ditulis dengan membaca source upstream, tetapi **belum pernah dikompilasi atau dijalankan di perangkat** oleh penulisnya (lingkungan tanpa Android SDK/HP). Build pertama di GitHub Actions adalah kompilasi pertama. Kalau gagal, kirim log-nya. Jalankan checklist pengujian di bawah di HP-mu sebelum mengandalkannya.

## Package yang dipakai PWA

| | |
|---|---|
| applicationId | `app.driveplayer.mpv` |
| Nama tampil | Drive Player MPV |

Bisa dipasang berdampingan dengan mpvEx asli (`app.marlboroadvance.mpvex`). Kedua nilai ada di `gradle.properties` (`drivePlayer.applicationId`, `drivePlayer.appName` tinggal diganti; nama tampil juga ada di `app/src/main/res/values/strings.xml`, `app_name`).

## Kontrak intent

`ACTION_VIEW`, data URL `https`, tipe `video/*`, dengan extra opsional:

| Extra | Tipe | Arti |
|---|---|---|
| `title` | String | judul yang ditampilkan (sudah didukung upstream) |
| `resume_key` | String | nama file video, huruf kecil, tanpa spasi ujung. Jika ada, dipakai sebagai identitas video |

Contoh dari PWA:

```
intent://<host><path>#Intent;type=video/any;package=app.driveplayer.mpv;scheme=https;S.title=<judul>;S.resume_key=<namaFileTerenkode>;end;
```

Manifest upstream sudah menerima `VIEW` + `https` + `video/*`, jadi **tidak ada perubahan intent-filter**. Extra dibaca lewat `intent` saat ini, sehingga berlaku di `onCreate` maupun `onNewIntent` (activity `singleTask`).

`Intent.parseUri` sudah men-decode nilai `S.resume_key=...`; kode **tidak men-decode lagi** (nama file yang memang mengandung `%` akan rusak kalau didecode dua kali). Pastikan PWA meng-encode **satu kali**.

## Identitas resume

Satu format kanonik untuk semua: **nama file lengkap dengan ekstensi, Unicode NFC, huruf kecil (`Locale.ROOT`), spasi ujung dibuang**. Tanpa path, URL, atau hash.

| Sumber | Kunci (`PlaybackStateEntity.mediaTitle`) |
|---|---|
| Stream dengan `resume_key` | kanonik dari `resume_key` |
| File lokal | kanonik dari nama file |
| Stream tanpa `resume_key` | upstream: `nama_hash(URI)` (tidak berubah) |
| Stream proxy SMB/WebDAV/FTP | upstream (tidak berubah) |

Konsekuensi yang sengaja diterima:

- Dua file **berbeda** dengan nama persis sama berbagi posisi (juga di antara file lokal di folder berbeda).
- Agar stream dan unduhan nyambung, **nama file hasil unduhan harus sama dengan nama di Drive** (huruf besar/kecil tidak masalah).
- Mengubah normalisasi (misalnya NFC) harus tetap sama di PWA, kalau tidak kunci tidak akan cocok. Catatan: `toLowerCase()` JavaScript dan `lowercase(Locale.ROOT)` Kotlin bisa berbeda untuk beberapa huruf khusus (mis. `İ`); nama file biasa aman.

### Data lama

Format lama file lokal (`nama_hashPath`) dibaca sebagai **cadangan**: jika kunci kanonik belum ada, dipakai state lama; penyimpanan berikutnya menulis kunci kanonik. Tidak ada migrasi database dan tidak ada perubahan skema Room.

Perilaku yang berubah untuk file lokal (dampak dari identitas tunggal):

- Menghapus file lokal lewat app **hanya** menghapus kunci lama; kunci kanonik dibiarkan supaya posisi stream/unduhan lain dengan nama sama tidak hilang.
- Mengganti nama file memindahkan kunci kanonik lama ke yang baru, kecuali kunci baru sudah ada.

## Ringkasan arsitektur upstream (hasil baca source)

Semua di `app/src/main/java/app/marlboroadvance/mpvex/`:

- **Intent masuk**: `ui/player/PlayerActivity.kt` (`parsePathFromIntent`, `getFileName` yang sudah membaca extra `title`, `onNewIntent`).
- **Pembuatan `mediaIdentifier`**: `PlayerActivity.getMediaIdentifier()` (intent), `getMediaIdentifierFromUri()` (item playlist), `localMediaIdentifier()`; helper file lokal di `utils/media/MediaIdentifier.kt`. Upstream: stream `http(s)` = `"${fileName}_${uri.hashCode()}"`; file lokal = `"${nama}_${path.hashCode()}"`.
- **Simpan state**: `PlayerActivity.saveVideoPlaybackState()` → `PlaybackStateRepository.upsert()` → `database/dao/PlaybackStateDao.kt`, entity `database/entities/PlaybackStateEntity.kt` (primary key `mediaTitle`).
- **Restore**: event `MPV_EVENT_FILE_LOADED` → `handleFileLoaded()` → `loadVideoPlaybackState()` → `applyPlaybackState()` (`MPVLib.setPropertyInt("time-pos", ...)`).
- **Daftar video**: `VideoListViewModel`, `FolderListViewModel`, `FileSystemBrowserViewModel` mencari state lewat `MediaIdentifier.forLocalPath(path)`.
- **Riwayat "Recently Played"**: `utils/history/RecentlyPlayedOps.kt`, dikunci oleh **URI/path**, bukan `mediaIdentifier`.

## Verifikasi fondasi (simpan/restore stream upstream)

Dilakukan dengan membaca kode, **bukan** dengan menjalankan app:

- Simpan terjadi di `onPause` (sinkron hanya saat finishing), `onStop` (asinkron, tidak saat finishing). **Tidak ada penyimpanan di `onDestroy`.** Tidak ada ambang "minimal sudah menonton" saat menyimpan.
- Simpan **dan** restore keduanya bergantung pada preferensi *Save position on quit* (`savePositionOnQuit`, default aktif). Kalau dimatikan, resume tidak jalan sama sekali.
- Masalah yang ditemukan dan diperbaiki **untuk jalur `resume_key`**:
  1. Simpan di `onPause` yang bukan finishing berjalan asinkron, jadi bisa hilang kalau proses dimatikan (tutup paksa dari recents). → sekarang sinkron, ditambah simpan berkala tiap 15 detik saat memutar.
  2. Keluar saat video masih loading bisa menimpa posisi tersimpan dengan 0. → penyimpanan ditahan sampai state lama selesai dimuat.
  3. `onNewIntent` (PWA membuka video lain di activity yang sama) tidak menyimpan posisi video sebelumnya. → disimpan dulu.
  4. Tidak ada simpan di `onDestroy`. → ditambahkan.
  5. Upstream tidak punya aturan "jangan seek jika >95%" saat restore (hanya mereset ke 0 jika posisi ≥ durasi−1 detik). → ditambahkan untuk identitas kanonik (resume_key dan file lokal). Stream tanpa `resume_key` tetap persis upstream.
- **Belum terverifikasi di perangkat**: apakah `time-pos` yang di-set saat `FILE_LOADED` selalu diterima mpv untuk stream HTTP yang belum seekable. Lihat butir 2 di checklist; kalau gagal, laporkan.

## Build via GitHub Actions (repo privat)

Fork GitHub dari repo publik tidak bisa dijadikan privat, jadi buat repo privat baru lalu dorong isi repo ini:

```bash
# 1. buat repo privat KOSONG di GitHub (mis. <user>/drive-player-mpv), lalu:
git clone <url-repo-ini> drive-player-mpv && cd drive-player-mpv
git remote set-url origin git@github.com:<user>/drive-player-mpv.git
git push -u origin --all
```

(Mengambil update upstream nanti: `git remote add upstream https://github.com/marlboro-advance/mpvEx.git`, lalu `git fetch upstream && git merge upstream/main`. Kode fork sengaja ditaruh di package `driveplayer` dengan hook tipis agar konflik minimal.)

**2. Buat keystore** (sekali saja, simpan filenya di tempat aman; kehilangan keystore = APK berikutnya tidak bisa menimpa instalasi lama):

```bash
keytool -genkeypair -v -keystore release.keystore -alias driveplayer \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.keystore > keystore.b64   # macOS: base64 -i release.keystore -o keystore.b64
```

**3. Isi secrets** di repo: *Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Isi |
|---|---|
| `SIGNING_KEYSTORE` | isi `keystore.b64` (satu baris base64) |
| `SIGNING_KEY_ALIAS` | `driveplayer` (alias di langkah 2) |
| `SIGNING_STORE_PASSWORD` | password keystore |
| `KEY_PASSWORD` | password key (boleh sama dengan password keystore) |

**4. Jalankan**: tab *Actions → "Drive Player MPV - build APK (arm64-v8a)" → Run workflow*.

**5. Unduh APK**: buka run yang selesai, bagian *Artifacts* di bawah, unduh `DrivePlayerMPV-arm64-v8a` (zip berisi `DrivePlayerMPV-arm64-v8a.apk`, disimpan 30 hari). Ini artifact privat, bukan release publik.

Workflow upstream (build/preview/release) dipindah ke `.github/workflows-upstream/` supaya tidak berjalan di repo privatmu. Build lokal (JDK 17, Android SDK): `./gradlew assembleStandardRelease -PdriveOnlyArm64=true` (unsigned) atau `./gradlew assembleDebug`.

## Checklist pengujian

Tandai di HP setelah memasang APK. Pastikan *Save position on quit* aktif.

- [ ] 1. Intent **tanpa** extra → perilaku sama seperti mpvEx asli.
- [ ] 2. Putar dengan `resume_key=a.mkv`, keluar di menit X, putar lagi dengan **URL berbeda** (path dan query beda) tapi `resume_key=a.mkv` → lanjut dari menit X. (Sekaligus memverifikasi seek saat `FILE_LOADED` untuk stream.)
- [ ] 3. `resume_key` berbeda dengan URL yang sama → tidak berbagi posisi.
- [ ] 3b. Nama file dengan spasi, huruf besar, kurung, atau karakter Unicode → kunci terbaca benar (tidak rusak oleh encoding intent).
- [ ] 4. Keluar dengan tombol back, tombol home, dan tutup paksa dari recent apps → posisi tetap tersimpan (tutup paksa: paling jauh mundur ±15 detik).
- [ ] 5. Video yang sudah selesai (>95%) diputar lagi → mulai dari awal.
- [ ] 6. Stream dengan `resume_key=film.mkv` sampai menit X, lalu putar file lokal bernama `Film.mkv` dari penyimpanan HP → lanjut dari menit X. Lalu sebaliknya.
- [ ] 7. Stream **tanpa** `resume_key` → perilaku sama seperti mpvEx asli (kunci `nama_hashURI`).
- [ ] 8. (tambahan) Buka video B lewat intent saat video A masih diputar di activity yang sama → posisi A tersimpan, B mulai dari posisinya sendiri.
- [ ] 9. (tambahan) Data resume file lokal dari mpvEx lama (kunci `nama_hashPath`) tetap terbaca; daftar video menampilkan progres dengan benar.
- [ ] 10. (tambahan) Daftar "Recently Played" tidak error. Catatan: riwayat dikunci per URI, jadi stream yang URL-nya berganti akan muncul sebagai entri terpisah (perilaku upstream, tidak diubah).

## File yang diubah dibanding upstream

Baru:

- `app/src/main/java/app/marlboroadvance/mpvex/driveplayer/ResumeIdentity.kt`
- `app/src/main/java/app/marlboroadvance/mpvex/driveplayer/PlaybackStateLookup.kt`
- `.github/workflows/drive-player-build.yml`
- `README.md` (README asli → `README.upstream.md`)

Diubah:

- `gradle.properties`: konstanta `drivePlayer.applicationId`, `drivePlayer.appName`
- `app/build.gradle.kts`: applicationId dari properti; updater upstream dimatikan (flavor `standard`); opsi `-PdriveOnlyArm64=true`
- `app/src/main/res/values/strings.xml`: `app_name`
- `ui/player/PlayerActivity.kt`: hook identitas, simpan/restore (lihat bagian verifikasi)
- `utils/media/MediaIdentifier.kt`: `forLocalPath` jadi kanonik; `legacyForLocalPath` = format lama
- `utils/media/PlaybackStateOps.kt`: rename/hapus (lihat "Data lama")
- `ui/browser/{videolist/VideoListViewModel,folderlist/FolderListViewModel,filesystem/FileSystemBrowserViewModel}.kt`: lookup dengan cadangan kunci lama

Dipindah: `.github/workflows/{build,pre-release,preview,release}.yml` → `.github/workflows-upstream/`.

Tidak diubah: `AndroidManifest.xml`, skema/migrasi Room, mpv.conf dan pengaturan, namespace/package Kotlin (`app.marlboroadvance.mpvex`, agar diff dan merge upstream kecil).

## Keputusan yang diambil sendiri

- Namespace Kotlin tidak diganti; hanya `applicationId`.
- Updater bawaan dimatikan karena akan menawarkan rilis mpvEx asli.
- Simpan berkala 15 detik hanya untuk jalur `resume_key`.
- Hapus file lokal tidak menghapus kunci kanonik (lihat "Data lama").
