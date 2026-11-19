# VidLab

**A video player and media library manager for Android—with built-in WebDAV, embedded Rclone, automatic metadata, and multi-tracker sync.**

VidLab brings a rich, organized home-theater catalog to both your device storage and your remote servers. Use it right out of the box as a sleek local media player (like Nova Video Player), or connect your WebDAV shares and `rclone.conf` remotes to stream, browse, and download your entire cloud library without needing a separate server like Plex or Jellyfin.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="https://github.com/user-attachments/assets/8b745edf-ee99-49b6-a981-c5ec057b1c98" alt="VidLab Catalog" /></td>
    <td align="center"><img src="https://github.com/user-attachments/assets/9f99b494-a787-4b77-8eeb-2152b9e4307a" alt="VidLab Media Details" /></td>
    <td align="center"><img src="https://github.com/user-attachments/assets/c756710f-4ebd-420c-9421-e5110863afb5" alt="VidLab Player & Sources" /></td>
  </tr>
</table>

---

## Core Features

### 1. Built-in WebDAV & Embedded Rclone Engine
- **Direct WebDAV Streaming**: Connect any WebDAV server (NAS, seedbox, Nextcloud, OwnCloud, or local network share) and stream movies and multi-season series directly with full artwork, metadata, and resume tracking.
- **Native Embedded Rclone**: Import any `rclone.conf` file directly into VidLab to stream from rclone-supported cloud backends—including encrypted (`crypt`) remotes—via a local loopback WebDAV bridge (`127.0.0.1`), with no Termux scripts or external apps required. *(Note: VidLab does not include built-in one-click Google Drive OAuth login; Google Drive and other cloud remotes work by importing your existing `rclone.conf`.)*
- **Bulk Offline Downloads**: Save single episodes, full seasons, all unwatched episodes, or an entire series from WebDAV/Rclone remotes straight to `Downloads/VidLab` for offline playback.

### 2. Zero-Setup Local Media Center
- **Full Internal Storage & Folder Scanning**: Works immediately on install—scans your internal storage (or a dedicated `/VidLab` folder) and organizes movies and TV shows into a clean poster grid.
- **Automatic Metadata & Artwork**: Enriches both local and remote files with posters, backdrops, plot summaries, ratings, release years, genres, episode thumbnails, and cast/crew profiles.
- **Interactive Cast & Crew**: Tap any actor or director to view every movie and series in your library they appear in.
- **Quality Badges & Missing Episode Detection**: Displays `4K`, `1080p`, `720p`, `HDR`, and `10-bit` badges parsed from filenames and highlights missing episode numbers inside a season.

### 3. Media3 (ExoPlayer) + FFmpeg Playback & Simkl Sync
- **Broad Format & Codec Support**: Powered by AndroidX Media3 (ExoPlayer) with FFmpeg extensions for smooth hardware and software decoding.
- **Touch Gestures & Smart Tracks**: Swipe vertically for brightness and volume, swipe horizontally to seek, skip intros, load external subtitles, and default automatically to English audio/subtitle tracks.
- **Watch Progress & Simkl Scrobbling**: Tracks resume positions locally with swipe-to-mark-watched gestures and optional Simkl account sync.

---

## Getting Started

1. Download the latest APK from **[Releases](https://github.com/lolzz11/VidLab/releases)** and install it on Android.
2. **For Local Playback**: Grant storage access when prompted and start watching immediately.
3. **For WebDAV / Rclone**: Open the side drawer (`Source`), switch to **WebDAV** or **Rclone**, enter your server details or import your `rclone.conf`, and browse your remote library.

---

## Tech Stack

- **UI & Architecture**: Kotlin, Jetpack Compose, Material 3, Coil
- **Playback Engine**: AndroidX Media3 (ExoPlayer) + FFmpeg extension
- **Cloud & Sync**: Embedded `rclone` (`librclone.so`) loopback WebDAV server, Simkl API

---

## License

VidLab is free software licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for details.
