package com.scrollcast.radio.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Works out where the listener is without asking for location permission: the mobile
 * network's country, then the SIM's, then the device locale.
 */
class RegionDetector(private val context: Context) {

    /** ISO 3166-1 alpha-2 code such as "IN", or null if nothing is known. */
    fun countryCode(): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val candidates = sequenceOf(
            runCatching { telephony?.networkCountryIso }.getOrNull(),
            runCatching { telephony?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        )
        return candidates
            .mapNotNull { it?.trim()?.uppercase() }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) }
    }

    /** ISO 639-1 code of the device language, such as "hi". */
    fun languageCode(): String = Locale.getDefault().language.lowercase()

    companion object {
        fun countryName(code: String): String =
            Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
    }
}
