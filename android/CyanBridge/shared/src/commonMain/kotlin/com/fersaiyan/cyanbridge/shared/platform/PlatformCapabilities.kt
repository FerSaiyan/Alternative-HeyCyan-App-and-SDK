package com.fersaiyan.cyanbridge.shared.platform

/** True on Android, which has Tasker; iOS has no Tasker, so no Tasker provider and no Local Agent. */
expect val platformSupportsTasker: Boolean
