#!/usr/bin/env python3
"""
Production Release Publisher for GitHub Releases.
Uploads signed release APK and update-manifest.json assets with robust token resolution,
duplicate asset handling (replaces previous asset on tag to avoid HTTP 422), and timeouts.
"""

import os
import sys
import json
import subprocess
import urllib.request
import urllib.error
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

def get_github_token() -> str:
    # Tier 1: Check environment variables
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and token.strip():
        return token.strip()

    # Tier 2: Check GitHub CLI (gh auth token)
    try:
        res = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=4)
        if res.returncode == 0 and res.stdout.strip():
            return res.stdout.strip()
    except (FileNotFoundError, subprocess.SubprocessError):
        pass

    # Tier 3: Query git credential helper with timeout
    try:
        p = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n",
            capture_output=True,
            text=True,
            timeout=5
        )
        for line in p.stdout.splitlines():
            if line.startswith("password="):
                pwd = line.split("=", 1)[1].strip()
                if pwd:
                    return pwd
    except (FileNotFoundError, subprocess.SubprocessError):
        pass

    raise RuntimeError(
        "GitHub token not found. Please set GITHUB_TOKEN environment variable "
        "or authenticate via 'gh auth login' / git credential manager."
    )

def main():
    root_dir = Path(__file__).resolve().parent.parent
    os.chdir(root_dir)

    manifest_path = root_dir / "release" / "update-manifest.json"
    if not manifest_path.exists():
        print(f"[ERROR] Manifest not found at {manifest_path}. Run 'trace release' first.")
        sys.exit(1)

    with open(manifest_path, "r", encoding="utf-8-sig") as f:
        manifest = json.load(f)

    version_name = manifest.get("versionName", "0.1.0")
    version_code = manifest.get("versionCode", 1)
    tag = f"v{version_name}"
    size_bytes = manifest.get("apkSizeBytes", 0)
    sha256 = manifest.get("sha256", "")
    cert_sha256 = manifest.get("signingCertSha256", "")
    notes = manifest.get("releaseNotes", "")
    repo = "DreamHAX444/Controller-App"

    token = get_github_token()
    print(f"[+] Authenticated to GitHub for repository {repo}")

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "TracePublisher/2.0"
    }

    release_url = f"https://api.github.com/repos/{repo}/releases"

    # 1. Check if release already exists
    release_id = None
    existing_assets = {}
    try:
        req = urllib.request.Request(f"{release_url}/tags/{tag}", headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode())
            release_id = data["id"]
            print(f"[+] Found existing release ID: {release_id} for {tag}")
            for asset in data.get("assets", []):
                existing_assets[asset["name"]] = asset["id"]
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print(f"[!] GitHub API lookup error: {e.code} - {e.read().decode()[:200]}")
            raise

    # 2. Create release if absent
    if not release_id:
        body = {
            "tag_name": tag,
            "target_commitish": "main",
            "name": f"Trace Controller {tag}",
            "body": f"## Trace Android Controller {tag}\n\n- Version: {version_name}\n- VersionCode: {version_code}\n- Package: com.livetracker.controller\n- SHA-256: `{sha256}`\n- Size: {size_bytes} bytes\n- Signing SHA-256: `{cert_sha256}`\n\n### Release Notes\n{notes}",
            "draft": False,
            "prerelease": False
        }
        req = urllib.request.Request(release_url, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode())
            release_id = data["id"]
            print(f"[+] Release created successfully (ID: {release_id}): {data.get('html_url')}")

    # 3. Helper to delete existing asset to avoid HTTP 422 duplicate error
    def delete_asset_if_exists(asset_name: str):
        if asset_name in existing_assets:
            aid = existing_assets[asset_name]
            print(f"[*] Removing duplicate existing asset '{asset_name}' (ID: {aid})...")
            del_url = f"https://api.github.com/repos/{repo}/releases/assets/{aid}"
            del_req = urllib.request.Request(del_url, headers=headers, method="DELETE")
            try:
                with urllib.request.urlopen(del_req, timeout=15):
                    print(f"[+] Removed old {asset_name}")
            except urllib.error.HTTPError as ex:
                print(f"[!] Warning deleting asset: {ex.code}")

    # 4. Upload APK asset
    apk_path = root_dir / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk_path.exists():
        print(f"[ERROR] Release APK not found at {apk_path}. Run 'trace release' first.")
        sys.exit(1)

    delete_asset_if_exists("app-release.apk")
    with open(apk_path, "rb") as f:
        apk_bytes = f.read()

    upload_url = f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name=app-release.apk"
    upload_headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "User-Agent": "TracePublisher/2.0"
    }
    print(f"[*] Uploading app-release.apk ({len(apk_bytes):,} bytes)...")
    req = urllib.request.Request(upload_url, data=apk_bytes, headers=upload_headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        asset_info = json.loads(resp.read().decode())
        print(f"[OK] Uploaded APK: {asset_info['browser_download_url']}")

    # 5. Upload Manifest asset
    delete_asset_if_exists("update-manifest.json")
    with open(manifest_path, "rb") as f:
        manifest_bytes = f.read()

    upload_manifest_url = f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name=update-manifest.json"
    manifest_headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "User-Agent": "TracePublisher/2.0"
    }
    print(f"[*] Uploading update-manifest.json ({len(manifest_bytes):,} bytes)...")
    req = urllib.request.Request(upload_manifest_url, data=manifest_bytes, headers=manifest_headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        m_info = json.loads(resp.read().decode())
        print(f"[OK] Uploaded Manifest: {m_info['browser_download_url']}")

    print("\n[OK] Production release assets published successfully to GitHub!")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"\n[ERROR] Release publishing failed: {e}")
        sys.exit(1)
