# 浮音 FloatMusic

[简体中文](README.md) | [English](README_en.md)

**Keep music close at hand.** FloatMusic is a floating music player for Windows and Android. It rests as a draggable music-note icon; clicking it opens a player card so you can control playback, read lyrics, and change tracks while using other apps.

Current version: **1.1 · Windows and Android preview**, accepted by the user. Windows is `1.1.0-preview.2`; Android is `1.1.0-preview.1`, with version code 15. Source code and packages are available in the [1.1 Release](https://github.com/chensiray/FloatMusic/releases/tag/v1.1.0-preview).

Version 1.1 saves quality preferences separately for all three libraries and adds QQ 320 / FLAC / experimental master and Kuwo 320 / FLAC, with lower-quality fallback when a tier is unavailable. Volume is now a button in the playback controls that opens a vertical slider upward. Lyric font size spans 10–30, with adjustable 0.8–3.0× line spacing, a default of 1×, live previews, and saved preferences. Settings are grouped by function. Tracks from different platforms and local files can still share playlists; multi-platform playlists, rankings, and the search result limit remain available. See the [Windows 1.1 acceptance guide](docs/Windows-1.1-验收说明.md) and [Android 1.1 acceptance guide](docs/Android-1.1-验收说明.md).

## Main features

- **Local music**: Import MP3, WAV, FLAC, OGG, M4A, and AAC files, up to 30 MiB per track. Windows also accepts files dragged onto the window or icon.
- **Online search**: Select one or more of NetEase Cloud Music, QQ Music, and Kuwo Music for both song and playlist searches. Results show their source, and library selections are saved. If one library fails, results from the others remain available. Settings control the combined result limit; results alternate among selected libraries, and fewer are shown if not enough matches exist.
- **Music rankings**: Switch among the three platforms, browse songs, preview tracks, or import an entire ranking into a local playlist. QQ loads its ranking directory dynamically; Kuwo offers verified hot, new, and rising charts, with tracks fetched live.
- **Backup audio sources**: Built-in NetEase playback prefers GD Studio and tries other sources after URL, connection, or decoding failures. Settings show the actual source and audio format.
- **Independent quality**: NetEase offers standard through Hi-Res; QQ offers standard / 320 / FLAC / experimental master; Kuwo offers standard / 320 / FLAC. Each preference is saved separately. Unavailable tiers fall back to lower tiers for the same track, and actual audio specifications are displayed.
- **Playback controls**: Play, pause, seek, previous/next track, volume, and audio output selection. The volume button opens a vertical slider upward. Sequential, repeat-all, repeat-one, and shuffle modes are supported. Restarting restores the track and position in a paused state.
- **Lyrics**: Choose original, translation, or bilingual lyrics in settings, adjust font size from 10–30 and line spacing from 0.8–3.0×, and preview and save changes. Timed lyrics highlight and slightly enlarge the current line; after manual scrolling, an arrow returns to it. Each track can have its own timing adjustment.
- **Playlists and favorites**: Manage local playlists and the built-in Favorites playlist, edit descriptions, reorder by dragging, move / copy / remove multiple selections, and import/export JSON. Both platforms support mixed playlists containing all three online sources and local music.
- **Appearance**: Light, dark, or system theme; draggable floating cards with proportional scaling and background opacity controls.

## Download and run

This repository contains source code. **Code → Download ZIP downloads source code, not an installable package.** Download the package for your platform from the [1.1 Release](https://github.com/chensiray/FloatMusic/releases/tag/v1.1.0-preview):

| Platform | Download | How to use |
|---|---|---|
| Windows x64 | [Complete runtime ZIP](https://github.com/chensiray/FloatMusic/releases/download/v1.1.0-preview/FloatMusic-1.1.0-preview.2-windows-x64.zip) | Extract the complete directory and run `FloatMusic.exe`; keep all DLLs and plugins |
| Android ARM64 | [APK installer](https://github.com/chensiray/FloatMusic/releases/download/v1.1.0-preview/FloatMusic-1.1.0-preview.1-android-arm64.apk) | Android 9 or later; an older build with the same signature can be updated in place |
| File checksums | [SHA256SUMS.txt](https://github.com/chensiray/FloatMusic/releases/download/v1.1.0-preview/SHA256SUMS.txt) | Check the downloaded files' SHA256 hashes |

Other versions are listed under [Releases](https://github.com/chensiray/FloatMusic/releases). To compile the app yourself, see the [build guide](docs/DEVELOPMENT.md).

The main environments used are Windows 11 x64 and Android ARM64. Android 9 is the minimum supported Android version. Compatibility may vary with other OS versions, devices, and audio codecs.

### Windows

1. Extract the complete runtime package and run `FloatMusic.exe`. Do not move the EXE alone; keep its DLLs and plugins in the same directory.
2. Click the blue music-note icon to open the player card.
3. Select **More → Import music (更多 → 导入音乐)** or **More → Search music (更多 → 搜索音乐)** and choose a track.

Launching Windows again brings the existing window forward. Both the icon and card accept a single dragged audio file.

Before upgrading, close the old app through **More → Exit FloatMusic (更多 → 退出浮音)**, then start the new version; otherwise, the single-instance mechanism may keep activating the old process. Playlists, favorites, and settings are stored in the app data directory. Replacing the runtime directory does not automatically delete that data.

### Android

1. Install the ARM64 APK, open FloatMusic, and tap **Show floating window (显示悬浮窗)** on the welcome screen.
2. Allow display over other apps when prompted, then return and use the music-note icon. Allowing media notifications enables playback controls in the notification area.
3. Tap the icon to expand the card, then use **More → Import music (更多 → 导入音乐)** or **More → Search (更多 → 搜索)**.

Returning to the welcome screen hides an existing overlay; leaving it restores the overlay. Playback can continue when the card is collapsed or another app is active, though phone power-saving policies may limit background duration.

Preview APKs use a debug signature. The 1.1 package has been verified to use the same signature as 1.0; device installation details are in the [verification notes](VERIFICATION.md). If Android reports a signature mismatch, back up playlists and confirm the package source before proceeding; do not immediately uninstall the old version.

## Using the player card

The top area always retains the track name, artist, progress, previous/next controls, play/pause, and volume. Drag the top of the card to move it; the **minus** button at the upper right collapses it to the icon.

The volume button sits beside previous, play/pause, next, and playback mode. Clicking it opens a vertical slider upward. Drag up to increase volume or down to decrease it; click outside or press Back / Esc to dismiss it.

| Entry | Purpose |
|---|---|
| Lyrics (歌词) | Read timed lyrics, return to the current line, adjust timing, or fetch again; original/translation selection, font size, and spacing are in settings on both platforms |
| Playlists (歌单) | Select a playlist, view its description and count, and manage, reorder, select, import, or export tracks through the operations panel |
| More → Search music (更多 → 搜索音乐; “搜索” on Android) | Switch between song and playlist search; open online playlist details, preview tracks, or import the entire playlist |
| More → Rankings (更多 → 排行榜) | Fetch hot charts and other rankings, view details, preview, import, or refresh |
| More → Favorite current / My favorites (更多 → 收藏当前 / 我的收藏; “收藏” on Android) | Favorite the current track; Favorites shares sorting and batch management with other playlists |
| More → Settings (更多 → 设置) | Grouped playback quality, lyrics, appearance, search, and service controls for independent quality, font size, spacing, output devices, and more |
| More → Exit FloatMusic (更多 → 退出浮音) | Stop playback, remove the overlay, and exit the app |

**Lyrics, Playlists, and More share the same lower area**: click another tab to switch, or click the active tab again to collapse it. Scroll inside this area for longer lists or content.

The playlist and search toolbars scroll with their content, leaving more room for tracks when browsing downward. Scroll back to the top to select or manage playlists or search again. On Windows, `Ctrl+F` returns directly to the search field.

Check one or more libraries above the search field on either platform; all are selected by default, and songs and playlists share the selection. Search again after changing libraries. Clearing all selections prompts you to choose at least one. Search results and current playback information show their source, and matching song names are not merged across platforms. Set the combined limit under **More → Settings → Search result count (更多 → 设置 → 搜索显示数量)**. Songs and playlists share this limit; changes apply to the next search and survive a restart.

After importing a local file, click play. Previewing a search result does not automatically add it to a playlist. Select playback mode to the right of the next-track button. Sequential playback stops naturally at the playlist's end; repeat and shuffle modes continue changing tracks. Favorites and playlists stay on the device and are not synchronized to a NetEase account.

## Importing and organizing playlists

Open the management panel on the playlist page to create, rename, edit descriptions, copy entire playlists, import, or export. Favorites is a built-in playlist: older favorites migrate automatically, its name is fixed, and the whole playlist cannot be deleted. Removing a favorite does not remove the same track from other playlists.

| Goal | Operation |
|---|---|
| Reorder tracks | Drag the handle on the right of a track on Windows; on Android, press and hold the handle before dragging, or use move-up/down actions |
| Organize multiple tracks | Enter selection mode and check tracks to move, copy, remove, or export. Moving and copying preserve order and merge duplicates in the destination playlist |
| Import an online playlist | Switch search to Playlists (歌单), open its details, and choose a destination |
| Transfer a playlist | Export JSON or copy it to the clipboard from the management panel, then import on the other device; exporting only selected tracks is also supported |

The file format is FloatMusic **UTF-8 JSON**, containing a playlist name, description, and track list. Each import or export is limited to 10,000 tracks and 8 MiB. Windows defaults the save name to “playlist name.json”. Imports can go into the current playlist, Favorites, or a new playlist.

The clipboard accepts full NetEase, QQ, and Kuwo playlist URLs, such as `music.163.com/playlist?id=…`, `y.qq.com/n/ryqq/playlist/…`, and `www.kuwo.cn/playlist_detail/…`. A bare numeric ID is still treated as NetEase. Open short links in a browser and copy their full URL first. Online playlists are limited to 2,000 tracks per read; an API may return only part of a playlist, and details show the loaded count and why it is incomplete.

**JSON does not contain audio files.** Online tracks can be imported on another device, but playback still depends on online services. Local tracks only record file locations; inaccessible references are skipped with a message on the destination device. Exporting a playlist neither downloads songs nor replaces an audio backup.

Version 1.1 continues to export JSON version 1, preserving each online track's platform and original ID without saving temporary playback URLs. Older NetEase playlists and 0.9 mixed playlists remain compatible. Both platforms running 0.9 / 1.0 / 1.1 can exchange mixed playlists. Older builds such as Android 0.8 skip QQ and Kuwo tracks with a message, so check the version before importing.

## Rankings

Opening **More → Rankings (更多 → 排行榜)** for the first time automatically loads charts. Hot, new, rising, and original charts are prioritized; other charts follow the service's order. Open a chart to preview tracks, add them to the current playlist, or choose a destination for the entire list. Back returns to the ranking list.

Names, descriptions, update frequency, and counts reflect the service response. Refresh fetches them again; failures show a reason and a retry option. Ranking details share online playlists' read limit and incomplete-result notices. Charts, songs, and audio sources are not guaranteed to remain available.

## Quality, output, and appearance

Adjust these under **More → Settings (更多 → 设置)**:

- **Quality**: Each library saves its own preference. NetEase offers standard, higher, highest, lossless, and Hi-Res; QQ offers standard, high quality 320, lossless FLAC, and experimental master; Kuwo offers standard, high quality 320, and lossless FLAC. Controls remain available without a track or while loading. Changing the active track's source tier reloads it while preserving position and play/pause state; changing another source does not interrupt playback. Unavailable higher tiers fall back to lower tiers for the same track. Actual format and sample specifications come from the returned audio; selecting a higher tier does not guarantee availability for every song.
- **Audio output**: Follow the system default or select a listed device. If Bluetooth headphones are silent, confirm the system connection, refresh the output list, and select the headphones. Android routing also depends on system and device support.
- **Size**: Windows supports 90–140%; Android supports 90–130%, subject to available screen space. Windows also supports proportional resizing by dragging the card's lower-right corner.
- **Background opacity**: 20–100%, affecting only the background; text and controls remain visible.
- **UI motion**: Light feedback when pressing and releasing buttons; it can be disabled. Android also follows the system animation setting.
- **Lyric display, font size, and spacing**: Choose original, translation, or bilingual lyrics. Font size is 10–30, default 18; the current line is two sizes larger. Spacing is 0.8–3.0× in 0.1 increments, with a default of 1× natural line height, so spacing shrinks with the font. A two-line preview updates live. Timed and plain-text lyrics both use the setting, manual browsing position is retained while adjusting it, and preferences survive exiting and restarting.

Quality and output devices are in playback settings; lyric controls are grouped together, and the custom NetEase service address is collapsed by default. QQ experimental master is requested only when selected and may increase loading time. Higher sample specifications do not establish the original master's quality. See the [high-quality audio research](docs/QQ音乐与酷我音乐高音质接口调研-2026-10-06.md) for API samples.

More platform-specific operations are in the [Windows user guide](docs/Windows使用指南.md) and [Android user guide](docs/Android使用指南.md).

## Music services and local data

NetEase search, rankings, and lyrics use its site APIs. Built-in playback tries [GD Studio](https://music.gdstudio.xyz/), the original third-party service, and [INJAHOW Meting](https://api.injahow.cn/meting/) in order. QQ song search and quality resolution use the [Vkeys / 落月 API](https://api.vkeys.cn/), with INJAHOW as a standard-quality backup. Kuwo song search and lyrics use the [Ourcraft aggregation API](https://music.yuncan.xyz/api); playback first requests the selected quality through the public mobi endpoint, then tries the aggregation service's original URL and proxy backup. Each platform retains its own track IDs. Changing audio sources does not substitute a same-named song from another platform. FloatMusic does not offer account login or cloud playlist synchronization.

QQ playlist search, details, and rankings use anonymous musicu APIs. Kuwo playlists use search and playlist services; ranking details use a public client protocol. See the [playlist and ranking research](docs/QQ音乐与酷我音乐歌单排行榜接口调研-2026-10-05.md) for interfaces and candidate selection. These interfaces read public metadata; playback still depends on audio availability. Account login and unlocking paid content have not been added.

URL resolution, audio connection, and loading failures automatically trigger backups. A resolution attempt has an overall timeout; temporarily failing services cool down for 30 seconds, and GD requests also have local rate protection. Settings show the source, requested tier, and actual playback format. INJAHOW returned MP3 128 kbps in testing; backups do not guarantee lossless or Hi-Res audio. High-quality responses are checked for track identity and audio format. Kuwo also uses the expected duration to reject clearly incomplete short audio or short audio whose completeness cannot be confirmed. Services may still time out together, rate-limit requests, or lack tracks; retry, change quality, or try another song.

If you have a compatible NetEase service, enter its address under **More → Settings → Music service (更多 → 设置 → 音乐服务)**. It only affects NetEase. Custom NetEase mode contacts only the selected service; switching back to built-in mode restores backup sources. Selected QQ and Kuwo libraries continue using their own built-in services. Windows API requests follow system proxy configuration; Android follows the device's network environment. Network conditions and experiment boundaries are in the [verification notes](VERIFICATION.md); historical backup parameters are in the [test report](docs/备用音源接入与实测-2026-10-01.md).

The GD Studio API is provided by GD Studio. Its public terms restrict it to personal learning and noncommercial use, and source attribution is retained. FloatMusic does not download or distribute online songs.

Local imports save an audio copy in the app data directory. The original file is not modified, and audio is not uploaded. Playlists, favorites, and settings stay on the device. Removing tracks or deleting playlists does not delete original files; imported copies are not currently cleaned up automatically. Online requests send search terms, track IDs, and related information to the corresponding service.

## Current limitations and feedback

Playlists support bulk JSON import and drag reordering. Bulk audio-file import, local LRC import, and downloading online songs are not currently supported. Phone power-saving policies, screen sizes, and Bluetooth devices still need ongoing compatibility work.

Version 1.1's automated checks, anonymous API samples, and device installation details are in the [verification notes](VERIFICATION.md). Third-party services can time out, rate-limit requests, or lack songs. Passing limited samples does not mean every track will always play. Kugou has not produced verified playable results and is not included in this release. Coverage of other devices and Android background scenarios still needs to expand.

To report a problem, open an [Issue](https://github.com/chensiray/FloatMusic/issues) and include the app version, OS/device model, reproduction steps, and actual behavior.

[Changelog](CHANGELOG.md) · [Limitations and plans](BACKLOG.md) · [Development and builds](docs/DEVELOPMENT.md)
