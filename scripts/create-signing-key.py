#!/usr/bin/env python3
"""Create the one long-lived APK signing identity outside the source checkout."""

import argparse
import base64
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Private directory outside this repository")
    args = parser.parse_args()
    root = args.directory.expanduser().resolve()
    source = Path(__file__).resolve().parents[1]
    if root == source or source in root.parents:
        raise SystemExit("Choose a private directory outside the source repository.")
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    root.chmod(0o700)
    if any(root.iterdir()):
        raise SystemExit("Destination must be empty; an existing signing identity is never replaced.")
    password = secrets.token_urlsafe(36)
    env = dict(os.environ, MANGAFLOW_KEY_PASSWORD=password)
    subprocess.run(
        [
            "keytool", "-genkeypair", "-keystore", str(root / "mangaflow.p12"),
            "-storetype", "PKCS12", "-alias", "mangaflow", "-keyalg", "RSA",
            "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "20000",
            "-dname", "CN=MangaFlow, O=MangaFlow, C=CN",
            "-storepass:env", "MANGAFLOW_KEY_PASSWORD", "-keypass:env", "MANGAFLOW_KEY_PASSWORD",
        ],
        env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    (root / "MANGAFLOW_SIGNING_KEYSTORE_B64").write_text(base64.b64encode((root / "mangaflow.p12").read_bytes()).decode(), encoding="utf-8")
    (root / "MANGAFLOW_SIGNING_PASSWORD").write_text(password, encoding="utf-8")
    for file in root.iterdir():
        file.chmod(0o600)
    print("Signing identity created. Upload the two named secret files to GitHub Actions Secrets; keep an encrypted backup of the keystore and password.")


if __name__ == "__main__":
    main()
