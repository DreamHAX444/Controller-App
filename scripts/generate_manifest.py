#!/usr/bin/env python3
import json
import os
import datetime

def main():
    os.makedirs("release", exist_ok=True)
    
    notes = os.environ.get("CUSTOM_NOTES", "").strip()
    tag = os.environ.get("TAG_NAME", "v1.0.0")
    if not notes:
        notes = f"Release {tag}: Core telemetry updates and self-update engine."

    published_at = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    manifest = {
        "schemaVersion": 1,
        "appId": "com.livetracker.controller",
        "channel": os.environ.get("CHANNEL", "stable"),
        "versionCode": int(os.environ.get("VERSION_CODE", "1")),
        "versionName": os.environ.get("VERSION_NAME", "1.0.0"),
        "minimumSupportedVersionCode": 1,
        "mandatory": os.environ.get("IS_MANDATORY", "false").lower() == "true",
        "publishedAt": published_at,
        "releaseNotes": notes,
        "apkUrl": os.environ.get("DOWNLOAD_URL", f"https://github.com/DreamHAX444/Controller-App/releases/download/{tag}/app-release.apk"),
        "apkSizeBytes": int(os.environ.get("SIZE_BYTES", "44086985")),
        "sha256": os.environ.get("SHA256", "778c432c08d161fca76febbb48240de4642a9f8ee94bc288811a683c4e7a0be6"),
        "signingCertSha256": os.environ.get("CERT_FINGERPRINT", "b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2")
    }

    with open("release/update-manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print("Generated release/update-manifest.json:")
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
