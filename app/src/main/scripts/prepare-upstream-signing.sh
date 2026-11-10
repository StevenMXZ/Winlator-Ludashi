#!/usr/bin/env bash
# One private signing identity for the new upstream update line. Never publish this asset publicly.
set -euo pipefail
sign_dir="${RUNNER_TEMP:?}/upstream-signing"
mkdir -p "$sign_dir"
if [ -n "${LUDASHI_KEYSTORE_B64:-}" ]; then
    printf '%s' "$LUDASHI_KEYSTORE_B64" | base64 -d > "$sign_dir/upstream.p12"
    : "${LUDASHI_STORE_PASSWORD:?Set the signing password secret}"
else
    test "$(gh api "repos/$GITHUB_REPOSITORY" --jq .private)" = true || {
        echo 'Public source repositories must provide private signing secrets.' >&2; exit 1;
    }
    # Listing succeeds before deciding the identity does not exist. A network/permission failure
    # must never be mistaken for a missing key and silently generate a replacement.
    tag=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases?per_page=100" --jq '.[] | select(.tag_name=="upstream-signing") | .tag_name')
    if [ -z "$tag" ]; then
        signing_pass=$(openssl rand -hex 24)
        echo "::add-mask::$signing_pass"
        keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$sign_dir/upstream.p12" \
            -alias upstream -keyalg RSA -keysize 3072 -validity 10000 \
            -dname 'CN=Winlator Ludashi Upstream' -storepass "$signing_pass" -keypass "$signing_pass"
        printf '%s' "$signing_pass" > "$sign_dir/password.txt"
        gh release create upstream-signing --repo "$GITHUB_REPOSITORY" --draft \
            --title 'Private upstream signing identity - do not publish' \
            --notes 'Private build material for the upstream update line. Preserve both assets.' \
            "$sign_dir/upstream.p12" "$sign_dir/password.txt"
    else
        gh release download upstream-signing --repo "$GITHUB_REPOSITORY" --dir "$sign_dir" \
            --pattern upstream.p12 --pattern password.txt
    fi
    LUDASHI_STORE_PASSWORD=$(cat "$sign_dir/password.txt")
fi
echo "::add-mask::$LUDASHI_STORE_PASSWORD"
keytool -list -keystore "$sign_dir/upstream.p12" -storepass "$LUDASHI_STORE_PASSWORD" -alias upstream >/dev/null
{
    echo "LUDASHI_KEYSTORE_PATH=$sign_dir/upstream.p12"
    echo "LUDASHI_STORE_PASSWORD=$LUDASHI_STORE_PASSWORD"
} >> "${GITHUB_ENV:?}"
