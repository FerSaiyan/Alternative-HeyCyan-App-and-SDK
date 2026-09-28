"""Read-only experimental projection of Tasker UI choices for small models.

Only observed candidate labels become tools; never emit coordinates, draft text,
or a send action. This is intentionally separate from the production Gemma gate
until a representative held-out corpus and calibration support a change.
"""

from __future__ import annotations

import re


def _label(option: dict) -> str:
    description = option["description"]
    quoted = re.search(r'"([^"\n]+)"', description)
    return (quoted.group(1) if quoted else description).split(" (node ", 1)[0].strip()


def _target(goal: str) -> str:
    match = re.search(r"\bsearch\s+for\s+([^,.;]+)", goal, re.I)
    return match.group(1).strip()[:60] if match else ""


def phone_frame(record: dict, max_actions: int = 3) -> dict:
    """Build a one-step request, a sorted tool list, and an explicit Laya fallback.

    The phase is inferred only from the current app, current visible options and
    a literal goal span. Do not use `step`, model answers or expected labels.
    """
    if max_actions not in (2, 3):
        raise ValueError("This probe supports two or three on-screen actions")
    goal = record["goal"]
    package = record["package"]
    if "youtube" not in goal.casefold() or "linus tech tips" not in goal.casefold():
        return {"phase": "unsupported", "request": "Escalate to the detailed planner",
                "app": "Other app", "tools": []}
    target = _target(goal)
    candidates = [option for option in record["candidates"]
                  if option["key"] in {"open_app", "click_node", "type_text", "scroll", "press_back"}]
    labels = [_label(option).casefold() for option in candidates]

    if package != "com.google.android.youtube":
        phase = "launch"
        request = "Open YouTube"
    elif any(label == "video player" for label in labels):
        phase = "player"
        request = f"Verify playback of the requested {target or 'YouTube'} video"
    elif target and any(label == target.casefold() for label in labels):
        phase = "suggestion"
        request = f"Choose the exact search suggestion {target}"
    elif target and any(label.startswith("go to channel ") and target.casefold() in label
                        for label in labels):
        phase = "results"
        request = f"Open the channel {target}"
    elif any("verified" in label or "subscribers" in label for label in labels):
        phase = "channel"
        request = f"Open a video from {target or 'the requested'} channel"
    elif any(label == "search" for label in labels):
        phase = "home"
        request = f"Search YouTube for {target}" if target else "Open YouTube search"
    else:
        phase = "unknown"
        request = "Determine the next step from the current visible controls"

    def priority(option: dict) -> int:
        label = _label(option).casefold()
        if phase == "launch":
            return 100 if option["key"] == "open_app" and "youtube" in label else -100
        if phase == "home":
            return 100 if label == "search" else -100
        if phase == "suggestion":
            return 100 if label == target.casefold() else (30 if target.casefold() in label else -100)
        if phase == "results":
            return 100 if label == f"go to channel {target.casefold()}" else -40
        if phase == "channel":
            has_video_duration = bool(re.search(r"\b\d+\s*(?:mi|minutes?|seconds?|hours?)\b", label))
            return (40 if option["key"] == "click_node" and
                    (has_video_duration or "video" in label) else -40)
        return -100

    if phase == "player":
        # Positive playback is checked from observations, never guessed by a model.
        ranked = []
    else:
        ranked = [option for option in sorted(candidates, key=priority, reverse=True)
                  if priority(option) > 0][:max_actions]

    tools = []
    for option in ranked:
        label = _label(option)
        if option["key"] == "open_app":
            name, description = "open_youtube", "Open the YouTube application"
        elif option["key"] == "type_text":
            name, description = "type_exact_search", f"Type the user-specified search phrase in {label}"
        elif phase == "home":
            name, description = "tap_search", "Tap the visible YouTube Search control"
        elif phase == "suggestion":
            name, description = ("choose_exact_suggestion" if label.casefold() == target.casefold()
                                 else "edit_search_suggestion"), f"Choose visible suggestion {label}"
        elif phase == "results":
            name, description = "open_requested_channel", f"Open the visible channel {label}"
        else:
            name, description = "open_video", f"Open visible video {label}"
        if name in {tool["name"] for tool in tools}:
            name += "_" + option["label"].lower()
        tools.append({**option, "name": name, "description": description})

    return {"phase": phase, "request": request, "app": "YouTube" if
            package == "com.google.android.youtube" else "Other app",
            "tools": tools}
