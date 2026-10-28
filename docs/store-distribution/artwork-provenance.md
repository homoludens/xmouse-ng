# Store and launcher artwork provenance

## Source and regeneration

- Source: `docs/xmouse_ng_icon.png`, supplied by the maintainer and kept unchanged.
- Source SHA-256: `cca449b33fcf8d0b329734ded024814273a663b27b67884a049af6b751c75689`.
- Source properties: 1254×1254 RGB PNG, 1,226,542 bytes, no ancillary metadata or alpha channel.
- Regeneration: install the pinned developer-only Pillow version from `scripts/requirements-store-artwork.txt`, then run:

  ```sh
  python3 scripts/store_listing.py --generate-artwork
  python3 scripts/store_listing.py --validate
  ```

The generator verifies the source hash before use. It uses Pillow 12.3.0 Lanczos high-quality resampling and lossless PNG compression (`optimize=True`, compression level 9); it does not quantize colors or add metadata. Screenshots are derived from the already synthetic `docs/screenshots/touchpad.png` and `actions.png`; neutral side margins convert their 960×2160 portrait ratio to the Play-compatible 1080×2160 (1:2) ratio without cropping or resampling any screenshot pixels.

## Generated store images

| Asset | Dimensions | Bytes | Source/transform |
| --- | ---: | ---: | --- |
| `images/icon.png` | 512×512 | 199,267 | Supplied artwork, Lanczos downsample; 83.8% fewer bytes than the 1254px source |
| `images/featureGraphic.png` | 1024×500 | 207,237 | Same artwork, centered at 500×500 on white; no text or store badge |
| `images/phoneScreenshots/01-touchpad.png` | 1080×2160 | 124,479 | Source 960×2160, padded 60px on each side; source was 127,911 bytes |
| `images/phoneScreenshots/02-actions.png` | 1080×2160 | 83,603 | Source 960×2160, padded 60px on each side; source was 85,916 bytes |

## Android launcher resources

The ordinary density-specific fallback icons are 48×48, 72×72, 96×96, 144×144, and 192×192 for mdpi through xxxhdpi. Round fallbacks use the same source art with a smooth circular alpha mask. API-26+ square and round adaptive resources use a 108dp foreground canvas, a white background matching the supplied artwork, and a centered 72dp foreground image. The dark circular pad and all four arrow glyphs occupy a measured maximum 32.74dp radius, within the 36dp circular safe radius. The manifest names both `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`; adaptive XML resolves the foreground and white background layers.

Existing square launcher PNG sizes were measured before replacement; the new square retains the artwork's smooth gradients and is not palette-quantized. Round and adaptive foreground layers are additional resources:

| Density | Previous square | New square | New round | New adaptive foreground |
| --- | ---: | ---: | ---: | ---: |
| mdpi | 1,376 | 3,672 | 4,470 | 7,011 |
| hdpi | 2,050 | 6,188 | 7,589 | 12,337 |
| xhdpi | 2,785 | 9,231 | 11,301 | 19,956 |
| xxhdpi | 4,368 | 17,303 | 21,057 | 42,352 |
| xxxhdpi | 6,152 | 29,115 | 34,918 | 77,614 |
| **Total** | **16,731** | **65,509** | **79,335** | **159,270** |

The full 1,226,542-byte source is not an app resource. `python3 scripts/store_listing.py --validate-apk app/build/outputs/apk/debug/app-debug.apk` inspected 235 packaged images and found no source-named, source-hash, pixel-identical, or 1254×1254 source image entry. Generated PNG metadata checks were empty. `aapt dump badging` identified the package as `net.droopia.xmouse_ng`, version 2.1.2/code 28, and resolved `ic_launcher.xml` under `mipmap-anydpi-v26`.

Circle and rounded-square adaptive-mask approximations were rendered under `/tmp/opencode/store-distribution/icon-previews/` and visually inspected; the whole pad and all four arrows remain inside both masks. These previews are local verification artifacts, not submitted artwork.
