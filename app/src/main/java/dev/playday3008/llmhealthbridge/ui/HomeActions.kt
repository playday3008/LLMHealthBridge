package dev.playday3008.llmhealthbridge.ui

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.Section
import dev.playday3008.llmhealthbridge.send.LlmApp

/** Actions the home screen can trigger. */
class HomeActions(
    val onSelectApp: (LlmApp?) -> Unit,
    val onTakePhoto: () -> Unit,
    val onPickPhotos: () -> Unit,
    val onDescribe: () -> Unit,
    val onPaste: () -> Unit,
    val onOpenBanner: () -> Unit,
    val onDismissBanner: () -> Unit,
    val onOpenGroup: (Group) -> Unit,
    val onDeleteGroup: (Group) -> Unit,
    val onToggleSection: (Section) -> Unit,
    val onRetryRecent: () -> Unit
)
