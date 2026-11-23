package com.fersaiyan.cyanbridge.localmodels.engine

import com.google.ai.edge.litertlm.Conversation

/**
 * LiteRT-LM 0.19 `Conversation.clone()`: a copy-on-write fork sharing the parent's KV cache (the system-prompt
 * cache in LiteRtLocalInferenceEngine). Only clone a conversation primed with prefillPrefaceOnInit = true: 0.19.1
 * reuses another conversation's KV when the parent is still at step 0.
 */
internal const val CONVERSATION_CLONE_AVAILABLE = true

internal fun Conversation.cloneOrNull(): Conversation? = clone()
