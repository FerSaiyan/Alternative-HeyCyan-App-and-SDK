# Frida Capture Guide — MemoMind Protocol Reverse Engineering

## Purpose
This document describes how to use Frida to capture MemoMind BLE/RFCOMM traffic from the official app (`com.memomind.ai.aphrodite`) on a rooted Android device. Use these methods when you need to map new or unknown protocol commands.

## Prerequisites
- **Rooted Android phone** (Magisk or similar)
- **USB debugging** enabled
- **Linux host** with Python + adb
- The **MemoMind app** installed and paired with glasses

## Setup

### 1. Install Frida client on host
```bash
python3 -m venv frida-venv
source frida-venv/bin/activate
pip install frida-tools
frida --version
# Note the version number, e.g. 17.11.0
```

### 2. Download matching frida-server for your phone's ABI
```bash
adb shell getprop ro.product.cpu.abi
# Typically: arm64-v8a

cd /tmp
VERSION=17.11.0  # match your frida --version output
wget "https://github.com/frida/frida/releases/download/${VERSION}/frida-server-${VERSION}-android-arm64.xz"
unxz "frida-server-${VERSION}-android-arm64.xz"
mv "frida-server-${VERSION}-android-arm64" frida-server
chmod +x frida-server
```

### 3. Push and start frida-server on device (as root)
```bash
adb push frida-server /data/local/tmp/frida-server
adb shell su -c "chmod 755 /data/local/tmp/frida-server"
adb shell su -c "pkill frida-server || true"
adb shell su -c "/data/local/tmp/frida-server >/dev/null 2>&1 &"
```

### 4. Verify Frida can see the device
```bash
frida-ps -U
# Should show a list of running processes
```

## The Bluetooth Logger Script

Save as `memomind_bt_logger.js`:

