# ClipForge for Android (v6.1)

Automatic highlight clipper and video saver. Give it a video; it finds the moments most likely to hook people
and cuts each one into its own MP4. Also saves YouTube / Shorts / Instagram videos straight to your gallery.

Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer + Transformer), Room, yt-dlp + ffmpeg
(youtubedl-android), Vosk (on-device speech recognition). Requires Android 10 (API 29) or newer.

## Functions

### Get a video in
- **YouTube link**: paste a video or Short link, preview it, then download it into the app.
- **From this phone**: pick any video file from your storage.
- **Settings per project**: clip length range (default 15-45 s) and how many clips (Auto or a set number).

### Clip finder (runs fully on the phone, in the background)
- **Sound analysis**: loudness, build-up vs the last 30 s, shouting / hype, sharp hits.
- **Speech-aware**: tells talking from music and noise, and notices emphasis when a speaker gets louder or sharper.
- **Reveal detection**: a quiet beat followed by a loud burst (punchlines, twists, drops).
- **Reaction detection**: laughter, applause and crowd roars.
- **Picture analysis**: fast action, scene cuts and flashes.
- **Transcript (optional, free, offline, English)**: Vosk turns speech into words. Used to find hook lines
  ("no way", "secret", questions, emotional lines), and to avoid dead air and "um/uh" stretches.
  The ~40 MB English model downloads once on first analysis. Skipped automatically for music, other languages,
  or if the download fails.
- **Viral-style scoring**: every possible clip is scored on its first 4 seconds (the hook), sustained interest,
  payoff after the opening, and a real build-up, with a sweet-spot length.
- **Better cuts**: clips start where a new thought begins and end on a finished thought (never on "and", "the",
  "so"), weak lead-in sentences are trimmed, and cuts snap to pauses when there is no transcript.
- **Smart titles and labels**: for example `Hook line: "nobody tells you this about..."`, plus a category
  (Emphatic talk, Big reveal, Crowd / laugh reaction, Hook line, Sudden build-up, Shout / hype, Big hit,
  Visual spike, High-energy scene) and a 1-99 score.

### Review and export
- **Full-screen clip preview**: swipe or use arrows between clips, loops the clip on screen.
- **Crop preview and export**: Original, 9:16 (vertical) or 1:1 (square) centre crop.
- **Select or delete clips** before exporting.
- **Export to gallery**: saved to `Movies/ClipForge`. Export keeps running in the background.

### Video editor (CapCut-style)
Open it from **Edit** on any clip's full-screen preview, or **Open the video editor** on the home screen.
- **Timeline**: Split at the playhead, Delete, Trim (range slider), Move left/right, tap a block to select it.
- **Per piece**: Speed (0.5x to 3x) and Volume (0 to 200%).
- **Canvas**: 9:16 (TikTok), 1:1, 16:9 or Original. The **whole video is fitted inside the frame** with bars
  around it, exactly like CapCut. Nothing is cropped (preview and export match). Default is 9:16.
- **Text**: add text at the playhead (top / middle / bottom, 3 colours), delete anytime.
- **Auto subtitles** (the main feature of the editor, see below).
- **Export**: renders everything into one MP4 in the gallery (`Movies/ClipForge`), in the background with a
  notification. Not yet in the editor: filters, music, transitions, stickers.

### Auto subtitles
Open the editor, tap **Captions**, pick the spoken language and tap **Generate subtitles**. Speech recognition runs on the
phone (Vosk, offline). Each language downloads its model once. Subtitles follow your cuts and speed changes.

- **12 styles**, each shown as a live picture you can tap: Hormozi, Beast, Karaoke, Marker, Sticker, Sunset, Neon,
  One Word, Classic, Minimal, Elegant, Typewriter. Outlines, shadows, glow, gradient text, pill / marker boxes, and a pop
  animation on the word being said.
- **Adjust** (every style): size, position (top / middle / bottom or anywhere between), words per line (Auto, 1 to 5),
  spoken-word colour, word-pop on / off, and colouring of key words (hook words, strong feelings, numbers).
- **Text tab**: every subtitle line with its time. Tap a line to jump there, tap the pencil to fix a wrong word or delete
  the line. Fixes show in the preview and in the export.
- **Languages**: English, English (accurate, 128 MB), English (India), Hindi, Spanish, French, German, Portuguese,
  Italian, Russian, Turkish.
- **Smarter lines**: capital letters on "I" and sentence starts, lines never end on words like "the" / "and" / "to", and a
  line stays up until the next one starts, so nothing flickers.
- **Save subtitles file (.srt)** to `Downloads/ClipForge`, for use in any other player or editor.
- The preview and the export use the same drawing code, so the saved video matches what you saw.


The old Original / 9:16 / 1:1 export on the results screen now also **fits** the whole picture instead of cropping
(a 16:9 option was added).

### Download tab (YouTube / Shorts / Instagram)
- Paste a YouTube video, YouTube Short or Instagram reel/post link and tap **Download**.
- Saves the original video (up to 1080p, audio and video merged with ffmpeg) to `Movies/ClipForge Downloads`.
- No watermark is added. Runs in the background with a progress notification; cancel any time.
- The downloader engine updates itself and retries once if a download fails.
- Instagram may block downloads or need a login; public reels work most of the time. Private posts do not work.
- Only save videos you own or have permission to save.

### Projects and continuing
- **My Projects**: every analysed video with its clips, kept until you delete it.
- **Continue tab**: projects still analysing, analysed but never exported, or interrupted. Resume, **Retry**
  (re-analyses from the saved video) or **Dismiss**.
- **Background work**: analysis, YouTube downloads, saving downloads and export run in a foreground service with a
  notification, wake lock and "ready / saved" notifications. The app asks once to be exempt from battery
  optimisation so phones that kill background apps leave it alone.

### Look
- iOS-style frosted-glass interface over a glowing dark backdrop.
- App icon, in-app logo and notification icon from the ClipForge logo (with themed-icon support on Android 13+).

## Get the APK (no Android Studio needed)
1. Push this folder to a GitHub repo on branch `main`.
2. Repo, **Actions** tab, latest **Build Android APK** run, download **app-debug-apk**, unzip, install.
   (Allow "install unknown apps". The APK is large because it bundles Python, yt-dlp and ffmpeg.)
3. If the build fails, open the failed run and copy the error. Version pins in `build.gradle.kts` /
   `app/build.gradle.kts` (youtubedl-android, ffmpeg, Vosk) are the most likely cause.

## Limits to know
- The score is a relative signal strength, not a prediction of virality. Category labels describe the signal,
  not meaning. Speech recognition is good, not perfect: no punctuation, and names and fast speech get mistakes. Fix them in the Text tab. The highlight finder only uses the English transcript.
- Vertical / square / wide export fits the whole picture with bars; there is no blurred-background option yet.
- Each project keeps a copy of its source video, so large videos use phone storage.
- YouTube and Instagram change often. If downloads stop working, update the youtubedl-android version.

## Status
Written without access to an Android SDK or device, so **it has not been compiled or run**. The first CI build may
need small fixes.
