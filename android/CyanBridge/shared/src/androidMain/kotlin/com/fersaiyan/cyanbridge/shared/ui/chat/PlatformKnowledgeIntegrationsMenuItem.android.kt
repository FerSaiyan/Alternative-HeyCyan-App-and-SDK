package com.fersaiyan.cyanbridge.shared.ui.chat

import androidx.compose.runtime.Composable

/**
 * Legacy Notes entry — now owned by NotesChatsScreen's expressive
 * segmented control + cog (Notes tab -> Obsidian sync settings).
 * Kept as no-op so legacy ChatListScreen no longer mixes vault
 * concerns with chats; the unified screen routes to the same
 * activity via its dedicated cog action.
 */
@Composable
actual fun PlatformKnowledgeIntegrationsTopBarAction() = Unit
