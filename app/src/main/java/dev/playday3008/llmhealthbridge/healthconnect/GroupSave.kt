package dev.playday3008.llmhealthbridge.healthconnect

/**
 * Result of saving one group: [inserted] records were written; [cleanupFailure] is set when
 * removing the group's replaced records failed afterwards, so old and new records both remain.
 */
class GroupSave(val inserted: Int, val cleanupFailure: Exception? = null)
