#!/usr/bin/env bash
# Prepares Hopi's Maven Central releases, once, on the owner's Mac (docs/RELEASING.md): generates
# the release signing key, sends its public half to the keyservers Maven Central reads, and stores
# the four secrets the release workflow uses in the GitHub repository FelipeKoga/hopi:
#
#   SIGNING_KEY              the ASCII-armored secret key (the release build's signingInMemoryKey)
#   SIGNING_KEY_PASSWORD     its passphrase, generated here (signingInMemoryKeyPassword)
#   MAVEN_CENTRAL_USERNAME   the Central Portal user token's username
#   MAVEN_CENTRAL_PASSWORD   and its password (scripts/central-release.sh)
#
# Needs gpg (brew install gnupg) and gh, signed in with access to the repository (gh auth login).
# It asks for the key's user ID (default "koga (Hopi release signing)": no email unless you type
# one) and for the user token (central.sonatype.com, Account, Generate User Token), then generates
# an RSA 4096 signing key with no expiry. The key stays in your keyring ($GNUPGHOME, ~/.gnupg by
# default), and gpg keeps its revocation certificate in $GNUPGHOME/openpgp-revocs.d; the passphrase
# goes nowhere but the secret. Run again with another user ID (gpg keeps one key per user ID here),
# it makes a new key and replaces the four secrets.
#
# RELEASE_SECRETS_OFFLINE=<directory> is a dry run, for testing this script: it sends nothing to a
# keyserver and runs no gh command, and writes each secret to <directory>/<NAME> instead.
set -euo pipefail

repo=FelipeKoga/hopi
keyservers=(hkps://keyserver.ubuntu.com hkps://keys.openpgp.org)
default_uid="koga (Hopi release signing)"
offline=${RELEASE_SECRETS_OFFLINE:-}

fail() {
    echo "release-secrets.sh: $*" >&2
    exit 1
}

command -v gpg > /dev/null || fail "needs gpg: brew install gnupg"
command -v gh > /dev/null || fail "needs gh: brew install gh"
if [ -n "$offline" ]; then
    [ -d "$offline" ] || fail "RELEASE_SECRETS_OFFLINE=$offline is not a directory"
    echo "Offline run: no keyserver, no gh; the secrets go to $offline." >&2
else
    gh auth status > /dev/null 2>&1 || fail "gh is not signed in: run gh auth login"
    gh repo view "$repo" --json name > /dev/null 2>&1 || fail "gh cannot see $repo with this account"
fi

# Everything asked first, so a typo stops the script before a key exists.
read -r -p "The signing key's user ID [$default_uid]: " uid || true
uid=${uid:-$default_uid}
if gpg --list-keys "=$uid" > /dev/null 2>&1; then
    fail "your keyring has a key for \"$uid\" already: type another user ID, or delete that key first"
fi
read -r -p "Central Portal user token, username: " central_user || true
read -r -s -p "Central Portal user token, password: " central_password || true
echo >&2
[ -n "$central_user" ] && [ -n "$central_password" ] || fail "the user token needs both its username and its password"

# 40 random letters and digits, from 48 random bytes.
random=$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=')
passphrase=${random:0:40}

# The passphrase reaches gpg on a file descriptor, never on a command line.
status=$(mktemp)
trap 'rm -f "$status"' EXIT
echo "Generating an RSA 4096 signing key for \"$uid\"…" >&2
gpg --batch --pinentry-mode loopback --passphrase-fd 3 --status-fd 4 \
    --quick-generate-key "$uid" rsa4096 sign never 3<<< "$passphrase" 4> "$status"
fingerprint=$(sed -n 's/^\[GNUPG:\] KEY_CREATED [BP] \([0-9A-F]*\).*/\1/p' "$status")
[ -n "$fingerprint" ] || fail "gpg reported no new key"
key=$(gpg --batch --pinentry-mode loopback --passphrase-fd 3 --armor --export-secret-keys "$fingerprint" 3<<< "$passphrase")
case "$key" in
    "-----BEGIN PGP PRIVATE KEY BLOCK-----"*) ;;
    *) fail "gpg exported no secret key for $fingerprint" ;;
esac

# Maven Central checks every signature against the public key it finds on these servers.
for server in "${keyservers[@]}"; do
    if [ -n "$offline" ]; then
        echo "Offline: would send $fingerprint to $server" >&2
    else
        gpg --keyserver "$server" --send-keys "$fingerprint" || fail "could not send $fingerprint to $server"
    fi
done

put() {
    if [ -n "$offline" ]; then
        (umask 077 && printf '%s' "$2" > "$offline/$1")
    else
        printf '%s' "$2" | gh secret set "$1" -R "$repo"
    fi
    echo "Set $1" >&2
}
put SIGNING_KEY "$key"
put SIGNING_KEY_PASSWORD "$passphrase"
put MAVEN_CENTRAL_USERNAME "$central_user"
put MAVEN_CENTRAL_PASSWORD "$central_password"

echo "Done: key $fingerprint (\"$uid\"). The release workflow can sign and upload; see docs/RELEASING.md." >&2
