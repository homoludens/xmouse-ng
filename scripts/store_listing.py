#!/usr/bin/env python3
"""Generate and validate the shared, source-backed Play/F-Droid listing material."""

from __future__ import annotations

import json
import hashlib
import io
import math
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
import argparse
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
LISTING = ROOT / "fastlane/metadata/android/en-US"
STORE_IMAGES = LISTING / "images"
SCREENSHOTS = LISTING / "images/phoneScreenshots"
ICON_SOURCE = ROOT / "docs/xmouse_ng_icon.png"
ICON_SOURCE_SHA256 = "cca449b33fcf8d0b329734ded024814273a663b27b67884a049af6b751c75689"
STORE_ICON = STORE_IMAGES / "icon.png"
FEATURE_GRAPHIC = STORE_IMAGES / "featureGraphic.png"
SCREENSHOT_SOURCES = {
    "01-touchpad.png": ROOT / "docs/screenshots/touchpad.png",
    "02-actions.png": ROOT / "docs/screenshots/actions.png",
}
ICON_DENSITIES = {
    "mdpi": (48, 1),
    "hdpi": (72, 3 / 2),
    "xhdpi": (96, 2),
    "xxhdpi": (144, 3),
    "xxxhdpi": (192, 4),
}
ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_SAFE_DIAMETER_DP = 72
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
PROJECT_URL = "https://github.com/homoludens/xmouse-ng"
MAX_LENGTHS = {
    "title.txt": 30,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
STORE_CLAIM = re.compile(
    r"(?:available\s+on|get\s+it\s+on|download\s+on)\s+(?:google\s+play|f-droid)",
    re.IGNORECASE,
)
AVAILABILITY_BADGE = re.compile(r"(?:google\s+play|f-droid)\s+(?:badge|availability\s+badge)", re.IGNORECASE)


def generate_screenshots() -> None:
    SCREENSHOTS.mkdir(parents=True, exist_ok=True)
    for name, source_path in SCREENSHOT_SOURCES.items():
        with Image.open(source_path) as source:
            if source.format != "PNG":
                raise ValueError(f"Screenshot source is not PNG: {source_path.relative_to(ROOT)}")
            image = source.convert("RGB")
            width, height = image.size
            # Google Play portrait screenshots are limited to a 2:1 height/width ratio.
            # Extend only the side background, preserving every pixel of the synthetic capture.
            target_width = max(width, (height + 1) // 2)
            canvas = Image.new("RGB", (target_width, height), image.getpixel((0, 0)))
            canvas.paste(image, ((target_width - width) // 2, 0))
            canvas.save(SCREENSHOTS / name, format="PNG", optimize=True, compress_level=9)


def load_icon_source() -> Image.Image:
    contents = ICON_SOURCE.read_bytes()
    source_hash = hashlib.sha256(contents).hexdigest()
    if source_hash != ICON_SOURCE_SHA256:
        raise ValueError(f"Supplied icon source changed: SHA-256 {source_hash}")
    with Image.open(io.BytesIO(contents)) as image:
        if image.format != "PNG" or image.size != (1254, 1254) or image.mode != "RGB" or image.info:
            raise ValueError("Icon source must remain the original metadata-free 1254×1254 RGB PNG")
        return image.copy()


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.info.clear()
    image.save(path, format="PNG", optimize=True, compress_level=9)


def resized_source(source: Image.Image, size: int) -> Image.Image:
    return source.resize((size, size), Image.Resampling.LANCZOS)


def make_round_icon(source: Image.Image, size: int) -> Image.Image:
    image = resized_source(source, size).convert("RGBA")
    oversample = 4
    mask = Image.new("L", (size * oversample, size * oversample), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * oversample - 1, size * oversample - 1), fill=255)
    image.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
    return image


def make_adaptive_foreground(source: Image.Image, canvas_size: int, safe_size: int) -> Image.Image:
    artwork = resized_source(source, safe_size).convert("RGBA")
    foreground = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    offset = (canvas_size - safe_size) // 2
    foreground.alpha_composite(artwork, (offset, offset))
    return foreground


def generate_store_artwork() -> list[str]:
    source = load_icon_source()
    save_png(resized_source(source, 512), STORE_ICON)

    feature = Image.new("RGB", (1024, 500), (255, 255, 255))
    feature_art = resized_source(source, 500)
    feature.paste(feature_art, ((1024 - 500) // 2, 0))
    save_png(feature, FEATURE_GRAPHIC)

    generated: list[str] = [str(STORE_ICON.relative_to(ROOT)), str(FEATURE_GRAPHIC.relative_to(ROOT))]
    for density, (icon_size, scale) in ICON_DENSITIES.items():
        directory = ROOT / "app/src/main/res" / f"mipmap-{density}"
        launcher_icon = resized_source(source, icon_size)
        round_icon = make_round_icon(source, icon_size)
        canvas_size = round(ADAPTIVE_CANVAS_DP * scale)
        safe_size = round(ADAPTIVE_SAFE_DIAMETER_DP * scale)
        foreground = make_adaptive_foreground(source, canvas_size, safe_size)
        for name, image in (
            ("ic_launcher.png", launcher_icon),
            ("ic_launcher_round.png", round_icon),
            ("ic_launcher_foreground.png", foreground),
        ):
            path = directory / name
            save_png(image, path)
            generated.append(str(path.relative_to(ROOT)))
    return generated


def max_art_radius(source: Image.Image) -> float:
    pixels = source.convert("RGB").load()
    center_x = (source.width - 1) / 2
    center_y = (source.height - 1) / 2
    radius = 0.0
    for y in range(source.height):
        for x in range(source.width):
            if min(pixels[x, y]) < 245:
                dx, dy = x - center_x, y - center_y
                radius = max(radius, math.hypot(dx, dy))
    return radius


def validate_png(path: Path, expected_size: tuple[int, int], expected_mode: str | None = None) -> Image.Image:
    if not path.is_file():
        raise ValueError(f"Missing generated image: {path.relative_to(ROOT)}")
    with Image.open(path) as image:
        image.verify()
    with Image.open(path) as image:
        if image.format != "PNG" or image.size != expected_size:
            raise ValueError(f"Expected PNG {expected_size[0]}×{expected_size[1]}: {path.relative_to(ROOT)}")
        if image.info:
            raise ValueError(f"PNG contains ancillary metadata: {path.relative_to(ROOT)}")
        if expected_mode and image.mode != expected_mode:
            raise ValueError(f"Expected {expected_mode} PNG: {path.relative_to(ROOT)}")
        return image.copy()


def validate_icon_artwork() -> list[str]:
    report: list[str] = []
    source = load_icon_source()
    source_radius_dp = max_art_radius(source) / source.width * ADAPTIVE_SAFE_DIAMETER_DP
    if source_radius_dp > ADAPTIVE_SAFE_DIAMETER_DP / 2:
        raise ValueError(f"Source artwork exceeds the adaptive safe radius: {source_radius_dp:.2f}dp")
    report.append(
        f"icon source: 1254×1254 RGB, {ICON_SOURCE.stat().st_size} bytes, "
        f"SHA-256 {ICON_SOURCE_SHA256}; adaptive non-white art radius {source_radius_dp:.2f}/36dp"
    )

    for path, dimensions in ((STORE_ICON, (512, 512)), (FEATURE_GRAPHIC, (1024, 500))):
        validate_png(path, dimensions, "RGB")
        report.append(f"{path.relative_to(ROOT)}: {dimensions[0]}×{dimensions[1]} PNG, {path.stat().st_size} bytes")

    for density, (icon_size, scale) in ICON_DENSITIES.items():
        directory = ROOT / "app/src/main/res" / f"mipmap-{density}"
        square_path = directory / "ic_launcher.png"
        round_path = directory / "ic_launcher_round.png"
        foreground_path = directory / "ic_launcher_foreground.png"
        validate_png(square_path, (icon_size, icon_size), "RGB")
        round_icon = validate_png(round_path, (icon_size, icon_size), "RGBA")
        if round_icon.getchannel("A").getpixel((0, 0)) != 0 or round_icon.getchannel("A").getpixel(
            (icon_size // 2, icon_size // 2)
        ) < 250:
            raise ValueError(f"Round launcher alpha mask is invalid: {round_path.relative_to(ROOT)}")
        canvas_size = round(ADAPTIVE_CANVAS_DP * scale)
        safe_size = round(ADAPTIVE_SAFE_DIAMETER_DP * scale)
        foreground = validate_png(foreground_path, (canvas_size, canvas_size), "RGBA")
        expected_offset = (canvas_size - safe_size) // 2
        expected_bounds = (
            expected_offset,
            expected_offset,
            expected_offset + safe_size,
            expected_offset + safe_size,
        )
        if foreground.getchannel("A").getbbox() != expected_bounds:
            raise ValueError(f"Adaptive foreground exceeds its 72dp safe box: {foreground_path.relative_to(ROOT)}")
        report.append(
            f"{density}: square/round {icon_size}×{icon_size}; foreground {canvas_size}×{canvas_size}; "
            f"sizes {square_path.stat().st_size}/{round_path.stat().st_size}/{foreground_path.stat().st_size} bytes"
        )

    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    if application is None or application.get(f"{ANDROID_NS}icon") != "@mipmap/ic_launcher" or \
            application.get(f"{ANDROID_NS}roundIcon") != "@mipmap/ic_launcher_round":
        raise ValueError("Manifest must reference the square and round launcher resources")
    background = None
    for node in ET.parse(ROOT / "app/src/main/res/values/colors.xml").getroot().findall("color"):
        if node.get("name") == "ic_launcher_background":
            background = (node.text or "").strip()
    if background != "#FFFFFF":
        raise ValueError("Adaptive launcher background must match the supplied artwork's white background")
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        adaptive_path = ROOT / "app/src/main/res/mipmap-anydpi-v26" / name
        adaptive = ET.parse(adaptive_path).getroot()
        layers = {child.tag: child.get(f"{ANDROID_NS}drawable") for child in adaptive}
        if adaptive.tag != "adaptive-icon" or layers != {
            "background": "@color/ic_launcher_background",
            "foreground": "@mipmap/ic_launcher_foreground",
        }:
            raise ValueError(f"Adaptive/round resource layers are invalid: {adaptive_path.relative_to(ROOT)}")
    report.append("manifest/resource references: square, round, and API-26 adaptive layers resolve")

    resources = ROOT / "app/src/main/res"
    for path in resources.rglob("*"):
        if not path.is_file():
            continue
        if hashlib.sha256(path.read_bytes()).hexdigest() == ICON_SOURCE_SHA256:
            raise ValueError("Full-resolution source artwork must not be copied into Android resources")
        if path.suffix.lower() in {".png", ".webp", ".jpg", ".jpeg"}:
            try:
                with Image.open(path) as image:
                    if image.size == source.size:
                        raise ValueError(f"Full-resolution icon dimensions are present in Android resources: {path}")
            except (OSError, Image.DecompressionBombError):
                continue
    return report


def render_icon_previews(output_dir: Path) -> list[str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    foreground_path = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
    foreground = validate_png(foreground_path, (432, 432), "RGBA")
    preview = Image.alpha_composite(Image.new("RGBA", foreground.size, (255, 255, 255, 255)), foreground)
    oversample = 4
    result: list[str] = []
    for name in ("circle", "rounded-square"):
        mask = Image.new("L", (preview.width * oversample, preview.height * oversample), 0)
        draw = ImageDraw.Draw(mask)
        bounds = (0, 0, mask.width - 1, mask.height - 1)
        if name == "circle":
            draw.ellipse(bounds, fill=255)
        else:
            draw.rounded_rectangle(bounds, radius=round(mask.width * 0.22), fill=255)
        preview_copy = preview.copy()
        preview_copy.putalpha(mask.resize(preview.size, Image.Resampling.LANCZOS))
        path = output_dir / f"adaptive-mask-{name}.png"
        save_png(preview_copy, path)
        result.append(str(path))
    return result


def validate_apk(apk_path: Path) -> str:
    source = load_icon_source()
    source_bytes = ICON_SOURCE.read_bytes()
    if not apk_path.is_absolute():
        apk_path = ROOT / apk_path
    if not apk_path.is_file():
        raise ValueError(f"APK does not exist: {apk_path}")
    checked_images = 0
    with zipfile.ZipFile(apk_path) as apk:
        for entry in apk.infolist():
            if entry.is_dir():
                continue
            name = Path(entry.filename).name.lower()
            payload = apk.read(entry)
            if name == ICON_SOURCE.name.lower() or hashlib.sha256(payload).hexdigest() == ICON_SOURCE_SHA256 or \
                    source_bytes in payload:
                raise ValueError(f"Full-resolution source image is present in APK entry {entry.filename}")
            if Path(entry.filename).suffix.lower() not in {".png", ".webp", ".jpg", ".jpeg"}:
                continue
            try:
                with Image.open(io.BytesIO(payload)) as image:
                    if image.size == source.size:
                        if image.convert("RGB").tobytes() == source.tobytes():
                            raise ValueError(f"Full-resolution source pixels are present in APK entry {entry.filename}")
                        raise ValueError(f"1254×1254 image is present in APK entry {entry.filename}")
                    checked_images += 1
            except (OSError, Image.DecompressionBombError):
                continue
    return f"{apk_path}: checked {checked_images} packaged images; full-resolution source artwork absent"


def read_text(name: str) -> str:
    path = LISTING / name
    if not path.is_file():
        raise ValueError(f"Missing listing text: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8").strip()


def validate() -> list[str]:
    report: list[str] = []
    text_values = {name: read_text(name) for name in MAX_LENGTHS}
    for name, maximum in MAX_LENGTHS.items():
        length = len(text_values[name])
        if length > maximum:
            raise ValueError(f"{name} is {length} characters; maximum is {maximum}")
        report.append(f"{name}: {length}/{maximum} characters")

    build_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version_name = re.search(r'versionName\s+["\']([^"\']+)["\']', build_gradle)
    version_code = re.search(r"versionCode\s+(\d+)", build_gradle)
    if not version_name or not version_code:
        raise ValueError("Could not read prepared app version from app/build.gradle")
    changelog = LISTING / "changelogs" / f"{version_code.group(1)}.txt"
    if not changelog.is_file():
        raise ValueError(f"Missing changelog for version code {version_code.group(1)}")
    changelog_text = changelog.read_text(encoding="utf-8").strip()
    if len(changelog_text) > 500:
        raise ValueError(f"Changelog is {len(changelog_text)} characters; maximum is 500")
    if not changelog_text.startswith(f"{version_name.group(1)} ({version_code.group(1)})"):
        raise ValueError("Changelog header does not match the prepared app version")
    report.append(f"changelogs/{version_code.group(1)}.txt: {len(changelog_text)}/500 characters")

    links = json.loads((ROOT / "docs/store-distribution/listing-links.json").read_text(encoding="utf-8"))
    if links.get("project_url") != PROJECT_URL or PROJECT_URL not in text_values["full_description.txt"]:
        raise ValueError("Listing project URL is missing or inconsistent")
    if links.get("support_url") != f"{PROJECT_URL}/issues":
        raise ValueError("Support URL is not the project's public issue tracker")
    if links.get("privacy_policy_source") != "docs/privacy-policy.md":
        raise ValueError("Privacy policy source path is inconsistent")
    if not (ROOT / links["privacy_policy_source"]).is_file():
        raise ValueError("Privacy policy source document is missing")
    if links.get("privacy_policy_status") != "not_published" or links.get("privacy_policy_url") is not None:
        raise ValueError("Unpublished policy hosting must not be represented as a live URL")
    if "privacy policy offline in Settings > About" not in text_values["full_description.txt"]:
        raise ValueError("Listing must identify the available in-app policy")
    report.append("project/support links: coherent; public privacy-policy URL: NOT PUBLISHED")

    listing_text = "\n".join(text_values.values())
    if STORE_CLAIM.search(listing_text) or AVAILABILITY_BADGE.search(listing_text):
        raise ValueError("Listing must not claim store availability or include a store badge")
    report.append("store acceptance, availability claims, and badges: none")

    for name, source_path in SCREENSHOT_SOURCES.items():
        path = SCREENSHOTS / name
        if not path.is_file():
            raise ValueError(f"Missing synthetic phone screenshot: {path.relative_to(ROOT)}")
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            width, height = image.size
            if image.format != "PNG":
                raise ValueError(f"Screenshot must be PNG: {path.relative_to(ROOT)}")
            if not (320 <= width <= 3840 and 320 <= height <= 3840):
                raise ValueError(f"Screenshot dimensions are outside store limits: {width}×{height}")
            if not (0.5 <= width / height <= 2.0):
                raise ValueError(f"Screenshot aspect ratio is outside 1:2–2:1: {width}×{height}")
            if image.info:
                raise ValueError(f"Screenshot contains ancillary metadata: {path.relative_to(ROOT)}")
            source = Image.open(source_path).convert("RGB")
            left = (width - source.width) // 2
            if source.size != (width - 2 * left, height) or image.convert("RGB").crop(
                (left, 0, left + source.width, height)
            ).tobytes() != source.tobytes():
                raise ValueError(f"Screenshot is not a pixel-faithful synthetic-source copy: {name}")
            report.append(f"{path.relative_to(ROOT)}: {width}×{height} PNG, {path.stat().st_size} bytes")
    report.extend(validate_icon_artwork())
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate", action="store_true", help="validate listing metadata and generated artwork")
    parser.add_argument("--generate-screenshots", action="store_true", help="regenerate the synthetic listing screenshots")
    parser.add_argument("--generate-artwork", action="store_true", help="regenerate screenshots, store art, and launcher icons")
    parser.add_argument("--render-previews", metavar="DIR", help="render adaptive circle/rounded-square mask previews")
    parser.add_argument("--validate-apk", metavar="APK", help="also verify that the full-resolution source is absent from an APK")
    arguments = parser.parse_args()
    if arguments.generate_screenshots and arguments.generate_artwork:
        parser.error("choose only one generation option")
    try:
        if arguments.generate_screenshots:
            generate_screenshots()
        if arguments.generate_artwork:
            generate_screenshots()
            generated = generate_store_artwork()
            print(f"Generated {len(generated)} store and launcher PNG assets from the supplied artwork.")
        if arguments.render_previews:
            previews = render_icon_previews(Path(arguments.render_previews))
            print("\n".join(f"Rendered {path}" for path in previews))
        report = validate()
        if arguments.validate_apk:
            report.append(validate_apk(Path(arguments.validate_apk)))
        print("\n".join(report))
    except (OSError, ValueError, KeyError, json.JSONDecodeError, ET.ParseError, zipfile.BadZipFile) as failure:
        print(f"store listing validation failed: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
