package com.dominiczirbel.network.model

import kotlinx.serialization.Serializable

/**
 * https://developer.spotify.com/documentation/web-api/reference/#object-externalidobject
 */
@Serializable
data class ExternalId(
    /** International Article Number */
    val ean: String? = null,

    /** International Standard Recording Code */
    val isrc: String? = null,

    /** Universal Product Code */
    val upc: String? = null
)
