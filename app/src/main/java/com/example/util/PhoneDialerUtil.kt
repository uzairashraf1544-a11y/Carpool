package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object PhoneDialerUtil {

    private const val TAG = "PhoneDialer"

    /**
     * Normalizes a phone number for the tel: URI scheme.
     * Preserves leading '+' for international numbers.
     * Removes whitespace, hyphens, parentheses, and other non-digit characters.
     * Returns empty string if phone is null, blank, or contains no digits.
     */
    fun normalizePhoneNumber(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val trimmed = phone.trim()
        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return ""
        return if (hasLeadingPlus) "+$digitsOnly" else digitsOnly
    }

    /**
     * Builds an Intent with Intent.ACTION_DIAL and tel: URI for the normalized phone number.
     * Returns null if phone number cannot be normalized.
     */
    fun createDialIntent(phone: String?): Intent? {
        val normalized = normalizePhoneNumber(phone)
        if (normalized.isBlank()) return null
        return Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$normalized")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Opens the Android system phone dialer with the phone number prefilled.
     * Uses Intent.ACTION_DIAL so CALL_PHONE permission is NOT required.
     * Safe against missing dialer apps, null/blank numbers, and exceptions.
     */
    fun openPhoneDialer(context: Context, phone: String?): Boolean {
        val intent = createDialIntent(phone)
        if (intent == null) {
            Log.w(TAG, "Cannot open dialer: phone number is blank or invalid ($phone)")
            return false
        }

        return try {
            context.startActivity(intent)
            Log.d(TAG, "Successfully launched dialer for normalized number: ${intent.data}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch phone dialer for number: $phone", e)
            false
        }
    }
}
