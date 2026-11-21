# Trimmed LibVLC for CyanBridge

`app/libs/libvlc-cyan-3.7.7.aar` replaces `org.videolan.android:libvlc-all:3.7.7`.
It is the same LibVLC (VLC 3.0.x, libvlcjni 3.x, Java API of libvlc-all 3.7.7)
built with only the modules and libraries the app uses.

| ABI | libvlc.so + libvlcjni.so, full 3.7.7 | trimmed | Play download, full / trimmed |
| --- | --- | --- | --- |
| arm64-v8a | 45.97 MB (21.8 MB compressed) | 5.85 MB (2.44 MB compressed) | 68.5 / 49.1 MB |
| armeabi-v7a | 39.89 MB | 4.48 MB | 53.8 / 35.5 MB |
| x86_64 | 54.48 MB | 7.11 MB | 72.1 / 51.2 MB |
| x86 | 49.92 MB | 6.67 MB | 57.7 / 38.7 MB |

Downloads are `bundletool get-size total` (1.18.3) for the release bundle of
2026-11-21 with each libvlc build (SDK 34, 420 dpi, en-US). The AAR's
`libc++_shared.so` is not shipped (see below) and is not counted.

## What the app plays

- Glasses live preview (HeyCyan, EyeVue, TuneBuds) and Walking Aid frames:
  RTSP over TCP (`:rtsp-tcp`) from the in-app relay on 127.0.0.1, H.264 video
  (H.265 tolerated) and the glasses' audio, decoded with `:codec=avcodec`.
  Output goes to `VLCVideoLayout` or, for Walking Aid, to an `ImageReader`
  surface through `vlcVout.setVideoSurface`.
- Mentra Live: the glasses' RTMP stream is received in the app, remuxed to
  MPEG-TS (H.264 + AAC) and served at `http://127.0.0.1:<port>/<token>.ts`.

Nothing else in the app or in `shared` uses LibVLC (no other network URLs,
no subtitles, no stream output, no MediaCodec: every `Media` forces
`:codec=avcodec`). The one local file is a test fixture: CI's Walking Aid
model test (`WalkingAidVisionStackIntegrationTest`) plays an MP4 as
`file://` through the production preview path, so `filesystem` and `mp4`
stay.

## What is kept

VLC modules: [modules.txt](modules.txt) (RTSP via live555, HTTP
and file access, TS/ES/H.26x/MP4 demuxers, H.264/H.265/AAC/MPEG-audio
packetizers, avcodec, PCM and G.711 decoders, Android window/GLES2/EGL/
native-window video output with the I420 converters, OpenSL ES and AudioTrack
output with the audio format, mixer, resampler and channel converters, logcat
logger, memory keystore). The list has 40 names; 37 are linked on every ABI.
The other three are VLC's ARMv7 NEON converters, which VLC's configure does
not build with NDK r27's armeabi-v7a defaults (the C converters are used).

Contribs (third-party libraries linked statically into `libvlc.so`):

| Contrib | Version | Why | License |
| --- | --- | --- | --- |
| ffmpeg (libavcodec, libavutil only) | 8.1.2 | `avcodec` decoder. Decoders: h264, hevc, aac, aac_latm, mp2float, mp3float, opus, mjpeg, adpcm_g726, pcm_alaw, pcm_mulaw; matching parsers. No avformat, swscale, encoders, network, hwaccel. | LGPL-2.1+ |
| live555 | 2026.06.24 | `live555` RTSP client | LGPL-3.0+ |
| libdvbpsi | 1.3.3 | `ts` demuxer (Mentra) | LGPL-2.1+ |

Everything else is left out: gnutls/nettle/gmp, taglib, libxml2, smb2/dsm/nfs,
ssh2, upnp, microdns, bluray/dvd, chromecast/protobuf, dav1d, libvpx, aom,
matroska, ogg/vorbis/flac/opus/speex, mpg123, libass, freetype, harfbuzz,
fribidi, fontconfig, libjpeg/png, zvbi, Lua, soxr, spatialaudio, libiconv
(bionic has iconv from API 28), and every VLC module not listed.

Consequences:

