# VidLab

**A modern, Netflix-style local video player and media organizer for Android.**

VidLab turns the video files on your phone or tablet into a rich, organized media library—much like Nova Video Player, built with a sleek Material 3 interface. Just point it at your device storage and it automatically matches your movies and TV shows with posters, backdrops, synopses, ratings, episode thumbnails, and cast profiles.

---

## Why VidLab?

Most mobile video players just give you a plain list of folders and filenames. VidLab organizes your collection into a proper home theater catalog right out of the box—no server setup or configuration required.

### 1. Local Media Center & Automatic Metadata
- **Full Internal Storage & Folder Scanning**: Automatically scans your device storage (or a dedicated `/VidLab` folder) and groups multi-season TV shows and movies cleanly.
- **Rich Artwork & Details**: Fetches posters, backdrops, plot summaries, release years, genres, ratings, and episode-by-episode descriptions.
- **Interactive Cast & Crew**: Tap any actor or director to see every movie and series in your library they appear in.
- **Quality & Resolution Badges**: Automatically parses filenames to display `4K`, `1080p`, `720p`, `HDR`, and `10-bit` tags at a glance.
- **Missing Episode Detection**: Spots gaps in a season's episode numbering right inside the episode list.

### 2. Powerful Playback (Media3 ExoPlayer + FFmpeg)
- **Broad Format & Codec Support**: Powered by AndroidX Media3 (ExoPlayer) with FFmpeg extensions for smooth hardware and software decoding.
- **Intuitive Touch Gestures**: Swipe vertically for brightness and volume, swipe horizontally to seek, and enjoy intro-skipping support.
- **Smart Audio & Subtitles**: Automatically prefers English audio and subtitle tracks, with support for loading external subtitle files.

### 3. Watch Tracking & Simkl Sync
- **Automatic Resume & Progress**: Remembers where you left off across movies and episodes, with horizontal swipe gestures to quickly mark episodes watched or unwatched.
- **Optional Simkl Scrobbling**: Connect your Simkl account to sync your watch history and progress automatically.

---

## Advanced: WebDAV & Rclone Support

Once you are comfortable with VidLab as your daily local player, you can optionally connect remote storage without needing a separate app:

- **Direct WebDAV Streaming**: Connect any standard WebDAV server (NAS, seedbox, Nextcloud, OwnCloud, or local network share) and stream your library directly with full metadata matching and offline download support.
- **Built-in Rclone Engine**: Power users can import an existing `rclone.conf` file to mount and stream from any rclone-compatible backend or encrypted (`crypt`) remote over a local loopback server (`127.0.0.1`). *(Note: VidLab does not include built-in one-click Google Drive OAuth login; cloud remotes are configured via standard WebDAV URLs or your own `rclone.conf`.)*
- **Offline Downloads**: Save individual episodes, full seasons, unwatched episodes, or entire series from WebDAV/Rclone sources directly to `Downloads/VidLab` for offline viewing.

---

## Getting Started

1. Download the latest APK from the **[Releases](https://github.com/lolzz11/VidLab/releases)** page and install it on your Android device.
2. Grant storage access when prompted so VidLab can scan your local video folders.
3. Open the side drawer (`Source`) if you want to switch between **Local**, **WebDAV**, or **Rclone**, or connect **Simkl** under `Settings`.

---

## Tech Stack

- **UI & Architecture**: Kotlin, Jetpack Compose, Material 3, Coil
- **Playback Engine**: AndroidX Media3 (ExoPlayer) + FFmpeg extension
- **Remote & Sync**: Embedded `rclone` loopback WebDAV bridge, Simkl API

https://github.com/user-attachments/assets/8b745edf-ee99-49b6-a981-c5ec057b1c98

https://github.com/user-attachments/assets/9f99b494-a787-4b77-8eeb-2152b9e4307a

https://github.com/user-attachments/assets/c756710f-4ebd-420c-9421-e5110863afb5
