package com.fersaiyan.cyanbridge.localmodels.engine

import com.google.ai.edge.litertlm.Conversation

/** This LiteRT-LM line has no Conversation.clone() (added in 0.19): no system-prompt cache. */
internal const val CONVERSATION_CLONE_AVAILABLE = false

internal fun Conversation.cloneOrNull(): Conversation? = null
