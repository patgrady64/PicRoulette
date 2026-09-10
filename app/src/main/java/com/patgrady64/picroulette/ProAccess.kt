package com.patgrady64.picroulette

/**
 * Central PicRoulette Pro entitlement.
 *
 * The app is not published yet, so Google Play Billing is intentionally NOT wired in.
 * Patrick's development build is permanently Pro. When Play Billing is added later,
 * replace the non-owner branch with the verified Google Play entitlement.
 */
object ProAccess {
    const val OWNER_PRO_ACCESS: Boolean = true

    fun isPro(): Boolean = OWNER_PRO_ACCESS
}
