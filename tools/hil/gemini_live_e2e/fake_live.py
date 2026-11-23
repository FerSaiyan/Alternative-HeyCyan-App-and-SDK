"""Fake Gemini Live server for CyanBridge emulator E2E tests (debug endpoint override).

WebSocket :18900 speaks enough of BidiGenerateContent: setup -> setupComplete, records
realtimeInput audio/video and clientContent, toolResponse; control port :18901 (one command
per line): `toolcall end_session`, `toolcall phone_task <task>`, `reply <text>`, `status`.
Events go to events.jsonl; every received image is saved under frames/ with its arrival time.
"""
import argparse, asyncio, base64, json, os, time, uuid
import websockets

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=18900)
ap.add_argument("--control-port", type=int, default=18901)
ap.add_argument("--out", default="/tmp/live-e2e/run")
a = ap.parse_args()
os.makedirs(os.path.join(a.out, "frames"), exist_ok=True)
EVENTS = open(os.path.join(a.out, "events.jsonl"), "a", buffering=1)
state = {"ws": None, "audio_packets": 0, "audio_bytes": 0, "frames": 0, "tool_responses": [], "setups": 0}


def log(kind, **kw):
    rec = {"t": round(time.time(), 3), "kind": kind, **kw}
    EVENTS.write(json.dumps(rec) + "\n")
    print(json.dumps(rec), flush=True)


async def send(obj):
    ws = state["ws"]
    if ws is None:
        return False
    await ws.send(json.dumps(obj))
    return True


async def handle(ws):
    state["ws"] = ws
    log("connect", path=getattr(ws, "request", None) and ws.request.path)
    try:
        async for raw in ws:
            msg = json.loads(raw)
            if "setup" in msg:
                state["setups"] += 1
                tools = [d["name"] for t in msg["setup"].get("tools", []) for d in t.get("functionDeclarations", [])]
                json.dump(msg["setup"], open(os.path.join(a.out, f"setup_{state['setups']}.json"), "w"), indent=1)
                log("setup", tools=tools, model=msg["setup"].get("model"))
                await ws.send(json.dumps({"setupComplete": {}}))
            elif "realtimeInput" in msg:
                rt = msg["realtimeInput"]
                if "audio" in rt:
                    pcm = base64.b64decode(rt["audio"]["data"])
                    state["audio_packets"] += 1
                    state["audio_bytes"] += len(pcm)
                    n = len(pcm) // 2
                    if n:
                        mean_abs = sum(abs(int.from_bytes(pcm[i:i + 2], "little", signed=True)) for i in range(0, n * 2, 2)) // n
                        sec = int(time.time())
                        if sec != state.get("energy_sec"):
                            if state.get("energy_sec"):
                                log("audio_energy", sec=state["energy_sec"], max_mean_abs=state["energy_max"])
                            state["energy_sec"], state["energy_max"] = sec, 0
                        state["energy_max"] = max(state["energy_max"], mean_abs)
                if "video" in rt:
                    state["frames"] += 1
                    data = base64.b64decode(rt["video"]["data"])
                    name = f"frame_{state['frames']:03d}_{int(time.time() * 1000)}.jpg"
                    open(os.path.join(a.out, "frames", name), "wb").write(data)
                    log("video", file=name, bytes=len(data))
                if "audioStreamEnd" in rt:
                    log("audio_stream_end")
            elif "clientContent" in msg:
                parts = msg["clientContent"]["turns"][0]["parts"]
                for p in parts:
                    if "inlineData" in p:
                        state["frames"] += 1
                        data = base64.b64decode(p["inlineData"]["data"])
                        name = f"frame_{state['frames']:03d}_{int(time.time() * 1000)}_turn.jpg"
                        open(os.path.join(a.out, "frames", name), "wb").write(data)
                        log("image_turn", file=name, bytes=len(data))
                    elif "text" in p:
                        log("text_turn", chars=len(p["text"]))
            elif "toolResponse" in msg:
                for r in msg["toolResponse"]["functionResponses"]:
                    state["tool_responses"].append(r)
                    log("tool_response", id=r["id"], name=r["name"], response=r["response"])
            else:
                log("other", keys=list(msg))
    except websockets.ConnectionClosed as e:
        log("closed", code=e.code)
    finally:
        if state["ws"] is ws:
            state["ws"] = None
        log("disconnect", audio_packets=state["audio_packets"], frames=state["frames"])


async def model_reply(text):
    # 0.4 s of quiet 24 kHz PCM16 so the app plays something, then the turn ends.
    pcm = base64.b64encode(b"\x00\x00" * 9600).decode()
    await send({"serverContent": {"outputTranscription": {"text": text}}})
    await send({"serverContent": {"modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": pcm}}]}}})
    await asyncio.sleep(0.5)
    await send({"serverContent": {"turnComplete": True}})


async def control(reader, writer):
    while line := (await reader.readline()).decode().strip():
        cmd, _, rest = line.partition(" ")
        if cmd == "toolcall":
            name, _, task = rest.partition(" ")
            call = {"id": "call-" + uuid.uuid4().hex[:8], "name": name}
            if name == "phone_task":
                call["args"] = {"task": task or "open Spotify"}
            ok = await send({"toolCall": {"functionCalls": [call]}})
            log("sent_toolcall", name=name, id=call["id"], ok=ok)
            reply = {"ok": ok, "id": call["id"]}
        elif cmd == "reply":
            await model_reply(rest or "Okay.")
            reply = {"ok": True}
        elif cmd == "status":
            reply = {k: v for k, v in state.items() if k != "ws"} | {"connected": state["ws"] is not None}
        else:
            reply = {"error": "unknown command"}
        writer.write((json.dumps(reply) + "\n").encode())
        await writer.drain()
    writer.close()


async def main():
    async with websockets.serve(handle, "0.0.0.0", a.port, max_size=8 * 1024 * 1024):
        server = await asyncio.start_server(control, "127.0.0.1", a.control_port)
        log("listening", port=a.port, control=a.control_port)
        async with server:
            await asyncio.Future()

asyncio.run(main())
