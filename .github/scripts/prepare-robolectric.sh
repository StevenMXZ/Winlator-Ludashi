#!/usr/bin/env bash
set -euo pipefail
version=9-robolectric-4913185-2-i7
artifact=android-all-instrumented
dir="$HOME/.m2/repository/org/robolectric/$artifact/$version"
mkdir -p "$dir"
base="https://repo.maven.apache.org/maven2/org/robolectric/$artifact/$version"
for ext in pom jar; do
  name="$artifact-$version.$ext"
  if [ -s "$dir/$name" ] && [ -s "$dir/$name.sha512" ] &&
      (cd "$dir" && printf '%s  %s\n' "$(cat "$name.sha512")" "$name" | sha512sum -c -); then
    continue
  fi
  curl --fail --location --retry 6 --retry-max-time 600 --connect-timeout 20 "$base/$name.sha512" -o "$dir/$name.sha512.tmp"
  curl --fail --location --retry 6 --retry-max-time 600 --connect-timeout 20 "$base/$name" -o "$dir/$name.tmp"
  (cd "$dir" && printf '%s  %s\n' "$(cat "$name.sha512.tmp")" "$name.tmp" | sha512sum -c -)
  mv "$dir/$name.tmp" "$dir/$name"
  mv "$dir/$name.sha512.tmp" "$dir/$name.sha512"
done
