package uz.smssender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PICK = 1
    private val REQ_SAVE = 2
    private val REQ_PERM = 3

    private lateinit var summary: TextView
    private lateinit var messageBox: EditText
    private lateinit var pauseBox: EditText
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val pickBtn = Button(this).apply {
            text = "Excel / CSV fayl yuklash"
            setOnClickListener { pickFile() }
        }
        root.addView(pickBtn, LinearLayout.LayoutParams(match, wrap))

        summary = TextView(this).apply { text = "Fayl yuklanmagan" }
        root.addView(summary, LinearLayout.LayoutParams(match, wrap))

        messageBox = EditText(this).apply {
            hint = "Xabar matni ({ism} — ism bilan almashadi)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        root.addView(messageBox, LinearLayout.LayoutParams(match, wrap))

        val pauseRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseRow.addView(TextView(this).apply { text = "Pauza (soniya): " })
        pauseBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("5")
        }
        pauseRow.addView(pauseBox, LinearLayout.LayoutParams((80 * dp).toInt(), wrap))
        root.addView(pauseRow, LinearLayout.LayoutParams(match, wrap))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = Button(this).apply {
            text = "Boshlash"
            setOnClickListener { startClicked() }
        }
        val stopBtn = Button(this).apply {
            text = "To'xtatish"
            setOnClickListener { stopClicked() }
        }
        btnRow.addView(startBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        btnRow.addView(stopBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(btnRow, LinearLayout.LayoutParams(match, wrap))

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val listView = ListView(this).apply { this.adapter = this@MainActivity.adapter }
        root.addView(listView, LinearLayout.LayoutParams(match, 0, 1f))

        val saveBtn = Button(this).apply {
            text = "Natijani saqlash (CSV, Excel'da ochiladi)"
            setOnClickListener { saveFile() }
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(match, wrap))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        SmsState.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        SmsState.listener = null
        super.onPause()
    }

    private fun refresh() {
        val c = SmsState.contacts
        if (c.isEmpty()) {
            summary.text = "Fayl yuklanmagan"
        } else {
            val valid = c.count { it.valid }
            val sent = c.count { it.status == "yuborildi" }
            val err = c.count { it.status.startsWith("xato") }
            summary.text = "Jami: ${c.size} | to'g'ri: $valid | yuborildi: $sent | xato: $err" +
                (if (SmsState.duplicates > 0) " | takror olib tashlandi: ${SmsState.duplicates}" else "") +
                (if (SmsState.running) "\nYuborilmoqda..." else "")
        }
        adapter.clear()
        adapter.addAll(c.map { "${it.phone}  ${it.name}\n→ ${it.status}" })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun pickFile() {
        if (SmsState.running) {
            toast("Yuborish jarayonida fayl almashtirib bo'lmaydi")
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        i.putExtra(
            Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "text/comma-separated-values", "text/plain",
                "application/octet-stream"
            )
        )
        startActivityForResult(i, REQ_PICK)
    }

    private fun saveFile() {
        if (SmsState.contacts.isEmpty()) {
            toast("Saqlash uchun natija yo'q")
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "sms_natija.csv")
        startActivityForResult(i, REQ_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PICK -> loadFile(uri)
            REQ_SAVE -> saveResults(uri)
        }
    }

    private fun loadFile(uri: Uri) {
        try {
            SmsState.load(ContactReader.read(this, uri))
            refresh()
            if (SmsState.contacts.isEmpty()) toast("Fayldan raqam topilmadi (1-ustunda telefon bo'lishi kerak)")
        } catch (e: Exception) {
            toast("Faylni o'qib bo'lmadi: ${e.message}")
        }
    }

    private fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun saveResults(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val sb = StringBuilder("\uFEFF")
                sb.append("Telefon;Ism;Holat\n")
                for (c in SmsState.contacts) {
                    sb.append(esc(c.phone)).append(';')
                        .append(esc(c.name)).append(';')
                        .append(esc(c.status)).append('\n')
                }
                os.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            toast("Saqlandi")
        } catch (e: Exception) {
            toast("Saqlab bo'lmadi: ${e.message}")
        }
    }

    private fun startClicked() {
        if (SmsState.running) return
        if (SmsState.contacts.none { it.valid }) {
            toast("Avval Excel faylni yuklang")
            return
        }
        val msg = messageBox.text.toString().trim()
        if (msg.isEmpty()) {
            toast("Xabar matnini yozing")
            return
        }
        SmsState.message = msg
        SmsState.pauseSec = (pauseBox.text.toString().toIntOrNull() ?: 5).coerceIn(1, 120)

        val perms = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), REQ_PERM) else startSending()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                startSending()
            } else {
                toast("SMS yuborish uchun ruxsat kerak")
            }
        }
    }

    private fun startSending() {
        SmsState.running = true
        startForegroundService(Intent(this, SendService::class.java))
        refresh()
    }

    private fun stopClicked() {
        if (!SmsState.running) return
        startService(Intent(this, SendService::class.java).setAction(SendService.ACTION_STOP))
        toast("To'xtatilmoqda...")
    }
}
