package uz.smssender

import android.os.Handler
import android.os.Looper

class Contact(
    val phone: String,
    val name: String,
    val valid: Boolean,
    var status: String
)

object SmsState {
    val contacts = ArrayList<Contact>()
    var message = ""
    var pauseSec = 5
    var duplicates = 0

    @Volatile
    var running = false

    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun load(rows: List<Pair<String, String>>) {
        contacts.clear()
        duplicates = 0
        val seen = HashSet<String>()
        for ((raw, name) in rows) {
            val norm = Phone.normalize(raw)
            if (norm == null) {
                contacts.add(Contact(raw, name, false, "noto'g'ri raqam"))
            } else if (!seen.add(norm)) {
                duplicates++
            } else {
                contacts.add(Contact(norm, name, true, "kutilmoqda"))
            }
        }
    }

    fun setStatus(index: Int, status: String) {
        main.post {
            contacts.getOrNull(index)?.status = status
            listener?.invoke()
        }
    }

    fun changed() {
        main.post { listener?.invoke() }
    }
}

object Phone {
    private val sci = Regex("^[0-9]+(\\.[0-9]+)?[eE]\\+?[0-9]+$")
    private val dotZero = Regex("^[0-9]+\\.0+$")

    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (sci.matches(s)) {
            s = try {
                java.math.BigDecimal(s).toPlainString()
            } catch (e: Exception) {
                s
            }
        } else if (dotZero.matches(s)) {
            s = s.substringBefore('.')
        }
        val d = s.filter { it.isDigit() }
        return when {
            d.length == 9 -> "+998$d"
            d.length in 11..15 -> "+$d"
            else -> null
        }
    }
}
