#!/usr/bin/env bash
# Print the selected serial on stdout; keep progress messages on stderr.
set -euo pipefail

action=${1:-}
shift || true
adb_command=adb
emulator_command=emulator
sdk_directory=
requested_name=
requested_serial=
boot_timeout=180
startup_log=/tmp/pandastic-emulator.log
while [ "$#" -gt 0 ]; do
    [ "$#" -ge 2 ] || { echo "Missing value for $1" >&2; exit 1; }
    case "$1" in
        --adb) adb_command=$2 ;;
        --emulator) emulator_command=$2 ;;
        --sdk) sdk_directory=$2 ;;
        --name) requested_name=$2 ;;
        --device) requested_serial=$2 ;;
        --timeout) boot_timeout=$2 ;;
        --log) startup_log=$2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift 2
done
fail() { echo "$*" >&2; exit 1; }
command -v "$adb_command" >/dev/null || fail "adb not found: $adb_command. Set SDK_DIR or ADB to your installed SDK."
command -v timeout >/dev/null || fail "GNU timeout is required for bounded emulator startup checks."
[[ "$boot_timeout" =~ ^[1-9][0-9]*$ ]] || fail "BOOT_TIMEOUT must be a positive number of seconds."
[[ "$action" == start || "$action" == stop ]] || fail "Usage: android-emulator.sh start|stop [options]"
[[ -z "$requested_serial" || "$requested_serial" == emulator-* ]] || fail "make run/stop selects emulators. Use make run-device for a phone."

device_list=$(timeout 10 "$adb_command" devices) || fail "Cannot contact ADB. Check $adb_command devices."
running_serials=()
while read -r serial state rest; do
    if [[ "$serial" == emulator-* && ( "$state" == device || "$state" == offline ) ]]; then
        running_serials+=("$serial")
    fi
done <<< "$device_list"

