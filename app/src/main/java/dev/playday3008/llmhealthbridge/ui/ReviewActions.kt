package dev.playday3008.llmhealthbridge.ui

import dev.playday3008.llmhealthbridge.review.ReplyReview

/** Actions the review screen can trigger. */
class ReviewActions(
    val onUpdate: ((ReplyReview) -> ReplyReview) -> Unit,
    val onSave: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onInstall: () -> Unit
)
