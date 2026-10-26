package app.autohost.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.autohost.capabilities.data.TelephonySeams

/**
 * P10-08 (ADR-074): the Android seam implementations for SMS/telephony — `full` flavor only
 * (the core manifest declares none of these permissions, so the platform cannot grant them).
 * Sends ride [SmsManager]; the dialer owns calls (ACTION_CALL, user-visible). Read is scoped
 * by the run's `senders` grant at the provider level; caps live in DataPolicy.
 */
class AndroidSms(private val context: Context) : TelephonySeams.Sms {

    private val resolver = context.contentResolver

    private fun hasRead(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasSend(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED

    override fun inbox(filter: String?, senders: Set<String>?, limit: Int): List<TelephonySeams.SmsRow> {
        if (!hasRead()) return emptyList()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
        )
        val rows = mutableListOf<TelephonySeams.SmsRow>()
        resolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit * 4) { // over-fetch then scope-filter
                val sender = cursor.getString(0) ?: continue
                if (senders != null && sender !in senders) continue
                val body = cursor.getString(1) ?: ""
                if (filter != null && !body.contains(filter)) continue
                rows.add(TelephonySeams.SmsRow(sender, body, cursor.getLong(2), cursor.getInt(3) != 0))
                if (rows.size >= limit) break
            }
        }
        return rows
    }

    @SuppressLint("MissingPermission")
    override fun send(address: String, body: String): TelephonySeams.SendOutcome {
        if (!hasSend()) return TelephonySeams.SendOutcome(false, "SEND_SMS not granted")
        return try {
            val manager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(body)
            if (parts.size > 3) {
                return TelephonySeams.SendOutcome(false, "message needs ${parts.size} parts — the provider caps at 3; send shorter bodies")
            }
            manager.sendMultipartTextMessage(address, null, parts, null, null)
            TelephonySeams.SendOutcome(true, parts = parts.size)
        } catch (e: Exception) {
            TelephonySeams.SendOutcome(false, e.message?.take(200) ?: "send failed")
        }
    }
}

class AndroidTelephony(private val context: Context) : TelephonySeams.Telephony {

    @Suppress("MissingPermission")
    override fun callState(): Pair<TelephonySeams.CallState, String?> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return TelephonySeams.CallState.IDLE to null // honest idle-without-state, never a crash
        }
        val manager = context.getSystemService(TelephonyManager::class.java)
        val state = when (manager.callState) {
            TelephonyManager.CALL_STATE_RINGING -> TelephonySeams.CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> TelephonySeams.CallState.OFFHOOK
            else -> TelephonySeams.CallState.IDLE
        }
        val number = if (state == TelephonySeams.CallState.RINGING) {
            runCatching { manager.line1Number }.getOrNull()
        } else {
            null
        }
        return state to number
    }

    override fun placeCall(number: String): Boolean = try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
