# Lyrify

Lyrify makes Samsung Music show synced lyrics for the songs on your phone. It watches what
Samsung Music is playing, looks the song up on [LRCLIB](https://lrclib.net) and saves a
`.lrc` file next to the audio file: for the current song and the next 5 in the queue first,
then for the rest of the queue in the background (shuffle can play any of them next), so the
lyrics are there before each song starts.

A personal app for a Samsung Galaxy (One UI, Samsung Music 16), installed with adb, never
published.

Optionally (off by default), **AI lyrics** fill in songs LRCLIB has no synced lyrics for: overnight,
while the phone is plugged in, an on-device AI listens to the song and writes the lyrics itself, or
times LRCLIB's plain lyrics. The AI runs on the phone's AI chip (the Snapdragon NPU), so only on
phones with a supported chip; see "AI lyrics" below. Everything else works on any phone.

- Only **creates** `.lrc` files. It never changes, moves, renames or deletes an audio file,
  never writes tags, and never overwrites a `.lrc` that is already there (yours or its own).
- It deletes exactly three kinds of file: a partial `.lrc` the same write just created (when
  the write fails halfway); when you tap **Wrong lyrics** / **Remove lyrics** and confirm, a
  `.lrc` Lyrify wrote whose bytes still match the SHA-256 it recorded; and the partial `.lrc`
  of a write of its own that the app's death cut short (see "How it works", step 7). Never
  anything else in your folders. (In its own app-private folders it also deletes the AI models
  when you tap **Delete models**, a model download whose checksum does not match, a model
  that fails to load, the downloaded Whisper archive once it is unpacked (or when it is not
  the expected one), a half-unpacked Whisper folder before unpacking again, model files an
  earlier version used, the checkpoints Lyrify 2.0 kept in its cache, and the AI's rotated log.)
- Only lyrics timed for **this exact recording** are written: LRCLIB's duration must be
  within 3 s of the file's, and every file is checked with a model of Samsung Music's
  parser before it is written, so Samsung never ends up with an empty lyrics file.
- Plain (untimed) lyrics, other versions and songs LRCLIB does not know are only recorded,
  not written. Plain lyrics keep their LRCLIB id for a later version that times them.
- A song with no artist in its tags (and no "Artist - Title" title or file name) never gets
  lyrics written by itself: a title alone could match anyone's song. Lyrify searches by title
  instead and, when LRCLIB has lyrics of the right length, asks you on **Check these**.
- Requests to LRCLIB carry artist, title, album and duration only (and, for AI timing, the
  id of an LRCLIB record). The only other network use is the one-time AI model download
  from Qualcomm AI Hub, GitHub and Hugging Face. Nothing else leaves the phone; the AI runs
  offline. The record of what was done stays in an app-private file and is not backed up.

## Build

Requirements: JDK 17 or newer (tested with JDK 21) and the Android SDK with
platform `android-37.0` and build-tools 36.0.0. No NDK: the app has no native code of its own.

```sh
cd Lyrify
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME is not set
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only, about 111 MB, most
of it the QNN runtime).

Coverage report for the unit tests (JaCoCo, built into AGP):

```sh
./gradlew createDebugUnitTestCoverageReport
# app/build/reports/coverage/test/debug/index.html
```

Toolchain: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, Kotlin 2.4.21 with the
serialization plugin, Compose BOM 2026.09.00 (Material 3), DataStore 1.3.0,
kotlinx.serialization 1.12.0, kotlinx.coroutines 1.11.0. compileSdk 37, targetSdk 36,
minSdk 30, WorkManager 2.12.0 (AI model download and overnight run). No networking
library: `HttpURLConnection`. AI engine: ONNX Runtime 1.30.0 (`com.microsoft.onnxruntime:onnxruntime-android`,
telemetry off), Qualcomm's QNN execution provider plugin 2.6.0 (`com.qualcomm.qti:onnxruntime-android-qnn`,
built and tested with QAIRT 2.50) and the QNN runtime 2.50.0 (`com.qualcomm.qti:qnn-runtime`, build
v2.50.0.260828221209, the one AI Hub compiled the Whisper context binaries with; only the HTP backend and the
Hexagon v69, v75, v79 and v81 libraries are packaged), as measured in `lyrify-npu-spike`. The native libraries are
extracted on install (`useLegacyPackaging`): the QNN plugin is registered by file path, and the NPU loads its skel
library from a folder in `ADSP_LIBRARY_PATH`.

## Install over wireless adb

1. On the phone: Settings > Developer options > Wireless debugging > on.
2. Tap **Pair device with pairing code**, then on the computer:
   ```sh
   adb pair <phone-ip>:<pairing-port>     # enter the 6-digit code
   adb connect <phone-ip>:<debug-port>    # port shown on the Wireless debugging screen
   adb devices                            # note the serial, e.g. 192.168.1.32:34079
   ```
3. Install:
   ```sh
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <serial> shell dumpsys package app.lyrify | grep versionName
   ```
4. Diagnostics: `adb -s <serial> logcat -s Lyrify`.

## First run on Samsung

Lyrify shows a setup screen until both switches are on.

1. **Notification access.** Tap **Open notification access** and switch Lyrify on. This is
   what lets an app read another app's media session (title, artist, queue); Lyrify does not
   read your notifications.
2. **If the switch is greyed out** ("Restricted setting"): Android blocks this for apps
   installed outside an app store. Try to switch it on once, then in Lyrify tap
   **Open App info**, the three-dot menu at the top right > **Allow restricted settings**,
   confirm with your PIN or fingerprint, and go back to step 1.
3. **All files access.** Tap **Allow all files access** and switch it on. Lyrify needs it to
   find your songs in MediaStore and to create `.lrc` files in folders like
   `SdCardBackUp/Music` or `Music/Telegram`.
4. **Samsung battery**: Settings > Battery > Background usage limits >
   **Never sleeping apps** > add Lyrify. Otherwise One UI may stop the listener.
5. Play a song in Samsung Music. The home screen shows it under **Now playing** with its
   status and "Queue: X of N checked"; songs appear under **Songs** as they are done.
   If the card says Lyrify is not connected although access is on, switch notification
   access off and on.

Lyrics written while a song plays show up the next time that song loads (Samsung reads
lyrics when a track starts), which is why the queue is fetched ahead.

## Statuses

| Status (chip) | Meaning | Looked up again |
| --- | --- | --- |
| Synced lyrics added (Synced) | Lyrify wrote the `.lrc` | never |
| Already had lyrics | a `.lrc` was there; untouched | never |
| Plain lyrics only (waiting for AI timing) (Plain only) | LRCLIB has untimed lyrics only; the ledger notes whether they are for this length | after 30 days |
| Not found | nothing on LRCLIB | after 30 days |
| Instrumental | LRCLIB says instrumental, or the title says (Instrumental) / karaoke | after 30 days |
| Only synced for another version (e.g. live) (Other version) | synced lyrics exist only for a version of another length | after 30 days |
| LRCLIB busy, will retry (Retrying) | LRCLIB overloaded or offline, or the file could not be written | 30 min, doubling, at most 24 h |
| No artist in the song's tags (No artist) | no artist to look up, and a search by title found nothing to offer | after 30 days |
| Needs a quick check (Needs check) | LRCLIB may have the song, but Lyrify is not sure: pick the lyrics on **Check these** | after 30 days; the AI leaves it alone |
| Removed by you (Removed) | you removed lyrics Lyrify wrote | never |
| Saving lyrics… (Saving) | a write in progress (only seen later if the app died during it) | settled the next time the song is seen |
| AI lyrics added (heard in the song) (AI lyrics) | no lyrics were known; the AI wrote what it heard | never |
| AI-timed lyrics added (AI-timed lyrics) | the AI timed LRCLIB's plain lyrics for this recording | never |
| AI: instrumental, no singing heard (AI: instrumental) | the AI heard no more than 9.8 words a minute in the song (or no vocal part at all) | never |
| AI not confident, nothing added (AI not confident) | the two AI timings agreed on under 80% of the lines, the times did not step forward, the lyrics could not be placed at all, too few lines, the song is too long for the AI (over 20 minutes, or more memory than the phone gives it), or its audio is in a form the AI cannot use (such as a sample rate it does not resample) | never; the reason is kept for a later version |
| AI failed, will try again (AI failed) | the AI could not run on the song (unreadable file, out of memory, or twice the app died during it or a model would not load) | by the AI after a day, doubling, at most 30 days |

The AI statuses replace LRCLIB's 30-day recheck: once the AI has settled a song, neither
LRCLIB nor the AI looks at it again by itself.

**Retry failed now** puts every "LRCLIB busy" track at the front of the background sweep
(so the current song still goes first, but a paused sweep does not hold it up); it needs
the listener connected. While the listener is connected, every "LRCLIB busy" track whose
retry is due, in the queue or not, also goes to the end of the background sweep when the
listener connects and every 30 minutes after (one whose audio file is gone is skipped). A
track is otherwise looked up when it is in Samsung Music's queue (again whenever the queue
changes, which costs nothing for tracks already done).

## Check these

When nothing can be written by itself (not found, no artist, only another version, or plain
lyrics of another length), Lyrify searches LRCLIB by title (`q=`: the title's words, and the
file name's when they differ; at most 2 searches). It keeps up to 3 records whose title is the
song's (or one holds the other as whole words, version tags and feat. aside), whose length is
within 3 s, with lyrics Samsung Music can show (or plain ones) that are not mostly in Arabic
script; an artist named in the tags, title or file name ranks first, then the closest length,
then synced. The song then waits on the home screen's "N songs need a quick check" card:

- **This one** writes those lyrics like any other (plain lyrics are kept for the AI to time).
- **None of these** leaves the song to the AI; it is not offered again.
- **Show lyrics** shows the first lines, fetched when asked and never stored.
- **Search myself** runs your own search and shows up to 10 results of the song's length.

A song whose artist tag is an uploader's name never gets lyrics written by themselves: such
songs come here. A lookup asks LRCLIB at most 12 times (5 queries of 2 requests, 2 searches), each
retried while LRCLIB is busy. After an upgrade that finds more (`LOOKUP_REV`), every "Not
found" and "No artist" song whose file is still there is looked up once more in the
background sweep, and the AI waits for that: LRCLIB's lyrics beat the AI's.

## Songs list and removing wrong lyrics

The home screen lists every song Lyrify has handled, newest first. Search by title or artist
(case and accents ignored: "motorhead" finds "Motörhead"), and filter by status with the
chips (each shows its count); tapping a status counter opens the list filtered to it.

If lyrics Lyrify added are wrong, tap **Wrong lyrics** on the now-playing card or
**Remove lyrics** on the song's row and confirm. Lyrify deletes the `.lrc` only if it is
byte for byte what it wrote (SHA-256 in the ledger), marks the song "Removed by you" (before
deleting, so a deleted file is never added back) and never adds lyrics for it again by itself.
Samsung Music may keep showing them until it restarts. A changed file is left alone; a file
already gone is just marked "Removed by you". Files v1 wrote (no hash) are adopted on first run
if their modification time is within 120 s of the ledger entry; others show "Changed outside
Lyrify, remove it with a file manager".

## How it works

Everything that decides something is plain Kotlin (no Android imports) and unit tested:
`lyrics/TrackNames`, `lyrics/Matcher`, `lyrics/LrcFormat`, `lyrics/LyricsPipeline`,
`data/Ledger`, `media/SessionTracks`, `media/QueueSweep`, `ui/SongFilter`, and for AI lyrics
`models/ModelStore` (download, resume, checksum, unpacking), `models/NpuChips` (supported phones),
`overnight/AiNight` (queue and run), `overnight/CrashGuard` and the engine's pure parts (below).

1. `media/NowPlayingListener` (a notification listener, bound by the system, no foreground
   service) follows the media session of `com.sec.android.app.music`. Every session
   callback reads a snapshot (current track, active queue item, queue); only a snapshot
   that differs from the last one counts, after 1 s without further change.
2. `media/QueueSweep`, one worker with two lanes: HIGH is the current track and the 5 queue
   items after the active one (`PlaybackState.activeQueueItemId`), LOW the rest of the
   queue from there, wrapping round ("Retry failed now" tracks go first in LOW, "LRCLIB busy"
   tracks due again last). HIGH always goes first; a LOW item in progress is dropped when
   HIGH work arrives and redone later. A new current track replaces the pending HIGH lane
   and drops the HIGH item in progress (even mid-backoff) unless it is still the current
   song or one of the next 5; a write already under way always finishes. A new queue
   replaces the queue part of LOW. After 3 LOW items in a row found LRCLIB busy, LOW pauses
   for 2 minutes ("paused, LRCLIB busy") while HIGH keeps going. An item that fails
   unexpectedly is logged and skipped.
3. `media/TrackResolver` finds every file a queue item may be: MediaStore rows with the same
   title (`COLLATE NOCASE`, then compared normalized) and, when the item names one, the same
   artist; for the current track also a duration within 2 s. Copies of a song in two
   folders both get lyrics, each looked up and validated with its own tags and duration.
   More than 4 matches (a generic "Intro") are skipped. Samsung's media ids are its own,
   not MediaStore ids, so they are not used.
4. `lyrics/LyricsPipeline`, one lookup per file at a time (different files run side by
   side): an existing `.lrc` wins; a track not due per the ledger, or removed by you, is
   skipped; otherwise `TrackNames` cleans the tags (bitrate suffixes, track numbers,
   " - Topic", junk like "(Official Video)", "Artist - Title" in the title, feat., quotes,
   unbalanced brackets, uploader handles), and the file name when the tags say too little
   ("_" read as a space), into up to 5 queries, each sent to `/api/get` then `/api/search`;
   then, if nothing was writable, the searches of **Check these**. A 503, garbage or a
   network error is retried after 2, 4 and 8 s (with jitter); then the track is recorded as
   busy.
5. `lyrics/Matcher` ignores records for other songs (names compared without version tags,
   feat., case and accents; a record always needs the artist to match), then: synced within
   3 s, instrumental, plain lyrics within 3 s, synced of another length, plain lyrics of
   another length.
6. `lyrics/LrcFormat` converts LRCLIB's `[mm:ss.xx]` to `[mm:ss.SSS]text` lines (several
   timestamps on a line become several lines; sorted; no header tags; UTF-8 without BOM;
   `\n` endings) and validates the result with Samsung's rules: at least 3 timed lines
   with text, ascending, the last one at most 5 s past the end of the track.
7. First the ledger records the write as begun ("Saving lyrics…", with the text, SHA-256 and
   size of the exact bytes); if that cannot be stored, no file is made. Then the `.lrc` is
   created in one step only if nothing exists at its path (`CREATE_NEW`, that is
   `O_CREAT|O_EXCL`: an existing file or symbolic link makes it fail), written and synced;
   a write that fails halfway removes the partial file. The outcome goes into the ledger
   (`data/Ledger`, a JSON DataStore; a failed ledger write is retried 3 times). If the app
   dies mid-write, the next look at the song (and each listener connect) settles it, by the bytes alone
   (the ledger keeps the text being written until the write is recorded): no file, looked
   up again; the complete file, recorded as written; a regular file holding the first part
   of those bytes (or none), deleted as Lyrify's own partial and looked up again; anything
   else (other content, larger, a link) is kept as "Already had lyrics".

LRCLIB gets one request at a time app-wide, with the User-Agent
`Lyrify/<version> (personal use)` and 15 s timeouts: at least 500 ms apart for HIGH work and
1.5 s for the background sweep. Redirects are not followed and answers over 2 MiB are
refused. A dropped request closes its connection, so it never holds up the next one.

## AI lyrics (v2)

### Supported phones

The AI runs on the phone's NPU (the Hexagon processor of a Snapdragon chip), with models Qualcomm AI Hub built for
that chip. Lyrify reads the chip with `Build.SOC_MANUFACTURER` and `Build.SOC_MODEL` (Android 12 and later;
`models/NpuChips`) and needs Android 15 or later for the overnight run. On any other phone the AI card says "AI lyrics
aren't available on this phone" and nothing is downloaded; LRCLIB lookups, `.lrc` writing, removal and search work as
everywhere.

| Chip | `SOC_MODEL` | AI Hub asset (`qualcomm_…`) | Hexagon | Whisper precision | Zip | Zip SHA-256 | Unpacked | Download (all three models) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Snapdragon 8 Elite Gen 5 (for Galaxy) | SM8850 | `snapdragon_8_elite_gen5_for_galaxy` | v81 | w8a16 | 824,020,794 B | `12c4469b…bb24f16` | 983,166,696 B | 980,617,333 B | **tested** (Galaxy S26 Ultra) |
| Snapdragon 8 Elite (for Galaxy) | SM8750 | `snapdragon_8_elite_for_galaxy` | v79 | w8a16 | 823,685,788 B | `a7d3eb9f…7403af0e` | 981,798,617 B | 980,282,327 B | UNTESTED |
| Snapdragon 8 Gen 3 | SM8650 | `snapdragon_8gen3` | v75 | w8a16 | 823,721,740 B | `7c9f289e…27961398` | 982,003,375 B | 980,318,279 B | UNTESTED |
| Snapdragon 8 Gen 1 | SM8450 | `snapdragon_8gen1` | v69 | w8a16 | 821,903,590 B | `db3cfe3f…ac28460` | 976,301,702 B | 978,500,129 B | UNTESTED |

A model string with a variant suffix (`SM8750-AC`) counts as its part. The full hashes are in `models/NpuChips.kt`.
Every asset is AI Hub's **Whisper-Large-V3-Turbo-Quantized** (w8a16: 8-bit weights, 16-bit activations) release
v0.65.0, `precompiled_qnn_onnx`, built with QAIRT 2.50.0.260828221209 and ONNX Runtime 1.30.0, as listed in
https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized/raw/main/release_assets.json (revision `93014065`,
checked 2026-11-04: every supported chip has a w8a16 build). SM8850's size and SHA-256 are the ones the lyrify-bench
model size test downloaded and ran (`models/npu-variants/whisper_large_v3_turbo_quantized/download.json`); the untested
ones were measured on 2026-11-04 by streaming each zip through `sha256sum` (nothing saved), each size the S3
Content-Length, and their unpacked sizes come from each zip's central directory. Each zip holds one folder with
`encoder.onnx`, `encoder.bin`, `decoder.onnx`, `decoder.bin` and `metadata.json`; the four metadata files list the same
I/O contract and quantization (unit-tested). Only the SM8850 build has run on a phone: the others may fail to load on
their chip, in which case the AI pauses itself (see "Models").

**Why w8a16** (lyrify-bench `results/summary.md`, "Model size test (2026-11-04)"): the set of turbo w8a16, MDX Voc_FT
and wav2vec2 q4 is 0.98 GB to download instead of 2.46 GB for turbo float and wav2vec2 fp32, with the same quality on
the bench's 12 songs: WER 19% / 17% (mean / median) against 20% / 18%, the same 10 songs timed with 71% / 84% of their
lines within 0.5 / 1.0 s, and transcribed segment starts within 1 s of a line 59% of the time against 63%. Its Whisper
NPU time on the phone was 4.8 s per song against 8.3 s.

**Float** stays supported: the engine reads each export's I/O contract from its own `metadata.json`, so a chip given AI
Hub's float build (Whisper-Large-V3-Turbo, float16 I/O, about 2.0 GB zip, 2.2 GB unpacked) runs the same code. No
supported chip needs it today.

### The card

The **AI lyrics** card on the home screen:

- **Your phone's chip**: "Runs on your phone's AI chip (Snapdragon …)", with "Not tested on this chip yet" for the
  untested ones.
- **Models**: downloaded once into app-private `files/models` over an unmetered network (Wi-Fi) only, about 1 GB for
  every supported chip (the card shows the chip's own total, 0.98 GB for the tested one), with **Delete models** to
  free the space (it also turns AI lyrics off).
  - AI Hub's w8a16 Whisper for this chip (about 0.82 GB zip, 0.98 GB unpacked) from
    `qaihub-public-assets.s3.us-west-2.amazonaws.com`.
  - UVR-MDX-NET-Voc_FT (66,762,490 B, SHA-256 `534b2070…ecce111`), the vocal separation model, from UVR's GitHub
    release (`github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx`).
  - wav2vec2-base-960h, Xenova's q4 export `onnx/model_q4.onnx` (89,834,049 B, SHA-256 `337db946…471f9f217`), for
    timing known lyrics, from Hugging Face at the pinned revision `a19f851b`, saved as `wav2vec2-base-960h_q4.onnx`.
    Only its linear layers are 4-bit (74 `com.microsoft:MatMulNBits` nodes, block 32, computed in float32 on the CPU);
    its Convs stay float32. The bench's model size test (part 1) found the same 10 of 12 songs timed with it as with
    the fp32 file it replaces (57% / 71% / 84% of their lines within 0.3 / 0.5 / 1.0 s, fp32 85% within 1 s), and an
    ONNX Runtime 1.30.0 CPU kernel for every op on arm64.

  `models/ModelStore` fetches only from those three sources and follows redirects only to
  `release-assets.githubusercontent.com` (GitHub's release downloads, checked with a HEAD request on 2026-11-03) and
  Hugging Face's CDN under `hf.co`, all over https; everything else is refused. Each file goes into `<name>.part`,
  resumed with HTTP Range after any interruption, and only once its SHA-256 matches the pinned one is it moved into
  place; a mismatch deletes the part and the download fails ("Try again" starts it anew).

  The Whisper zip is downloaded the same way into the app-private cache (`cache/models/whisper-npu.zip.part`), not
  unpacked while it streams: a dropped connection resumes it with a Range request, and its SHA-256 can only be checked
  once all of it is there. Once it matches, its five files are taken out one by one into `files/models/whisper-npu/`,
  each through a `.part` file, its size and SHA-256 recorded as it is written (the zip's CRC-32 is checked too); only
  those five names are written. Their sizes and hashes go into `whisper-npu/manifest.json`, written last, and the zip
  is deleted. The folder counts as in place only when its manifest names the pinned zip and each file has the recorded
  size. Before anything is fetched Lyrify checks the free space: the most the download holds on disk at once, which is
  the Whisper zip and its unpacked files together (the MDX and wav2vec2 files fit in the zip's room once it is
  deleted), plus 200 MB: about 2.0 GB from nothing. What an earlier version left (below) is deleted first and counts as
  free, and so does a Whisper folder whose unpacking was cut short (the download deletes it before unpacking again).
  With less, the card says "Not enough free space for the AI models: they need about … free" and the download
  waits.

  A model that fails to load is deleted (that file, or the whole Whisper folder) and downloaded again, once: when the
  same file (same SHA-256) fails again, it is kept and the AI pauses ("The AI couldn't start on this phone; it's been
  paused" on the card, and in the run log) until the next version of Lyrify or until you delete the models. Each such
  failure also counts as a death of the song in that step (see "Crash guard"). An NPU that cannot be used at all (the
  QNN execution provider offers no NPU device, or this build's ONNX Runtime lacks the internals the w8a16 Whisper's
  tensors need, see "Why reflection") records nothing, deletes no model, ends the run before any song is separated,
  says "The AI couldn't use your phone's AI chip this time" on the card, and is tried again at the next hourly look. The download
  (`models/ModelDownloadWorker`) is a long-running WorkManager worker in a foreground service of type `dataSync`, its
  percentage in a notification. A dropped connection that got further goes on at once; starting the download again
  replaces one under way, which goes on from its parts. Models an earlier version used are deleted from `files/models`
  when the app starts or a download runs: Lyrify 2.0's CPU models (the whisper.cpp Whisper, HT-Demucs), its fp16 and
  fp32 wav2vec2 files, and a Whisper folder unpacked from another zip than the one pinned now (the float Whisper of
  earlier 2.0 builds, 2.2 GB; a folder still being unpacked has no manifest yet and is left alone). So is a verified
  Whisper zip left in the cache once its folder is in place (the app died after writing the manifest, or the zip could
  not be deleted).
- **Switch** (off by default, remembered as you set it): on, it starts the model download if needed and schedules the
  run (a lost schedule is made again when Lyrify opens); off, a run in progress stops after the step it is on. **Delete
  models** waits until that run has stopped and closed its sessions (the button says "Stopping…", at most 5 minutes)
  before it deletes anything.
- **Tonight's progress**: songs done (lyrics added or a final verdict; failed runs do not count) of the queue and the
  song now, "paused while your phone cools down", "starts when your phone is plugged in", or why the phone held it back.
- **Run log**: a long press on the card's title "AI lyrics" shows the last lines of the AI's own log (see below).

### The run

The run (`overnight/AiWorker`) is a WorkManager worker checked every hour while the battery is not low and storage is
not low, and it runs only while the phone is **plugged in** (any charger, cable, wireless or dock), charging or not.
WorkManager's "requires charging" is not used: it means Android's charging state (`ACTION_CHARGING`), which on the phone
came on only 18-19 minutes after plugging in and went off with the cable still in, as it can when the battery stops
charging (for example held at 80-85% by Samsung's "Protect battery"). The worker reads the cable itself
(`EXTRA_PLUGGED`) and the run stops when it comes off. It runs as a long-running worker in a foreground service of type
`mediaProcessing` (Android 15+), which the phone allows 6 hours in 24 (opening Lyrify resets it); at that limit
WorkManager stops it (`onTimeout`) and the AI waits a day or until the app is opened. It goes into the foreground before
it looks at the songs' files.

One song at a time, under a minute each on the tested phone (32-42 s for 4-6 minute songs, thermal status NONE, about
700 MB at peak, lyrify-bench `handoff/npu-full-run1`). The engine's sessions (the QNN execution provider, the MDX,
Whisper encoder and decoder sessions, and wav2vec2's once a song is timed) are created for the first song that runs and
stay loaded until the run ends; creating the MDX session takes about 30 s. Before each song and each Whisper chunk the
run waits while the phone is warmer than "light" (`PowerManager.currentThermalStatus`), and logs how long. Unplugged or
switched off, the engine finishes the step it is on and stops, recording nothing for that song; a later run does it
again from the start (2.0's per-song checkpoints are gone: a song takes under a minute). The phone has to let it start
in the background: if the card says it did not, set Lyrify's battery use to **Unrestricted** (App info › Battery).

**Crash guard** (`overnight/CrashGuard`, app-private `files/ai-crash-guard.json`): while a run is in a step, the song's
record names it; one still named when the next run starts is a run the app died in (a native crash, out of memory). A
song the app died in twice is recorded as "AI failed" (tried again after a day, doubling) instead of being retried
forever. Three deaths in the align step with no song finished in between turn timing known lyrics off (those songs wait,
the rest are transcribed); three in another step pause the AI (the card says so). Both last until the next version of
Lyrify, songs finished meanwhile or not. A song whose audio file was replaced during its run (a music sync) is not
written; a later run does it again.

**Run log** (`overnight/AiRunLog`, app-private `files/ai-run.log`, about 200 KB with one rotated file): each run's start
(cable, battery, temperature, chip, ONNX Runtime and QNN versions, version), each song, step and chunk times, the
sessions' creation times, each Whisper window's token count and stop reason, waits for the heat, stops and why
(unplugged, the system's stop reason, the daily limit), deaths found, and results. Titles, times and counts only: never
lyrics, and of a failure only its kind.

The queue (`overnight/AiNight`, from the ledger): songs with LRCLIB plain lyrics for this recording's length first (they
get timed), then songs LRCLIB has nothing for and the other plain-only ones (transcribed), most recently seen first,
then failed runs due again. Removed, instrumental, settled, written and missing files are skipped, as is any song with a
`.lrc`. The plain lyrics are fetched at that moment by record id (`/api/get/{id}`, the background lane, with the usual
spacing and retries) and never stored. The results go through the same write path as LRCLIB's lyrics: the pipeline
checks the song again under its one-write-per-file rule, marks the write in the ledger, creates the `.lrc` exclusively
after checking it with Samsung's rules, and records the hash and source (AI), so **Remove lyrics** works for AI lyrics
and blocks the AI from that song for good. The engine itself writes nothing. While the AI works on a song, the queue
sweep skips it rather than look it up on LRCLIB.

The ledger moved to schema 2 (AI statuses, `source`, `agreement`): a schema 1 ledger is read as it is, with every
lyrics file it wrote, was writing or the user removed marked as LRCLIB's.

### The engine

`ai/` is the on-device engine, ported from the measured `lyrify-npu-spike` (full stage) and the laptop bench
(`lyrify-bench`). `LyricsFromAudio.run(track, knownPlainLyrics)` on `NpuStages`:

1. decodes the song (MediaCodec) to 44.1 kHz stereo as the separation reads it, a chunk at a time (another rate is
   resampled on the way);
2. separates the vocals with UVR-MDX-NET-Voc_FT on the NPU (`ai/Separation.kt`): STFT and iSTFT on 4 CPU threads with
   the spike's fast mixed-radix FFT, python-audio-separator's "ov25" chunking, the batch fixed to 1 for the HTP. The
   overlap-add keeps one chunk of positions and hands each position on once no later chunk reaches it;
3. makes the 16 kHz stem as the bench does (channel mean, julius' `resample_frac`, the PCM 16 round trip) and the V2
   frames (1 s frames whose stem RMS is over 0.2 x the mix's) as the vocals come out (`VocalStem`), and cuts the stem
   into vocal chunks of up to 30 s; a song with no such frame is an instrumental. Everything is the same, to the bit, as
   with whole arrays (a unit test runs both), but no 44.1 kHz array of the whole song is ever held;
4. transcribes each chunk with AI Hub's Whisper large-v3-turbo on the NPU (`ai/Npu.kt`): log-mel, tokenizer and greedy
   decoding on the CPU, the encoder and decoder on the HTP (burst mode, CPU fallback disabled, so a model runs whole on
   the NPU or not at all), prompt `<|startoftranscript|><|en|><|transcribe|>` with timestamp tokens allowed, no previous
   text, at most 200 tokens, a repetition guard; segments come from the timestamp tokens, shifted by the chunk start.
   The export's I/O contract is read from its own `metadata.json` (`ai/NpuContract.kt`, ported from the spike): sizes,
   element types and the quantization of every tensor; a session whose I/O differs is refused, as is an export of other
   sizes than turbo's. For the w8a16 export the mel is quantized to uint16 as ONNX's `QuantizeLinear` does (round half to
   even, saturated), the attention mask's 0 and -100 become 65535 and 0, the first call's uint8 self caches hold their
   zero point (128), the caches are passed from call to call as the sessions return them, and the uint16 logits are
   dequantized before the argmax; for a float export the same steps use float16;
5. decides vocal or instrumental from what Whisper heard (`VocalGate`): the words of the kept segments (no junk, no
   filler over non-vocal frames) per minute of song, vocal above 9.8. The V2 frame share no longer decides: lead guitar
   and violin leak into Voc_FT's vocal stem (set B: AUC 0.960, leave-one-out 75%), while the word rate over the chunks
   separates set B perfectly (AUC 1.000, leave-one-out 100%; lyrify-bench results/summary.md "Separation test
   (2026-11-03)", `scripts/sep_eval.py`). The bench counted every word; Lyrify leaves junk and fillers out first, so
   "Thank you." over an instrumental does not count. An instrumental is recorded as "AI: instrumental" and nothing is
   written;
6. without known lyrics, each kept segment (no junk, no filler over non-vocal audio) becomes an LRC line at its start
   plus 0.44 s;
7. with LRCLIB's plain lyrics, `PlainLyricsTiming` times them (below).

It returns an `AiResult` and writes nothing; nothing leaves the phone. HTDemucs is not used: it cannot run on the NPU
(QNN `MEM_ALLOC`, and its CPU fallback dies of out-of-memory).

**Why reflection** (`IoTensors` in `ai/Npu.kt`): the w8a16 Whisper takes uint16 inputs (the mel and the attention
mask), and ONNX Runtime's Java API cannot make a uint16 tensor: `OnnxJavaType` stops at int16, and a `ShortBuffer`
becomes an int16 tensor, which a uint16 input refuses. So Lyrify makes it the way every `OnnxTensor.createTensor` does,
through the private native `OnnxTensor.createTensorFromBuffer`, with the uint16 element type, by reflection into ONNX
Runtime 1.30.0's Java classes (the members were checked against `java/src/main/java/ai/onnxruntime` at the v1.30.0
tag; the Android AAR's classes and the JVM build's declare the same ones). The spike ran it on the phone. Safeguards:

- `IoTensorsTest` runs the code on ONNX Runtime 1.30.0's JVM build (a test-only dependency): uint16 and uint8 tensors
  reach a model unsigned, outputs fed back as inputs, a buffer rewritten between runs; `WhisperNpuTest` runs the whole
  w8a16 and float I/O through stand-in models. A test pins the ONNX Runtime
  version, so a version bump fails the build until the members are checked against the new sources.
- `proguard-rules.pro` keeps exactly those members in the shrunk release build (besides the rule that keeps all of
  ONNX Runtime's Java classes).
- It fails closed: when a member is missing or refuses the call, the engine reports the NPU unavailable (checked
  before the QNN EP and any session, so before a song is separated): nothing is recorded, no model is deleted, the
  card says the AI couldn't use the chip, and the next hourly look tries again. It never crashes the app.

**Timing known lyrics** (`ai/PlainLyricsTiming.kt`), the timing re-validation's method "S4 gated by S2c", exactly as
`lyrify-bench/handoff/npu-timing/recommendation.json` (2026-11-03) gives it (`scripts/npu_timing.py`,
`results/summary.md` "NPU timing re-validation"):

- **The line starts (S4)** come from wav2vec2 alone: its emissions over the whole vocal stem (30 s windows every 25 s,
  2.5 s trimmed at the inner edges, joined so frame f is f x 20 ms of the song), then one CTC forced alignment (Viterbi)
  of every lyric line over them; a line starts at its first letter's first frame, no offset. A line with no letter
  (only digits or punctuation) takes the next line's start, the last one the previous line's.
- **The confidence (S2c)** comes from Whisper: its segments place the lines by text (a monotonic dynamic programme over
  beads of up to 16 lines on a segment or a line on up to 4 segments, scored by rapidfuzz's ratio, tau 0.5); groups of
  6 lines are then aligned again in windows around those places (5 s margins, at most 60 s, one more line of context on
  each side) on slices of the same emissions. The confidence is the share of lines where the two starts agree within
  0.5 s. A song is written at 80% or more and with 90% of its line starts rising by at least 0.2 s, else "AI not
  confident". On the bench's 12 songs this accepted 10, with 57% / 71% / 85% of their lines within 0.3 / 0.5 / 1 s.
- **Long songs** (Lyrify's own rule; the recommendation's fallback, "use the S2c times and keep the gate", cannot keep
  a gate that needs S4): the Viterbi keeps one byte per frame and letter state, up to 64 MB of them at once (the
  bench's largest song needs 56 MB). A longer song's table is made a block of frames at a time from saved scores: the
  same path, bit for bit, for one more pass of arithmetic, so long songs are timed and gated the same way. Past 1 G
  cells (a 30-minute track with thousands of words) the song is not timed ("AI not confident"); the run log notes the
  cell count, the frames and the number of lines.

**Transcribing** (no known lyrics): each kept Whisper segment becomes an LRC line at its start plus 0.44 s (segment
starts run early: the bench's median).

**Memory** (`AiMemory`): what grows with a song is its 16 kHz stem, wav2vec2's emissions and, timing lyrics, the
aligner's table (at most 64 MB). The Java heap's peak is about 92 MB for a 6-minute song, 119 MB for 12 minutes and
156 MB for 20 minutes, plus the rest of the app. Before a song starts, its length is checked against the heap
(`Runtime.maxMemory()`, 512 MB on the tested phone with `largeHeap`): a song that would need more than 80% of it, or is
over 20 minutes (the decoder's limit), is not run and is recorded as "AI not confident" with the reason `TOO_LONG` in
the ledger (`aiReason`, as for every rejection), so a later version can try those songs again.

The pure parts are unit tested with golden fixtures: the spike's (log-mel against transformers, the tokenizer, the
STFT, iSTFT and MDX demix against numpy and the bench's `sep_mdx.py`, julius' resampler, the gate and chunks, the
Whisper I/O contract on every chip's float and w8a16 `metadata.json`, the quantize and dequantize helpers, the mask
values, the uint16 and uint8 tensors on ONNX Runtime's JVM build), `WhisperNpu` itself on ONNX Runtime's JVM build for
both precisions with stand-in encoder and decoder models of the exports' exact I/O (`tools/whisper_io_models.py`: their
outputs show the mel's encoding, the caches starting at their zero point and passed on, the mask, and the logits
dequantized before the argmax), the
bench's (CTC Viterbi, windows, gate and chunks), S1 against the bench's own `s1_starts` on synthetic text
(`tools/s1_golden.py`), and the whole timing method (S4, S2c, the agreement, the verdict and the whole-song window cuts)
against the bench's `npu_timing.py` and `npu_timing_report.py` on synthetic text and emissions (`tools/timing_golden.py`).
A test pins every timing number to a copy of `recommendation.json`. No fixture holds real lyrics.

Debug builds can run the engine on one song from adb (Android 15 or later). Push the models to shared storage first
(Lyrify reads it with its all-files access); `whisper-npu` holds the five files of this phone's AI Hub zip:

```sh
D=/sdcard/Download/lyrify-models
adb shell mkdir -p $D/whisper-npu
adb push encoder.onnx encoder.bin decoder.onnx decoder.bin metadata.json $D/whisper-npu/
adb push UVR-MDX-NET-Voc_FT.onnx $D/
adb push model_q4.onnx $D/wav2vec2-base-960h_q4.onnx   # Xenova's q4, only for timing known lyrics
adb shell am broadcast --allow-fgs-start-reason 316 -n app.lyrify/.ai.AiDebugReceiver --es models $D \
    --es path "/storage/emulated/0/Music/Song.mp3" [--es lyrics /sdcard/Download/plain.txt] [--ei threads 4]
adb logcat -s Lyrify
```

The receiver starts a debug-only foreground service (type mediaProcessing) that runs the engine, logs each stage's time
and the resulting LRC's line times and lengths (never the lyrics), and writes no file. `--allow-fgs-start-reason 316` lets the app start that service from the
background. A run takes under a minute plus about 30 s to create the sessions; open Lyrify once after installing (a
stopped app gets no broadcasts). The debug run writes nothing to the AI's run log.

## Verbose logging

`adb -s <serial> logcat -s Lyrify` shows the session followed, each track's outcome,
retries and write problems. For more:

```sh
adb -s <serial> shell setprop log.tag.Lyrify VERBOSE   # on
adb -s <serial> shell setprop log.tag.Lyrify ""        # off
```

Verbose output logs what Samsung Music puts in its session (metadata keys, media id, the
active queue item and the next items) and the queue items skipped as too generic.

## Samsung Music facts (16.2.45.1)

- Sidecar path: the audio path cut at the last `.`, plus `.lrc` (lowercase), same folder.
  The audio must be in MediaStore; the `.lrc` needs no media scan.
- A timed line must start with `[` (a BOM or a leading space breaks it). The bracket is
  parsed with `SimpleDateFormat("mm:ss.SS")`: the fraction is raw milliseconds (`.13` is
  13 ms, so Lyrify always writes three digits), `[mm:ss]` or a comma fraction drops the
  line, only the first bracket of a line counts, header tags are skipped, lines with equal
  times merge, and times must ascend (binary search).
- A file with no timed lines parses to zero lines, is cached as "has lyrics", hides the
  embedded lyrics tag and shows nothing until Samsung Music is force-stopped. A changed
  `.lrc` is not re-read until a force-stop either, so a bad first write sticks: hence no
  overwrites and the validation.
- Encoding: the first charset that decodes strictly, UTF-8 first. Lyrify writes UTF-8
  without BOM.
- Lyrics load when a track's metadata is set, not when the lyrics view opens. A missing file
  is not cached. A song loaded before its `.lrc` exists keeps showing its embedded (plain)
  lyrics until Samsung Music restarts: another reason to fetch the whole queue early.
- Samsung's `METADATA_KEY_MEDIA_ID` and queue item media ids are its own ids, not
  MediaStore `_ID`. Queue items carry title and artist only.

## Known limitations

- Files are matched to queue items by title and artist (queue items have no duration).
  Every matching copy gets its own lyrics; more than 4 files with one name are skipped.
- Only lyrics timed for a recording of the same length are written; about a third of the
  library is expected to get none (obscure tracks, instrumentals, plain-only lyrics).
- Lyrics written while a song plays appear the next time it loads.
- Removing lyrics only stops Lyrify from adding them again; Samsung Music may keep showing
  them until it restarts.

## Manual test checklist

- [ ] Fresh install: setup screen; both switches lead to the right Settings screens.
- [ ] With both on, the home screen shows; playing a song shows it under Now playing.
- [ ] A song with lyrics on LRCLIB gets "Synced lyrics added"; the `.lrc` sits next to it
      and the audio file's size and date are unchanged.
- [ ] The next songs in the queue get their statuses within a minute or so; "Queue: X of N
      checked" climbs to N in the background.
- [ ] A song in two folders gets a `.lrc` next to each copy.
- [ ] **Wrong lyrics** on a song Lyrify wrote removes only its `.lrc` (after confirming);
      the song shows "Removed by you" and does not get lyrics again.
- [ ] Search "motorhead" finds Motörhead; status chips and counters filter the list.
- [ ] A song without an artist tag ("05- Song.mp3") shows up under "N songs need a quick
      check"; **Show lyrics** shows a few lines; **This one** adds synced lyrics (or "Picked." for
      plain ones, worded by whether AI lyrics are on); **None of these** makes it "Not found"; **Search myself** finds
      a song by your own words.
- [ ] Skip to a prefetched song: Samsung Music shows synced lyrics that follow the song.
- [ ] A song that already had a `.lrc` shows "Already had lyrics"; the file is unchanged.
- [ ] Airplane mode: tracks become "LRCLIB busy, will retry"; back online, **Retry failed
      now** fills them in.
- [ ] Reboot: the listener reconnects without setup.
- [ ] AI lyrics: the card says "Runs on your phone's AI chip (Snapdragon 8 Elite Gen 5)". Switch on
      over Wi-Fi; the models download with a climbing percentage (also in a notification), survive
      turning Wi-Fi off and on (they resume), unpack, and show "ready (0.98 GB)"; the cache holds no zip.
- [ ] AI lyrics after updating from a 2.0 build that had downloaded the 2.46 GB float set: on opening Lyrify the old
      Whisper folder and `wav2vec2-base-960h.onnx` are gone from `files/models` (the MDX file stays), the card asks
      for the rest (about 0.91 GB of the 0.98 GB), and it says space is short only when it really is.
- [ ] Plugged in at night with the screen off: the notification shows the song and "0 of N done", and by
      morning songs have "AI lyrics added" or "AI-timed lyrics added", their `.lrc` next to
      them, the audio files unchanged.
- [ ] Unplug mid-song: the run stops within seconds, the song keeps its old status; plugged in
      again, the run does that song again.
- [ ] Plugged in while "Protect battery" holds the charge at 80-85%: the run still starts.
- [ ] Long press on "AI lyrics": the run log shows the night (no lyrics in it).
- [ ] **Remove lyrics** on an AI song deletes its `.lrc`; it shows "Removed by you" and the AI
      never takes it again.
- [ ] **Delete models** frees the space and turns the switch off; during a run it says "Stopping…"
      until the run has stopped.