```javascript
'use strict';

function now() {
  return new Date().toISOString();
}

function jbytesToArray(b) {
  if (b === null || b === undefined) return [];
  const out = [];
  for (let i = 0; i < b.length; i++) {
    let v = b[i];
    if (v < 0) v += 256;
    out.push(v);
  }
  return out;
}

function hex(arr) {
  return arr.map(v => ('0' + v.toString(16)).slice(-2)).join(' ');
}

function ascii(arr) {
  return arr.map(v => (v >= 32 && v <= 126) ? String.fromCharCode(v) : '.').join('');
}

function logLine(line) {
  console.log('[' + now() + '] ' + line);
}

function logBytes(tag, meta, b) {
  const arr = jbytesToArray(b);
  console.log('[' + now() + '] ' + tag + ' ' + meta + '\n' +
    '  len=' + arr.length + '\n' +
    '  hex=' + hex(arr) + '\n' +
    '  ascii=' + ascii(arr));
}

function uuidOf(obj) {
  try {
    if (obj === null || obj === undefined) return 'null';
    return obj.getUuid().toString();
  } catch (e) {
    return 'uuid_error';
  }
}

function deviceInfoFromGatt(gatt) {
  try {
    const dev = gatt.getDevice();
    return 'dev_name=' + dev.getName() + ' dev_addr=' + dev.getAddress();
  } catch (e) {
    return 'dev_unknown';
  }
}

function describeSocket(sock) {
  try {
    const dev = sock.getRemoteDevice();
    return 'dev_name=' + dev.getName() + ' dev_addr=' + dev.getAddress() + ' socket=' + sock.toString();
  } catch (e) {
    return 'socket=' + sock.toString();
  }
}

Java.perform(function () {
  logLine('MemoMind Bluetooth logger loaded');

  const BluetoothGatt = Java.use('android.bluetooth.BluetoothGatt');
  const BluetoothDevice = Java.use('android.bluetooth.BluetoothDevice');
  const BluetoothSocket = Java.use('android.bluetooth.BluetoothSocket');

  // GATT write hooks (in case BLE path is used)
  try {
    const oldWriteChar = BluetoothGatt.writeCharacteristic.overload('android.bluetooth.BluetoothGattCharacteristic');
    oldWriteChar.implementation = function (ch) {
      logBytes('GATT_WRITE_CHAR', deviceInfoFromGatt(this) + ' char_uuid=' + uuidOf(ch) + ' writeType=' + ch.getWriteType() + ' api=old', ch.getValue());
      return oldWriteChar.call(this, ch);
    };
  } catch (e) {}

  try {
    const newWriteChar = BluetoothGatt.writeCharacteristic.overload('android.bluetooth.BluetoothGattCharacteristic', '[B', 'int');
    newWriteChar.implementation = function (ch, value, writeType) {
      logBytes('GATT_WRITE_CHAR', deviceInfoFromGatt(this) + ' char_uuid=' + uuidOf(ch) + ' writeType=' + writeType + ' api=new', value);
      return newWriteChar.call(this, ch, value, writeType);
    };
  } catch (e) {}

  // BluetoothSocket / RFCOMM hooks (primary transport)
  try {
    BluetoothGatt.writeDescriptor.overload('android.bluetooth.BluetoothGattDescriptor').implementation = function (desc) {
      logBytes('GATT_WRITE_DESC', deviceInfoFromGatt(this) + ' desc_uuid=' + uuidOf(desc) + ' char_uuid=' + uuidOf(desc.getCharacteristic()), desc.getValue());
      return this.writeDescriptor(desc);
    };
  } catch (e) {}

  try {
    BluetoothGatt.setCharacteristicNotification.overload('android.bluetooth.BluetoothGattCharacteristic', 'boolean').implementation = function (ch, enable) {
      logLine('GATT_SET_NOTIFICATION ' + deviceInfoFromGatt(this) + ' char_uuid=' + uuidOf(ch) + ' enable=' + enable);
      return this.setCharacteristicNotification(ch, enable);
    };
  } catch (e) {}

  // Socket creation hooks
  try {
    BluetoothDevice.createL2capChannel.overload('int').implementation = function (psm) {
      logLine('CREATE_L2CAP_CHANNEL name=' + this.getName() + ' addr=' + this.getAddress() + ' psm=' + psm);
      return this.createL2capChannel(psm);
    };
  } catch (e) {}

  try {
    BluetoothDevice.createRfcommSocketToServiceRecord.overload('java.util.UUID').implementation = function (uuid) {
      logLine('CREATE_RFCOMM_SOCKET name=' + this.getName() + ' addr=' + this.getAddress() + ' uuid=' + uuid.toString());
      return this.createRfcommSocketToServiceRecord(uuid);
    };
  } catch (e) {}

  try {
    BluetoothSocket.connect.overload().implementation = function () {
      logLine('BLUETOOTH_SOCKET_CONNECT ' + describeSocket(this));
      return this.connect();
    };
  } catch (e) {}

  // Stream hooks (captures actual data)
  function hookStreamClass(className, kind) {
    try {
      const C = Java.use(className);
      if (kind === 'out') {
        try {
          C.write.overload('[B').implementation = function (b) {
            logBytes('BT_STREAM_WRITE', 'class=' + className + ' api=byte[]', b);
            return this.write(b);
          };
        } catch (e) {}
        try {
          C.write.overload('[B', 'int', 'int').implementation = function (b, off, len) {
            const arr = jbytesToArray(b).slice(off, off + len);
            console.log('[' + now() + '] BT_STREAM_WRITE class=' + className + ' api=byte[],off,len\n' +
              '  len=' + arr.length + '\n' +
              '  hex=' + hex(arr) + '\n' +
              '  ascii=' + ascii(arr));
            return this.write(b, off, len);
          };
        } catch (e) {}
      } else {
        try {
          C.read.overload('[B').implementation = function (b) {
            const n = this.read(b);
            if (n > 0) {
              const arr = jbytesToArray(b).slice(0, n);
              console.log('[' + now() + '] BT_STREAM_READ class=' + className + ' api=byte[]\n' +
                '  len=' + arr.length + '\n' +
                '  hex=' + hex(arr) + '\n' +
                '  ascii=' + ascii(arr));
            }
            return n;
          };
        } catch (e) {}
        try {
          C.read.overload('[B', 'int', 'int').implementation = function (b, off, len) {
            const n = this.read(b, off, len);
            if (n > 0) {
              const arr = jbytesToArray(b).slice(off, off + n);
              console.log('[' + now() + '] BT_STREAM_READ class=' + className + ' api=byte[],off,len\n' +
                '  len=' + arr.length + '\n' +
                '  hex=' + hex(arr) + '\n' +
                '  ascii=' + ascii(arr));
            }
            return n;
          };
        } catch (e) {}
      }
      logLine('Hooked stream class ' + className);
    } catch (e) {}
  }

  hookStreamClass('android.bluetooth.BluetoothOutputStream', 'out');
  hookStreamClass('android.bluetooth.BluetoothInputStream', 'in');
});
```

## Running a Capture

### Spawn the app under Frida
```bash
# Force-stop the app first
adb shell am force-stop com.memomind.ai.aphrodite

# Spawn with logger attached
frida -U -f com.memomind.ai.aphrodite -l memomind_bt_logger.js | tee memomind_capture.log
```

> **Note:** If `--no-pause` is not available in your version, just type `%resume` in the Frida console to let the app resume.

