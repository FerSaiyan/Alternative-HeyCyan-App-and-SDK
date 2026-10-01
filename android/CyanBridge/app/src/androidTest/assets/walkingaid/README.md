# Walking Aid stream fixtures

Both clips are 12 seconds, yuv420p, 640×480, 6 FPS: six seconds of the bus
scene, then six seconds of the Zidane scene. Sources are the existing pinned,
checksum-verified JPEG fixtures in `tools/hil/walking_aid_assets.tsv` from
`ultralytics/assets` revision `db36e84d7a7ea9a5cf0cecb042e386c4b007e44c`.

- `meta-scenes.mp4` (HEVC/H.265): upright bus scene letterboxed and Zidane scene
  center-cropped to 640×480 for Meta DAT. The SDK mock's negotiated decoder is HEVC;
  an H.264 feed can report STREAMING without yielding decoded video frames.
  SHA-256: `4a2f58d6641e9bc26f5bfb299bcb7b2c73e5cf66233550aa63619ede783a4da7`.
- `eyevue-scenes.mp4` (H.264): bus scene letterboxed and Zidane scene center-cropped to
  480×640 and rotated clockwise to
  emulate EyeVue's 640×480 camera layout. Production rotates them back -90°.
  SHA-256: `75a11b91a976ba0de562ce812832c111c5d2691f7aeec4ea699afd5ad0beb8da`.

The center crop retains a visible tie at a useful camera resolution rather than
shrinking a wide scene into a narrow portrait letterbox.

Encoding: ffmpeg `libx264`, `-r 6 -preset veryslow -crf 23 -pix_fmt yuv420p
-movflags +faststart`. No network/model inference is used to construct the clips.
Meta encoding uses `libx265 -preset fast -crf 23 -tag:v hvc1`, with
`pools=2:frame-threads=1:bframes=0:repeat-headers=1`, from the upright H.264 intermediate.
The real-model CI runner separately verifies the YOLO model's pinned checksum.
