package com.patgrady64.picroulette

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/** PicRoulette Pro entitlement and offline complimentary activation. */
object ProAccess {
    // Android Studio/debug builds stay Pro for development. Release builds require
    // a complimentary activation (or Google Play entitlement when Billing is added).

    private const val PREFS = "picroulette_pro"
    private const val INSTALLATION_ID_KEY = "installation_id"
    private const val COMPLIMENTARY_PRO_KEY = "complimentary_pro"
    private const val ACTIVATION_PREFIX = "PRA1-"
    private const val SIGNING_DOMAIN = "PICROULETTE-PRO-V1|"

    private const val PUBLIC_KEY_DER_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERS+/o+Yk6bXiUVJOQZB71UAA1UDNroScUrs2CMNpUsLUArNxUEINIYJkaumoM6P5BcBbkW+zcVRFKF7synyg6A=="

    fun isPro(context: Context): Boolean =
        BuildConfig.DEBUG || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(COMPLIMENTARY_PRO_KEY, false)

    fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(INSTALLATION_ID_KEY, null)?.let { return it }

        val random = ByteArray(10).also { SecureRandom().nextBytes(it) }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val encoded = buildString {
            random.forEach { byte -> append(alphabet[(byte.toInt() and 0xff) % alphabet.length]) }
        }
        val id = "PR-${encoded.substring(0, 5)}-${encoded.substring(5, 10)}"
        prefs.edit().putString(INSTALLATION_ID_KEY, id).apply()
        return id
    }

    fun activateComplimentaryPro(context: Context, enteredCode: String): Boolean {
        val compact = enteredCode.trim().replace(" ", "").replace("\n", "")
        if (!compact.uppercase(Locale.US).startsWith(ACTIVATION_PREFIX)) return false

        val encodedSignature = compact.substring(ACTIVATION_PREFIX.length)
        val signatureBytes = runCatching {
            Base64.decode(encodedSignature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false

        val publicKey = runCatching {
            val der = Base64.decode(PUBLIC_KEY_DER_BASE64, Base64.DEFAULT)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }.getOrNull() ?: return false

        val payload = (SIGNING_DOMAIN + installationId(context)).toByteArray(Charsets.UTF_8)
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signatureBytes)
            }
        }.getOrDefault(false)

        if (valid) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(COMPLIMENTARY_PRO_KEY, true).apply()
        }
        return valid
    }
}
