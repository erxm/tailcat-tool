#!/usr/bin/env bash
set -euo pipefail

TASK_APP="${1:?Usage: sign-app.sh APP_PATH}"
TASK_IDENTIFIER="${2:-cc.coauto.tailcat-manager}"
if [[ -n "${TAILCAT_SIGNING_IDENTITY:-}" ]]; then
    codesign --force --sign "$TAILCAT_SIGNING_IDENTITY" --identifier "$TASK_IDENTIFIER" "$TASK_APP"
else
    # Keep a reusable development identity outside the checkout and app bundle.
    /usr/bin/python3 - "$TASK_APP" "$TASK_IDENTIFIER" <<'PY'
import hashlib
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
import tempfile

def run(*args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace").strip())
    return result.stdout

root = Path.home() / "Library/Application Support/TailcatManager/Signing"
if root.is_symlink():
    raise RuntimeError("Signing directory must not be a symlink")
root.mkdir(parents=True, exist_ok=True, mode=0o700)
root.chmod(0o700)
keychain = root / "LocalSigning.keychain-db"
password_file = root / "keychain-password"
certificate = root / "certificate.pem"
for path in (keychain, password_file, certificate):
    if path.is_symlink():
        raise RuntimeError("Signing files must not be symlinks")

if not keychain.exists():
    if password_file.exists() or certificate.exists():
        raise RuntimeError("Incomplete local signing identity; preserve or restore the Signing directory")
    password = secrets.token_hex(32)
    previous = shlex.split(run("/usr/bin/security", "list-keychains", "-d", "user").decode())
    try:
        with tempfile.TemporaryDirectory(prefix="tailcat-signing-") as temporary:
            folder = Path(temporary)
            config = folder / "openssl.cnf"
            config.write_text("""[req]
distinguished_name = dn
x509_extensions = extensions
prompt = no
[dn]
CN = Tailcat Manager Local Development
[extensions]
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyCertSign
extendedKeyUsage = critical,codeSigning
""")
            key = folder / "signing.key"
            cert = folder / "certificate.pem"
            bundle = folder / "identity.p12"
            passfile = folder / "password"
            passfile.write_text(password)
            passfile.chmod(0o600)
            run("/usr/bin/openssl", "req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-days", "3650", "-config", str(config), "-keyout", str(key), "-out", str(cert))
            key.chmod(0o600)
            run("/usr/bin/openssl", "pkcs12", "-export", "-inkey", str(key), "-in", str(cert),
                "-out", str(bundle), "-passout", "file:" + str(passfile))
            run("/usr/bin/security", "create-keychain", "-p", password, str(keychain))
            run("/usr/bin/security", "import", str(bundle), "-k", str(keychain), "-P", password,
                "-x", "-T", "/usr/bin/codesign")
            certificate.write_bytes(cert.read_bytes())
            password_file.write_text(password)
            password_file.chmod(0o600)
    finally:
        # Creating a keychain adds it to the search list; do not change the user's list.
        run("/usr/bin/security", "list-keychains", "-d", "user", "-s", *previous)
else:
    if not password_file.is_file() or not certificate.is_file():
        raise RuntimeError("Local signing identity is incomplete; do not silently replace it")
    password = password_file.read_text().strip()

keychain.chmod(0o600)
run("/usr/bin/security", "unlock-keychain", "-p", password, str(keychain))
run("/usr/bin/security", "set-keychain-settings", "-lut", "21600", str(keychain))
der = run("/usr/bin/openssl", "x509", "-in", str(certificate), "-outform", "DER")
fingerprint = hashlib.sha1(der).hexdigest().upper()
identifier = sys.argv[2]
if not identifier or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-" for c in identifier):
    raise RuntimeError("Invalid signing identifier")
requirement = '=designated => identifier "' + identifier + '" and anchor H"' + fingerprint + '"'
run("/usr/bin/codesign", "--force", "--sign", fingerprint, "--keychain", str(keychain),
    "--identifier", identifier, "--timestamp=none", "--requirements", requirement, sys.argv[1])
print("Signed with the persistent local Tailcat development identity")
PY
fi
codesign --verify --deep --strict "$TASK_APP"
