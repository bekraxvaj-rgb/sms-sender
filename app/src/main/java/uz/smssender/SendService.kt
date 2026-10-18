package uz.smssender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager

class SendService : Service() {

    companion object {
        const val ACTION_STOP = "uz.smssender.STOP"
        const val ACTION_SENT = "uz.smssender.SENT"
        const val CHANNEL = "send"
    }

    @Volatile
    private var stopFlag = false
    private var worker: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val idx = intent.getIntExtra("idx", -1)
            val cur = SmsState.contacts.getOrNull(idx) ?: return
            if (resultCode == Activity.RESULT_OK) {
                if (!cur.status.startsWith("xato")) SmsState.setStatus(idx, "yuborildi")
            } else {
                SmsState.setStatus(idx, "xato (kod $resultCode)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SMS yuborish", NotificationManager.IMPORTANCE_LOW)
        )
        val filter = IntentFilter(ACTION_SENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            worker?.interrupt()
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        startFg("Yuborish boshlandi")
        if (worker?.isAlive != true) {
            stopFlag = false
            worker = Thread { work() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SMS yuborilmoqda")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun startFg(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif(text))
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    private fun work() {
        val sms = smsManager()
        val nm = getSystemService(NotificationManager::class.java)
        val list = SmsState.contacts
        val total = list.count { it.valid }
        var done = list.count { it.valid && it.status != "kutilmoqda" }
        val rnd = java.util.Random()

        for (i in list.indices) {
            if (stopFlag) break
            val c = list[i]
            if (!c.valid || c.status != "kutilmoqda") continue

            SmsState.setStatus(i, "yuborilmoqda")
            try {
                val text = SmsState.message.replace("{ism}", c.name)
                val sentIntent = Intent(ACTION_SENT).setPackage(packageName).putExtra("idx", i)
                val pi = PendingIntent.getBroadcast(
                    this, i, sentIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val parts = sms.divideMessage(text)
                if (parts.size > 1) {
                    val pis = ArrayList<PendingIntent>()
                    repeat(parts.size) { pis.add(pi) }
                    sms.sendMultipartTextMessage(c.phone, null, parts, pis, null)
                } else {
                    sms.sendTextMessage(c.phone, null, text, pi, null)
                }
            } catch (e: Exception) {
                SmsState.setStatus(i, "xato: ${e.message}")
            }

            done++
            nm.notify(1, notif("$done / $total"))
            if (stopFlag) break
            try {
                Thread.sleep(SmsState.pauseSec * 1000L + rnd.nextInt(2000))
            } catch (e: InterruptedException) {
                break
            }
        }

        try {
            Thread.sleep(3000) // oxirgi natijalar kelishini kutamiz
        } catch (e: InterruptedException) {
        }
        SmsState.running = false
        SmsState.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
