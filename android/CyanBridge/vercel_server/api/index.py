from __future__ import annotations

import importlib.util
import shutil
from pathlib import Path


STAGE_ROOT = Path(__file__).resolve().parents[1]
SOURCE_APP = STAGE_ROOT / "_local_termux_server" / "app.py"
RUNTIME_ROOT = Path("/tmp/cyanbridge_vercel_runtime")
RUNTIME_APP = RUNTIME_ROOT / "app.py"

RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
if not RUNTIME_APP.exists() or SOURCE_APP.stat().st_mtime > RUNTIME_APP.stat().st_mtime:
    shutil.copy2(SOURCE_APP, RUNTIME_APP)

spec = importlib.util.spec_from_file_location("cyanbridge_vercel_runtime", RUNTIME_APP)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load CyanBridge server from {RUNTIME_APP}")

module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
app = module.app
