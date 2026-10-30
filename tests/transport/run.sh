#!/usr/bin/env sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_root="$repo_root/tests/transport"
test_output=$(mktemp -d "${TMPDIR:-/tmp}/tellono-transport.XXXXXX")
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
javac -d "$test_output" \
  "$test_root/android/net/Network.java" \
  "$test_root/android/util/Log.java" \
  "$test_root/android/os/SystemClock.java" \
  "$repo_root/app/src/main/java/com/duvitech/network/udp/UDPClient.java" \
  "$repo_root/app/src/main/java/com/duvitech/network/udp/UDPStatusServer.java" \
  "$test_root/TransportTest.java"
java -cp "$test_output" TransportTest