- No text renderer: VLC no longer draws the media title or OSD text over the
  video, and there is no fontconfig cache. The first preview after an install
  no longer waits for the 25-28 s font scan. `LibVLC.buildFontCache()` returns
  true at once, so `VlcFontCache` stays as a harmless no-op.
- No hardware decoding (`mediacodec`), no subtitles, no deinterlacing, no
  `Media` recording (`MediaPlayer.record`), no `Dumper`, no lua playlist
  scripts, no HRTF asset.

## Source revisions

| Part | Repository | Revision |
| --- | --- | --- |
| vlc-android (reference only) | https://code.videolan.org/videolan/vlc-android | tag `libvlc-3.7.7` = `7a7c8f14be6759f9bb1b9a934fd086259473e20e` |
| libvlcjni (JNI + build scripts) | https://code.videolan.org/videolan/libvlcjni | `0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6` (pinned by that vlc-android tag) |
| VLC | https://code.videolan.org/videolan/vlc | `84177b2273abc5c4300234e6e18b55a4d2dd5a03` (3.0.x, pinned by libvlcjni) + libvlcjni's `libvlc/patches/*.patch` |
| Contrib tarballs | pinned with SHA-512 in VLC's `contrib/src/<pkg>/SHA512SUMS` | see table above |
| Java half | `org.videolan.android:libvlc-all:3.7.7` from Maven Central | `classes.jar`, `res/`, `R.txt`, manifest, unchanged (SHA-256 checked in build.sh) |

Our changes are three patches in [patches/](patches/):

- `libvlcjni-0001-cyanbridge-trimmed-build.patch`: `compile-libvlc.sh`
  builds only the selected contribs with the trimmed ffmpeg options,
  configures VLC without optional dependencies, links only the modules in
  `modules.txt`, uses one NDK (r27-r29) and API level 28 for all four ABIs
  (upstream uses NDK r21 and API 17 for 32-bit); `Android.mk` no longer links
  libjpeg; `libvlcjni.c` builds without fontconfig.
- `vlc-0001-contrib-ffmpeg-optional-trimmed-configuration.patch`: the ffmpeg
  contrib takes extra configure options from `FFMPEG_TRIM_CONF`.
- `vlc-0002-avcodec-check-the-libavcodec-micro-version.patch`: VLC's avcodec
  module tested `LIBAVFORMAT_VERSION_MICRO`, which is undefined without
  libavformat, and then called the removed `avcodec_register_all()`.

16 KB pages: libraries are linked with `-z max-page-size=16384` (upstream
flags); `build.sh` refuses a 64-bit library with a PT_LOAD alignment below
0x4000.

## Rebuilding

Needs Linux x86_64, NDK r27-r29, git, curl, autotools, cmake, meson, ninja,
nasm, about 0.7 GB free per ABI, and network access to code.videolan.org, the
VideoLAN contrib mirror, ffmpeg.org, GNU mirrors (libtool) and Maven Central.

    ANDROID_NDK=~/Android/Sdk/ndk/27.2.12479018 ./build.sh

This writes `../../app/libs/libvlc-cyan-3.7.7.aar` (all four ABIs) in about
ten minutes. A clean rebuild gives byte-identical libraries except for the
build date string inside `libvlc.so`. The work tree defaults to
`~/build/vlc-trim` (`VLC_TRIM_WORK`); `VLC_TRIM_JOBS` sets the make
parallelism (default 12); `VLC_TARBALLS` can point at an existing contrib
tarball directory. To add a module, add its name to
`modules.txt` (and a contrib to `AVLC_CONTRIB_TARGETS` / a codec to
`AVLC_FFMPEG_TRIM_CONF` in `build.sh` if it needs one) and rebuild.

The app strips the AAR's `libc++_shared.so` and uses the shared one from its
other dependencies (`VlcWithoutBundledStl` in `app/build.gradle`).

## License

LibVLC, libvlcjni and the kept modules are LGPL-2.1-or-later; live555 is
LGPL-3.0-or-later, so the combined `libvlc.so` is distributed under
LGPL-3.0-or-later terms. The sources are the revisions above plus the patches
in this directory.
