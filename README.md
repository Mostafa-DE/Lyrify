# Lyrify

Lyrify makes Samsung Music show synced lyrics for the songs on your phone. It watches what
Samsung Music is playing, looks the song up on [LRCLIB](https://lrclib.net) and saves a
`.lrc` file next to the audio file: for the current song and the next 5 in the queue first,
then for the rest of the queue in the background (shuffle can play any of them next), so the
lyrics are there before each song starts.

A personal app for a Samsung Galaxy (One UI, Samsung Music 16), installed with adb, never
published.

- Only **creates** `.lrc` files. It never changes, moves, renames or deletes an audio file,
  never writes tags, and never overwrites a `.lrc` that is already there (yours or its own).
- It deletes exactly three kinds of file: a partial `.lrc` the same write just created (when
  the write fails halfway); when you tap **Wrong lyrics** / **Remove lyrics** and confirm, a
  `.lrc` Lyrify wrote whose bytes still match the SHA-256 it recorded; and the partial `.lrc`
  of a write of its own that the app's death cut short (see "How it works", step 7). Never
  anything else.
- Only lyrics timed for **this exact recording** are written: LRCLIB's duration must be
  within 3 s of the file's, and every file is checked with a model of Samsung Music's
  parser before it is written, so Samsung never ends up with an empty lyrics file.
- Plain (untimed) lyrics, other versions and songs LRCLIB does not know are only recorded,
  not written. Plain lyrics keep their LRCLIB id for a later version that times them.
- A song with no artist in its tags (and no "Artist - Title" title) is not looked up: a
  title alone could match anyone's song.
- Requests to LRCLIB carry artist, title, album and duration only. Nothing else leaves the
  phone. The record of what was done stays in an app-private file and is not backed up.

## Build

Requirements: JDK 17 or newer (tested with JDK 21) and the Android SDK with
platform `android-37.0` and build-tools 36.0.0.

```sh
cd Lyrify
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME is not set
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Coverage report for the unit tests (JaCoCo, built into AGP):

```sh
./gradlew createDebugUnitTestCoverageReport
# app/build/reports/coverage/test/debug/index.html
```

Toolchain: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, Kotlin 2.4.21 with the
serialization plugin, Compose BOM 2026.09.00 (Material 3), DataStore 1.3.0,
kotlinx.serialization 1.12.0, kotlinx.coroutines 1.11.0. compileSdk 37, targetSdk 36,
minSdk 30. No networking library: `HttpURLConnection`.

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
| No artist in the song's tags (No artist) | no artist to look up | after 30 days |
| Removed by you (Removed) | you removed lyrics Lyrify wrote | never |
| Saving lyrics… (Saving) | a write in progress (only seen later if the app died during it) | settled the next time the song is seen |

**Retry failed now** puts every "LRCLIB busy" track at the front of the background sweep
(so the current song still goes first, but a paused sweep does not hold it up); it needs
the listener connected. While the listener
is connected, queue items whose retry is due are also put back in the background sweep every
30 minutes. A track is otherwise looked up when it is in Samsung Music's queue (again
whenever the queue changes, which costs nothing for tracks already done).

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
`data/Ledger`, `media/SessionTracks`, `media/QueueSweep` and `ui/SongFilter`.

1. `media/NowPlayingListener` (a notification listener, bound by the system, no foreground
   service) follows the media session of `com.sec.android.app.music`. Every session
   callback reads a snapshot (current track, active queue item, queue); only a snapshot
   that differs from the last one counts, after 1 s without further change.
2. `media/QueueSweep`, one worker with two lanes: HIGH is the current track and the 5 queue
   items after the active one (`PlaybackState.activeQueueItemId`), LOW the rest of the
   queue from there, wrapping round ("Retry failed now" tracks go first in LOW). HIGH always
   goes first; a LOW item in progress is dropped when HIGH work arrives and redone later. A
   new current track replaces the pending HIGH lane and drops the HIGH item in progress
   (even mid-backoff) unless it is still the current song or one of the next 5; a write
   already under way always finishes. A new queue replaces the LOW lane. After 3 LOW items
   in a row found LRCLIB busy, LOW pauses for 2 minutes ("paused, LRCLIB busy") while HIGH
   keeps going. An item that fails unexpectedly is logged and skipped.
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
   unbalanced brackets, uploader handles) into up to 5 queries, each sent to `/api/get`
   then `/api/search`. A 503, garbage or a network error is retried after 2, 4 and 8 s
   (with jitter); then the track is recorded as busy.
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
- [ ] Skip to a prefetched song: Samsung Music shows synced lyrics that follow the song.
- [ ] A song that already had a `.lrc` shows "Already had lyrics"; the file is unchanged.
- [ ] Airplane mode: tracks become "LRCLIB busy, will retry"; back online, **Retry failed
      now** fills them in.
- [ ] Reboot: the listener reconnects without setup.
