#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s DEVICE_SERIAL enable|disable\n' "$0" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
serial=$1
action=$2
[[ -n "$serial" ]] || usage
[[ "$action" == enable || "$action" == disable ]] || usage

ADB=${ADB:-adb}
package=sh.rrvsh.eleventwosixty
service="$package/$package.HomeRedirectAccessibilityService"

"$ADB" -s "$serial" get-state >/dev/null

read_services() {
  local value
  value=$("$ADB" -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')
  if [[ "$value" == null ]]; then
    value=
  fi
  printf '%s' "$value"
}

remove_own_service() {
  local current=$1 entry updated=
  IFS=: read -r -a entries <<< "$current"
  for entry in "${entries[@]}"; do
    [[ -z "$entry" || "$entry" == "$service" ]] && continue
    if [[ -n "$updated" ]]; then
      updated+=:
    fi
    updated+=$entry
  done
  printf '%s' "$updated"
}

current=$(read_services)
without_own=$(remove_own_service "$current")

if [[ "$action" == enable ]]; then
  if [[ -n "$without_own" ]]; then
    updated="$without_own:$service"
  else
    updated=$service
  fi
  "$ADB" -s "$serial" shell appops set "$package" GET_USAGE_STATS allow
  "$ADB" -s "$serial" shell settings put secure enabled_accessibility_services "$updated"
  "$ADB" -s "$serial" shell settings put secure accessibility_enabled 1
  printf 'Enabled Home redirect prerequisites for %s on %s.\n' "$package" "$serial"
else
  "$ADB" -s "$serial" shell appops set "$package" GET_USAGE_STATS default
  if [[ -n "$without_own" ]]; then
    "$ADB" -s "$serial" shell settings put secure enabled_accessibility_services "$without_own"
  else
    "$ADB" -s "$serial" shell settings delete secure enabled_accessibility_services
    "$ADB" -s "$serial" shell settings put secure accessibility_enabled 0
  fi
  printf 'Removed only eleventwosixty accessibility and usage access on %s.\n' "$serial"
fi
