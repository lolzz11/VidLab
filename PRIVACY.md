# Privacy Policy for VidLab

**Last Updated:** November 2026

VidLab is an open-source, local-first video player and media library manager for Android licensed under GPL-3.0. Your privacy is a core design principle: VidLab does not operate backend servers, does not collect telemetry or analytics, and does not sell or share personal data.

## 1. Data Storage & Processing
- **Local Storage Only:** All watch history, playback progress, app preferences, and library metadata caches are stored locally on your Android device inside the app's private storage.
- **Media Files & WebDAV:** Media files accessed from local device storage or user-configured WebDAV/Rclone endpoints are streamed directly to your device. VidLab developers never have access to your media files, server URLs, or credentials.

## 2. Third-Party Integrations (Optional)
If you explicitly choose to connect optional third-party services, VidLab communicates directly from your device to those services using their official APIs:
- **Trakt.tv, Simkl, and AniList:** Used solely to authenticate your account via OAuth and scrobble or sync your playback progress when enabled. OAuth access tokens are stored locally on your device and can be revoked or cleared at any time via **Sign Out** in the app's Tracking drawer or from your account settings on each platform.
- **TMDB / AniSkip / TheIntroDB:** Used solely to fetch public poster artwork, media descriptions, and chapter/intro timestamps based on media filenames.

## 3. Data Deletion
You can delete all locally stored tokens, preferences, and watch history at any time by signing out of connected trackers inside VidLab, clearing the app's storage in Android Settings, or uninstalling the application.

## 4. Contact & Source Code
VidLab is open-source and auditable at:  
https://github.com/lolzz11/VidLab
