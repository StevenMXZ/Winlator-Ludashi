#!/usr/bin/env bash
# CI-only download fallback; the project's wrapper still verifies and runs Gradle.
set -euo pipefail
archive="$RUNNER_TEMP/gradle-8.10.2-bin.zip"
checksum="31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"
verified=false
for source in \
  "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip" \
  "https://mirrors.huaweicloud.com/gradle/gradle-8.10.2-bin.zip"; do
  if curl --fail --location --retry 2 --retry-all-errors --retry-delay 3 \
    --connect-timeout 15 --max-time 150 "$source" -o "$archive" && \
    printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check -; then
    verified=true
    break
  fi
done
if [ "$verified" != true ]; then
  echo '::error::Could not download the pinned Gradle distribution from either source.'
  exit 1
fi
# Only the checked-out CI copy changes; checksum validation remains enabled.
python3 - "$archive" <<'PY'
from pathlib import Path
import sys
p = Path('gradle/wrapper/gradle-wrapper.properties')
lines = p.read_text().splitlines()
assert 'distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26' in lines
p.write_text('\n'.join('distributionUrl=' + Path(sys.argv[1]).resolve().as_uri() if line.startswith('distributionUrl=') else line for line in lines) + '\n')
PY
