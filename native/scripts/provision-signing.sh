#!/usr/bin/env bash
# Run only as repository administrator. Private material stays outside the checkout.
set -euo pipefail
umask 077
repo=sinicablekaveh-create/ubiquitous-happiness
signing_dir="${EC_PRIVATE_SIGNING_DIR:-$HOME/.local/share/electric-connect/signing}"
mkdir -p "$signing_dir"
if [ ! -f "$signing_dir/upload.jks" ]; then
  python3 - "$signing_dir/password" <<'PY'
import secrets,sys
open(sys.argv[1],'w').write(secrets.token_urlsafe(40))
PY
  keytool -genkeypair -keystore "$signing_dir/upload.jks" -alias electric-connect \
    -storepass:file "$signing_dir/password" -keypass:file "$signing_dir/password" \
    -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Electric Connect'
fi
base64 -w0 "$signing_dir/upload.jks" > "$signing_dir/encoded"
trap 'rm -f "$signing_dir/encoded"' EXIT
gh secret set EC_SIGNING_KEYSTORE_B64 --repo "$repo" < "$signing_dir/encoded"
gh secret set EC_SIGNING_PASSWORD --repo "$repo" < "$signing_dir/password"
echo 'Private key retained locally; encrypted repository secrets configured. Back up this directory securely.'
echo 'This NEW key cannot upgrade an older APK signed with another certificate.'
