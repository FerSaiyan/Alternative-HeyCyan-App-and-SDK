"""Stream a 16 kHz mono PCM16 WAV into the emulator's virtual microphone (EmulatorController.injectAudio).

Hand-encoded protobuf (emulator/lib/emulator_controller.proto), so only grpcio is needed.
usage: inject_audio.py WAV [--port 5584]   (gRPC port and token from the emulator's discovery file)
"""
import argparse, glob, os, time, wave
import grpc


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(num, wire, payload):
    key = varint((num << 3) | wire)
    if wire == 0:
        return key + varint(payload)
    return key + varint(len(payload)) + payload


def packet(pcm, rate, first):
    msg = b""
    if first:  # AudioFormat{samplingRate, channels=Mono(0), format=S16(1), mode=REAL_TIME(1)}
        fmt = field(1, 0, rate) + field(3, 0, 1) + field(4, 0, 1)
        msg += field(1, 2, fmt)
    msg += field(2, 0, int(time.time() * 1e6)) + field(3, 2, pcm)
    return msg


def discovery(port):
    for ini in glob.glob(os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "avd/running/pid_*.ini")):
        kv = dict(l.strip().split("=", 1) for l in open(ini) if "=" in l)
        if kv.get("port.serial") == str(port):
            return kv["grpc.port"], kv.get("grpc.token")
    return str(port + 3000), None  # started with -grpc <console port + 3000> (insecure, localhost only)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--port", type=int, default=5584)
    ap.add_argument("--lead-ms", type=int, default=0, help="silence before the clip")
    a = ap.parse_args()
    gport, token = discovery(a.port)
    w = wave.open(a.wav)
    assert w.getnchannels() == 1 and w.getsampwidth() == 2, "16-bit mono WAV expected"
    rate, data = w.getframerate(), w.readframes(w.getnframes())
    data = b"\0\0" * (rate * a.lead_ms // 1000) + data + b"\0\0" * (rate // 2)
    chunk = rate // 50 * 2  # 20 ms

    def packets():
        t0 = time.time()
        for i, off in enumerate(range(0, len(data), chunk)):
            yield packet(data[off:off + chunk], rate, i == 0)
            delay = t0 + (i + 1) * 0.02 - time.time()
            if delay > 0:
                time.sleep(delay)

    channel = grpc.insecure_channel(f"localhost:{gport}")
    call = channel.stream_unary("/android.emulation.control.EmulatorController/injectAudio",
                                request_serializer=lambda b: b, response_deserializer=lambda b: b)
    md = [("authorization", f"Bearer {token}")] if token else None
    for attempt in range(3):  # the emulator occasionally resets a fresh stream; retry at once
        try:
            call(packets(), metadata=md, timeout=120)
            return
        except grpc.RpcError as e:
            if attempt == 2:
                raise
            print("injectAudio retry:", e.code(), flush=True)


if __name__ == "__main__":
    main()
