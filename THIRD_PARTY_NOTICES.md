# Third-Party Licenses & API Attributions

VidLab incorporates open-source libraries and integrates with external metadata and watch-tracking APIs.

## Open-Source Components

### 1. FFmpeg (via AndroidX Media3 FFmpeg Extension)
- **License:** GNU Lesser General Public License (LGPL) version 2.1 or later
- **Notice:** This software uses libraries from the FFmpeg project (`libffmpegJNI.so`) licensed under the LGPLv2.1+.
- **Upstream Source:** https://ffmpeg.org/download.html & https://github.com/androidx/media

### 2. Rclone
- **License:** MIT License
- **Copyright:** Copyright (c) 2012-2026 Nick Craig-Wood
- **Notice:** VidLab bundles an unmodified `rclone` binary (`librclone.so`) used as a local loopback WebDAV bridge for user-configured remotes.
- **Upstream Source:** https://github.com/rclone/rclone

### 3. AndroidX, Jetpack Compose, Media3 & Coil
- **License:** Apache License, Version 2.0
- **Upstream Source:** https://source.android.com/ & https://github.com/coil-kt/coil

---

## Metadata & Tracking API Attributions

- **TMDB (The Movie Database):** This product uses the TMDB API but is not endorsed or certified by TMDB. (https://www.themoviedb.org/)
- **Trakt:** This product uses Trakt but is not endorsed or certified by Trakt. (https://trakt.tv/)
- **Simkl:** Watch history and catalog synchronization powered by the Simkl API. (https://simkl.com/)
- **AniList:** Anime metadata and progress synchronization powered by the AniList GraphQL API. (https://anilist.co/)
- **AniSkip & TheIntroDB:** Community intro/outro timestamp markers powered by AniSkip and TheIntroDB.
