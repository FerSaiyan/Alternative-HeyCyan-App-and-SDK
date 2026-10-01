# Meta SDK mock camera fixture

`camera-feed.mp4` is three seconds of synthetic cyan frames, 320×240 at 24 fps,
H.264/yuv420p. No user media. Generated with:

```sh
ffmpeg -f lavfi -i 'color=c=cyan:s=320x240:r=24' -t 3 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart camera-feed.mp4
```