### Capture best practices
1. **Do one action at a time** with clear gaps (5-10 seconds) between actions
2. **Note the wall clock time** before each action to correlate with log timestamps
3. **Start with simplest actions first**, then layer complexity

Recommended action order:
```
1. Open app only (capture connection handshake)
2. Tap battery/status screen
3. Send one notification (from another app)
4. Open teleprompter
5. Start teleprompter
6. Pause teleprompter
7. Stop teleprompter (may clear display)
8. Open recorder
9. Start recording
10. Pause recording
11. Stop recording
12. Activate voice assistant ("Hi Memo")
13. Ask a weather question
```

## What to Look For in the Log

### Connection phase
Look for `CREATE_RFCOMM_SOCKET` lines showing which UUIDs are used:
```
CREATE_RFCOMM_SOCKET name=MemoMind One 209 addr=XX:XX:XX:XX:1E:98 uuid=00001101-0000-1000-8000-00805f9b34fb
```

### Command writes (phone → glasses)
Look for `BT_STREAM_WRITE` entries. The frame format is:
```
fa 00 00 <len> <seq> <group> <opcode> <type> [payload...]
```

### Data reads (glasses → phone)
Look for `BT_STREAM_READ` entries. Two types exist:
- Ack/response frames: `fa 00 00 0e <seq> <group> <opcode> 06 ...`
- Binary data (audio): `52 91/92 ...`

### Decoding tips
- **JSON payloads**: ASCII decode the payload bytes to see the JSON
- **Binary formats**: Compare hex across similar actions to spot patterns
- **Sequence numbers**: Track which request matches which response
- **Type byte**: `0x06` = success ack, `0x08` = push/write, `0x01` = empty request, `0x02` = response

## Analyzing a Capture

After a capture session, use these approaches:

### 1. Extract all writes
```bash
grep "BT_STREAM_WRITE" memomind_capture.log -A 3
```

### 2. Find JSON payloads
```bash
grep -o '{".*}' memomind_capture.log | python3 -m json.tool 2>/dev/null || grep -o '{".*}' memomind_capture.log
```

### 3. Time-based separation
Use timestamps to group actions:
```bash
grep -n "2026-06-09T17:38" memomind_capture.log | head -30
```

## Troubleshooting

### Frida won't spawn the app
```bash
# Try attach to already-running app instead
adb shell monkey -p com.memomind.ai.aphrodite 1
sleep 2
frida-ps -U | grep MemoMind
# Note the PID, then:
frida -U -p <PID> -l memomind_bt_logger.js
```

### No Bluetooth traffic captured
- Check frida-server is running as root: `adb shell ps -A | grep frida`
- Verify Classis Bluetooth is ON (not just BLE_ON)
- The app must be **connected to glasses** for traffic to appear

### Only see GATT hooks, not socket hooks
The app may be using BLE on your device/version. Check the log for:
- `CREATE_RFCOMM_SOCKET` — if absent, BLE path is primary
- `GATT_WRITE_CHAR` — captures BLE writes instead

### Logger process stops unexpectedly
Run the capture with `nohup` and append to log file:
```bash
setsid -f bash -c "printf '%resume\n' | frida -U -f com.memomind.ai.aphrodite -l memomind_bt_logger.js >> capture.log 2>&1"
```

## Display Primitive Investigation (Work in Progress)

To map drawing commands (lines, circles, rectangles, images), you need to trigger the app features that use them. Likely trigger points in the MemoMind app:

| Feature | Expected Drawing | How to trigger |
|---------|-----------------|----------------|
| Teleprompter text rendering | DrawText at position | Start/stop teleprompter with formatted text |
| Notification display | DrawText for title + body | Receive a notification |
| Weather card | DrawText + DrawImage/DrawPath | View weather in app |
| Calendar component | DrawText + DrawRect | Sync calendar events |
| News component | DrawText + DrawImage | View news in app |
| Onboarding/wizard | Various DrawCommands | Run through setup wizard |
| Screen drawing/notes | DrawPath for freehand | Use note-taking feature (if any) |

**Recommended next capture:**
1. Trigger each feature listed above individually
2. Look for new `group` or `opcode` values not in the known mapping
3. Compare the hex payloads to infer opcode byte values for DrawCommand variants

The relevant code addresses in `libapp.so`:
- `_readDrawImage@3044314182` at `0x0be3cd`
- `_readDrawText@3044314182` at `0x0eea57`
- `_readDrawPath@3044314182` at `0x144ba7`
- `_readDrawVertices@3044314182` at `0x20df33`
- `_addCommandsTag@3044314182` at `0x22c436` (writes the opcode byte)
