package com.example.guiliang

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var db: RecordDb
    private lateinit var summary: TextView
    private lateinit var detail: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RecordDb(this)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 42, 36, 24); setBackgroundColor(0xFFF5F7F6.toInt()) }
        val title = TextView(this).apply { text = "柜量管家"; textSize = 28f; setTextColor(0xFF145B49.toInt()); gravity = Gravity.CENTER_VERTICAL }
        root.addView(title)
        root.addView(TextView(this).apply { text = "本地识别 · 柜号匹配 · 每日/月度统计"; textSize = 13f; setTextColor(0xFF66736E.toInt()) })
        summary = TextView(this).apply { textSize = 19f; setTextColor(0xFF163D32.toInt()); setPadding(24, 30, 24, 30); setBackgroundColor(0xFFE2EEE9.toInt()) }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        val scan = Button(this).apply { text = "扫描最近5个月相册"; setOnClickListener { requestAndScan() } }
        root.addView(scan, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        val refresh = Button(this).apply { text = "刷新统计"; setOnClickListener { refresh() } }
        root.addView(refresh)
        root.addView(TextView(this).apply { text = "每日完成明细（仅显示已匹配记录）"; textSize = 17f; setTextColor(0xFF263B34.toInt()); setPadding(0, 24, 0, 10) })
        detail = TextView(this).apply { textSize = 14f; setTextColor(0xFF263B34.toInt()); setPadding(18, 18, 18, 18); setBackgroundColor(0xFFFFFFFF.toInt()) }
        val scroll = ScrollView(this); scroll.addView(detail); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestAndScan() {
        if (!hasPermission()) {
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(p), 41)
        } else scanRecentImages()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanRecentImages()
        else Toast.makeText(this, "需要允许读取照片才能扫描相册", Toast.LENGTH_LONG).show()
    }

    private fun scanRecentImages() {
        Toast.makeText(this, "开始扫描最近5个月的图片…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val cutoff = Calendar.getInstance().apply { add(Calendar.MONTH, -5) }.timeInMillis
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DISPLAY_NAME)
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            var scanned = 0
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val added = c.getLong(addedCol) * 1000L
                    val taken = c.getLong(takenCol)
                    // Prefer EXIF/media capture time. If absent, use media-library added time as a conservative fallback.
                    val effectiveTime = if (taken > 0L) taken else added
                    if (effectiveTime < cutoff) continue
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString())
                    scanned++
                    recognize(uri, effectiveTime)
                }
            }
            runOnUiThread { Toast.makeText(this, "已提交 $scanned 张近期图片识别；识别在后台继续进行", Toast.LENGTH_LONG).show() }
        }
    }

    private fun recognize(uri: Uri, imageTime: Long) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image).addOnSuccessListener { result ->
                val raw = result.text
                val container = Regex("(?i)\\b[A-Z]{4}\\s?\\d{7}\\b").find(raw)?.value?.replace(" ", "")?.uppercase(Locale.ROOT) ?: return@addOnSuccessListener
                val workNo = findValue(raw, listOf("作业单号", "作业单", "业务单号", "单号"))
                val soNo = findValue(raw, listOf("SO号", "SO NO", "SO#", "订舱号", "SO"))
                val type = when {
                    raw.contains("卸重") -> "卸重"
                    raw.contains("卸吉") -> "卸吉"
                    raw.contains("装重") -> "装重"
                    raw.contains("装吉") -> "装吉"
                    else -> "待识别"
                }
                val orderDate = findDate(raw) ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(imageTime))
                val isOrder = workNo.isNotBlank() || soNo.isNotBlank() || type != "待识别"
                db.upsertCandidate(container, workNo, soNo, type, orderDate, uri.toString(), isOrder)
                runOnUiThread { refresh() }
            }
        } catch (_: Exception) { }
    }

    private fun findValue(text: String, labels: List<String>): String {
        for (label in labels) {
            val escaped = Regex.escape(label)
            val m = Regex("(?i)$escaped\\s*[:：#]?\\s*([A-Z0-9-]{5,24})").find(text)
            if (m != null) return m.groupValues[1].uppercase(Locale.ROOT)
        }
        return ""
    }
    private fun findDate(text: String): String? {
        val m = Regex("(20\\d{2})[-年/.](\\d{1,2})[-月/.](\\d{1,2})").find(text) ?: return null
        return try { "%04d-%02d-%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: Exception) { null }
    }

    private fun refresh() {
        val now = Calendar.getInstance()
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(now.time)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
        val total = db.countMonth(month)
        val todayCount = db.countDay(today)
        summary.text = "${month}  本月已匹配：${total} 条\n今天已匹配：${todayCount} 条\n\n未匹配订单不会计入完成量"
        val grouped = db.all().filter { it.status == "已匹配" }.groupBy { it.workDate }.toSortedMap(compareByDescending { it })
        detail.text = if (grouped.isEmpty()) "暂时没有已匹配记录。\n点击上方按钮扫描相册。" else grouped.entries.joinToString("\n\n") { (date, rows) -> "$date    ${rows.size} 条\n" + rows.joinToString("\n") { "• ${it.containerNo}  ${it.workType}" } }
    }

    override fun onDestroy() { super.onDestroy(); executor.shutdown(); recognizer.close() }
}
