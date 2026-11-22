#!/usr/bin/env bash
# Test the local-models NPU catalog on the emulator (docs/NPU_ON_DEVICE.md, "Emulator: faking the SoC").
# Boots the read-only Pixel_9a (Magisk-rooted, x86_64) with 8 GB (the NPU entry needs 6 GB), fakes the SoC (ro.soc.*)
# and model with resetprop, restarts zygote so apps read the new android.os.Build values, enables the debug-build
# switch debug.cyanbridge.npu_download_test (download without the NPU runtime; loading stays refused) and installs
# the APK. Hold the device lane first; never run it with Gradle. Leaves the AVD running (stop: adb emu kill).
#   usage: npu_soc_mock.sh <debug apk> [SOC_MODEL=SM8650] [SOC_MANUFACTURER=QTI] [MODEL=SM-F956B]
set -u
APK=$1; SOC=${2:-SM8650}; MAN=${3:-QTI}; MODEL=${4:-SM-F956B}
S=emulator-5562; EMU=$HOME/Android/Sdk/emulator/emulator; WORK=${WORK:-$HOME/.cache/cyanbridge-npu-soc-mock}; mkdir -p $WORK
bw() { for i in $(seq 1 60); do [ "$(adb -s $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0; sleep 5; done; return 1; }
adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
(setsid "$EMU" -avd Pixel_9a -read-only -memory 8192 -port 5562 -no-window -no-audio -no-boot-anim -no-snapshot \
   -gpu swiftshader_indirect > $WORK/emu.log 2>&1 & echo $! > $WORK/emu.pid)
bw || { echo boot timeout; exit 3; }
echo "before: soc=$(adb -s $S shell getprop ro.soc.model | tr -d '\r') model=$(adb -s $S shell getprop ro.product.model | tr -d '\r')"
adb -s $S shell "su -c 'which resetprop; magisk -v'" 2>&1 | head -3
adb -s $S shell "su -c 'resetprop ro.soc.model $SOC; resetprop ro.soc.manufacturer $MAN; resetprop ro.product.model $MODEL'"
echo "props now: soc=$(adb -s $S shell getprop ro.soc.model | tr -d '\r')/$(adb -s $S shell getprop ro.soc.manufacturer | tr -d '\r') model=$(adb -s $S shell getprop ro.product.model | tr -d '\r')"
old=$(adb -s $S shell pidof system_server | tr -d '\r')
adb -s $S shell "su -c 'setprop ctl.restart zygote'"
for i in $(seq 1 60); do sleep 3; new=$(adb -s $S shell pidof system_server 2>/dev/null | tr -d '\r'); [ -n "$new" ] && [ "$new" != "$old" ] && break; done
for i in $(seq 1 40); do sleep 3; adb -s $S shell cmd package list packages >/dev/null 2>&1 && break; done; sleep 20
echo "system_server $old -> $new"
adb -s $S shell setprop debug.cyanbridge.npu_download_test 1
adb -s $S shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1
adb -s $S uninstall com.fersaiyan.cyanbridge >/dev/null 2>&1; adb -s $S install -g "$APK" | tail -1
echo "free RAM: $(adb -s $S shell "awk '/MemTotal/ {print \$2}' /proc/meminfo" | tr -d '\r') kB"
