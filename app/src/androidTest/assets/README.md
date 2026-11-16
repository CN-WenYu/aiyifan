# Playback fixture

`playback-test.mp4` is a synthetic 20-second, 160×90 H.264/AAC clip generated for
`PlaybackDeviceTest`. It contains test-pattern video and silent audio, with no
third-party content. It is packaged only in the instrumentation test APK.

`download/` contains HLS variants derived from this same synthetic clip: two
video resolutions, a separate silent audio rendition, and an AES-128 rendition.
`download/aes/key.bin` is deliberately public fixture data (bytes 00 through 0f),
not an application credential. The fixed IV has those same bytes. Tests serve
these files on loopback, close that server, and require playback from cache.

Recreate with FFmpeg:

```powershell
ffmpeg -f lavfi -i 'testsrc2=size=160x90:rate=10' -f lavfi -i 'anullsrc=r=22050:cl=mono' -t 20 -c:v libx264 -preset fast -crf 35 -pix_fmt yuv420p -c:a aac -b:a 16k -movflags +faststart playback-test.mp4
```