avd_name() {
    local response
    response=$(timeout 5 "$adb_command" -s "$1" emu avd name 2>/dev/null) || return 1
    response=${response//$'\r'/}
    printf '%s\n' "$response" | sed '/^OK$/d; /^$/d' | head -1
}

basic_lab_avd=pandastic_basic
selected_serial=
if [[ -n "$requested_serial" ]]; then
    for serial in "${running_serials[@]}"; do
        [[ "$serial" != "$requested_serial" ]] || selected_serial=$serial
    done
    [[ -n "$selected_serial" ]] || fail "Emulator $requested_serial is unavailable. Check $adb_command devices."
elif [[ -n "$requested_name" ]]; then
    for serial in "${running_serials[@]}"; do
        if [[ "$(avd_name "$serial" || true)" == "$requested_name" ]]; then
            [[ -z "$selected_serial" ]] || fail "Multiple emulators run $requested_name. Select DEVICE=emulator-5554."
            selected_serial=$serial
        fi
    done
else
    # The SMS lab's second phone (AVD pandastic_basic, `make emulator-basic`) is never the default target.
    candidates=()
    for serial in "${running_serials[@]}"; do
        [[ "$(avd_name "$serial" || true)" == "$basic_lab_avd" ]] || candidates+=("$serial")
    done
    if [[ ${#candidates[@]} -eq 1 ]]; then
        selected_serial=${candidates[0]}
    elif [[ ${#candidates[@]} -gt 1 ]]; then
        fail "Multiple emulators are running: ${candidates[*]}. Select DEVICE=emulator-5554 or EMULATOR_NAME=Your_AVD."
    fi
fi

if [[ "$action" == stop ]]; then
    if [[ -z "$selected_serial" ]]; then
        echo "No matching running emulator to stop." >&2
        exit 0
    fi
    echo "Stopping emulator $selected_serial..." >&2
    timeout 10 "$adb_command" -s "$selected_serial" emu kill >&2 || fail "Could not stop $selected_serial."
    deadline=$((SECONDS + 20))
    while true; do
        device_list=$(timeout 5 "$adb_command" devices) || fail "Cannot confirm emulator shutdown: ADB is unavailable."
        still_present=false
        while read -r serial state rest; do
            [[ "$serial" != "$selected_serial" ]] || still_present=true
        done <<< "$device_list"
        $still_present || break
        (( SECONDS < deadline )) || fail "ADB still reports $selected_serial after requesting shutdown."
        sleep 1
    done
    exit 0
fi

emulator_pid=
if [[ -z "$selected_serial" ]]; then
    command -v "$emulator_command" >/dev/null || fail "Emulator not found: $emulator_command. Set SDK_DIR or EMULATOR."
    available_names=$(ANDROID_HOME="$sdk_directory" ANDROID_SDK_ROOT="$sdk_directory" "$emulator_command" -list-avds) || fail "Could not list Android virtual devices."
    mapfile -t names < <(printf '%s\n' "$available_names" | tr -d '\r' | sed '/^$/d')
    if [[ -z "$requested_name" && ${#names[@]} -gt 1 ]]; then  # skip the SMS lab's Basic phone AVD
        mapfile -t names < <(printf '%s\n' "${names[@]}" | grep -vx "$basic_lab_avd")
    fi
    if [[ -n "$requested_name" ]]; then
        found=false
        for name in "${names[@]}"; do [[ "$name" != "$requested_name" ]] || found=true; done
        $found || fail "Unknown AVD '$requested_name'. Available AVDs: ${names[*]:-(none)}. Set EMULATOR_NAME to one of those names."
    elif [[ ${#names[@]} -eq 1 ]]; then
        requested_name=${names[0]}
    elif [[ ${#names[@]} -eq 0 ]]; then
        fail "No Android virtual devices exist. Create one in Android Studio, or use make run-device with your Samsung."
    else
        fail "Multiple AVDs exist: ${names[*]}. Select one with make run EMULATOR_NAME=Your_AVD."
    fi
    echo "Starting emulator $requested_name. Startup log: $startup_log" >&2
    # A separate session lets the emulator remain open after Make finishes.
    if command -v setsid >/dev/null; then
        ANDROID_HOME="$sdk_directory" ANDROID_SDK_ROOT="$sdk_directory" nohup setsid "$emulator_command" -avd "$requested_name" > "$startup_log" 2>&1 < /dev/null &
    else
        ANDROID_HOME="$sdk_directory" ANDROID_SDK_ROOT="$sdk_directory" nohup "$emulator_command" -avd "$requested_name" > "$startup_log" 2>&1 < /dev/null &
    fi
    emulator_pid=$!
else
    echo "Using emulator $selected_serial." >&2
fi

echo "Waiting for emulator (up to $boot_timeout seconds)..." >&2
deadline=$((SECONDS + boot_timeout))
while (( SECONDS < deadline )); do
    if [[ -z "$selected_serial" ]]; then
        device_list=$(timeout 5 "$adb_command" devices 2>/dev/null || true)
        while read -r serial state rest; do
            if [[ "$serial" == emulator-* && "$state" == device && "$(avd_name "$serial" || true)" == "$requested_name" ]]; then
                selected_serial=$serial
                break
            fi
        done <<< "$device_list"
    fi
    if [[ -n "$selected_serial" ]]; then
        booted=$(timeout 5 "$adb_command" -s "$selected_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [[ "$booted" == 1 ]]; then
            package_path=$(timeout 5 "$adb_command" -s "$selected_serial" shell cmd package path android 2>/dev/null || true)
            if [[ "$package_path" == package:* ]]; then
                echo "Emulator $selected_serial is ready." >&2
                printf '%s\n' "$selected_serial"
                exit 0
            fi
        fi
    fi
    if [[ -n "$emulator_pid" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        tail -20 "$startup_log" >&2
        fail "Emulator exited during startup. See $startup_log."
    fi
    sleep 1
done
[[ -z "$emulator_pid" ]] || tail -20 "$startup_log" >&2
fail "Emulator did not become ready in $boot_timeout seconds. Check $startup_log and $adb_command devices."
