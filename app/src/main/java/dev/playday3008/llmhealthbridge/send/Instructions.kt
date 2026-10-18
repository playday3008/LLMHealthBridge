package dev.playday3008.llmhealthbridge.send

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The health-logging instructions sent to the LLM app, stored as an asset template. */
object Instructions {
    /** Placeholder in the asset that is replaced with the default time. */
    const val PLACEHOLDER = "{{DEFAULT_TIME}}"

    /** Placeholder in the asset that is replaced with the phone's time zone id. */
    const val ZONE_PLACEHOLDER = "{{TIME_ZONE}}"

    /** Placeholder in the asset that is replaced with the request id. */
    const val REQUEST_PLACEHOLDER = "{{REQUEST_ID}}"

    /** File name of the asset and of the attachment. */
    const val ASSET = "health-logging-instructions.txt"

    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    /**
     * Returns [template] with [PLACEHOLDER] replaced by [defaultTime] as ISO-8601 with offset,
     * seconds precision, [ZONE_PLACEHOLDER] by the id of [zone], so the LLM can give other dates
     * the offset they have there, and [REQUEST_PLACEHOLDER] by [request].
     */
    fun fill(template: String, defaultTime: OffsetDateTime, zone: ZoneId, request: String): String =
        template
            .replace(PLACEHOLDER, defaultTime.truncatedTo(ChronoUnit.SECONDS).format(format))
            .replace(ZONE_PLACEHOLDER, zone.id)
            .replace(REQUEST_PLACEHOLDER, request)
}
