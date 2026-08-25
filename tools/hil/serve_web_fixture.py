#!/usr/bin/env python3
"""Deterministic local web fixture for CyanBridge's Chrome/Tasker HIL tests."""

from __future__ import annotations

import argparse
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

SEARCH_MARKER = "CYANBRIDGE_HIL_WEB_SEARCH_72941"
RESULTS_MARKER = "CYANBRIDGE_HIL_WEB_RESULTS_72941"
ARTICLE_MARKER = "CYANBRIDGE_HIL_WEB_ARTICLE_72941"
SMARTGLASSES_MARKER = "CYANBRIDGE_HIL_SMARTGLASSES_NEWS_88417"


def page(title: str, body: str) -> bytes:
    document = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)}</title>
  <style>
    body {{ font-family: sans-serif; max-width: 760px; margin: 24px auto; padding: 0 20px; line-height: 1.5; }}
    input, button {{ font: inherit; padding: 12px; margin-top: 8px; }}
    input {{ width: min(92%, 620px); display: block; }}
    a {{ display: inline-block; margin-top: 16px; font-size: 1.1rem; }}
  </style>
</head>
<body>{body}</body>
</html>"""
    return document.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urlparse(self.path)
        if parsed.path == "/":
            body = f"""
<h1>CyanBridge HIL Search</h1>
<p>{SEARCH_MARKER}</p>
<form action="/search" method="get">
  <label for="query">Search query</label>
  <input id="query" name="q" type="search" aria-label="Search query">
  <button type="submit">Search</button>
</form>
"""
            self.respond(page("CyanBridge HIL Search", body))
            return

        if parsed.path == "/search":
            query = parse_qs(parsed.query).get("q", [""])[0]
            normalized = query.lower()
            if "smartglass" in normalized or "smart glass" in normalized:
                first_result = '<a href="/smartglasses-news">Latest smartglasses news — first result</a>'
            else:
                first_result = '<a href="/result">Borealis Field Note — first result</a>'
            body = f"""
<h1>Search results</h1>
<p>{RESULTS_MARKER}</p>
<p>Results for: <strong>{html.escape(query)}</strong></p>
{first_result}
"""
            self.respond(page("CyanBridge HIL Results", body))
            return

        if parsed.path == "/result":
            body = f"""
<article>
  <h1>Borealis Field Note</h1>
  <p>{ARTICLE_MARKER}</p>
  <p>Project Borealis uses 37 amber nodes in its first visible section.</p>
  <p>CyanBridge local AI provides the reasoning, chooses the next action, and produces the final answer.</p>
  <p>Tasker performs the browser navigation, clicking, typing, and other Android UI execution.</p>
  <p>This architecture keeps intelligence in CyanBridge while Tasker remains the device-action boundary.</p>
</article>
"""
            self.respond(page("Borealis Field Note", body))
            return

        if parsed.path == "/smartglasses-news":
            body = f"""
<article>
  <h1>Latest smartglasses news — CyanBridge HIL fixture</h1>
  <p>{SMARTGLASSES_MARKER}</p>
  <p><strong>Automation fixture:</strong> this is deterministic test content, not live reporting.</p>
  <p>On August 23, 2026, the fictional Aurora Lens 2 test device added a 42-gram frame, bilingual live captions, and an eight-hour mixed-use battery target.</p>
  <p>The fixture also says developers can hand Android UI execution to Tasker while CyanBridge keeps planning, summarization, approval policy, and the final user response.</p>
  <p>The unique verification phrase for this CI page is: cobalt horizon 88417.</p>
</article>
"""
            self.respond(page("Latest smartglasses news — HIL fixture", body))
            return

        self.send_error(404)

    def respond(self, content: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[hil-web] {self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"CyanBridge HIL web fixture listening on http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
