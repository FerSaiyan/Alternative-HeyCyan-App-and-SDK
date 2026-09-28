package com.fersaiyan.cyanbridge.localagent

/** Positive, current Tasker observation of a playing requested video. */
internal object YouTubePlaybackEvidence {
    fun playingRequestedVideo(goal: String, observation: LocalAgentObservation): Boolean {
        if (observation.packageName != "com.google.android.youtube" ||
            !goal.contains(Regex("(?i)\\b(?:play|playback|playing)\\b"))) return false
        val target = UiActionCandidateBuilder.extractTypeSpans(goal).firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 } ?: return false
        val nodes = observation.screenSnapshot?.nodes.orEmpty()
        // The Pause control means the video is currently playing; a static
        // progress bar, generic player chrome, or "paused" text is insufficient.
        return nodes.any { it.text.equals("Pause video", ignoreCase = true) ||
            it.contentDescription.equals("Pause video", ignoreCase = true) } &&
            nodes.any { it.text.equals("Video player", ignoreCase = true) ||
                it.contentDescription.equals("Video player", ignoreCase = true) } &&
            nodes.any { (it.text + " " + it.contentDescription).contains(target, ignoreCase = true) }
    }
}
