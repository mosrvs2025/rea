package app.ultimate.watch

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists the recordings kept on this phone with play, share and delete. The segment still being written is shown but cannot be touched. */
class RecordingsActivity : ComponentActivity() {
    private class Row(val file: File, val bytes: Long, val modified: Long, val durationMs: Long?, val active: Boolean)

    private lateinit var list: ListView
    private lateinit var summary: TextView
    private val rows = ArrayList<Row>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 32, 24, 16) }
        summary = TextView(this).apply { textSize = 16f }.also(column::addView)
        list = ListView(this).also { column.addView(it) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        list.emptyView = TextView(this).apply { text = "No recordings yet. Turn on recording when you start monitoring."; visibility = View.GONE }.also(column::addView)
        list.setOnItemClickListener { _, _, position, _ -> choose(rows[position]) }
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val activeName = ServiceState.state.value.recording?.substringAfter("Recording · ", "")?.takeIf { it.endsWith(".mp4") }
            val loaded = withContext(Dispatchers.IO) {
                Recordings.dir(this@RecordingsActivity).listFiles().orEmpty().filter(Recordings::isRecording).sortedByDescending { it.lastModified() }.map { f ->
                    val active = f.name == activeName
                    Row(f, f.length(), f.lastModified(), if (active) null else duration(f), active)
                }
            }
            rows.clear(); rows.addAll(loaded)
            adapter.clear()
            adapter.addAll(rows.map(::describe))
            val total = rows.sumOf { it.bytes }
            summary.text = "${rows.size} recording${if (rows.size == 1) "" else "s"} · ${Formatter.formatFileSize(this@RecordingsActivity, total)} used"
        }
    }

    private fun duration(file: File): Long? = try {
        MediaMetadataRetriever().use { it.setDataSource(file.path); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
    } catch (_: Exception) { null }

    private fun describe(r: Row): String {
        val whenText = DateFormat.format("d MMM, HH:mm:ss", Date(r.modified))
        val state = when {
            r.active -> "recording now"
            r.durationMs == null -> "incomplete — may not play"
            else -> "${r.durationMs / 1000}s"
        }
        return "$whenText · ${Formatter.formatFileSize(this, r.bytes)} · $state"
    }

    private fun choose(r: Row) {
        if (r.active) { AlertDialog.Builder(this).setMessage("This file is still being recorded.").setPositiveButton("OK", null).show(); return }
        AlertDialog.Builder(this).setTitle(r.file.name).setItems(arrayOf("Play", "Share / export", "Delete")) { _, which ->
            val uri = FileProvider.getUriForFile(this, "app.ultimate.watch.files", r.file)
            when (which) {
                0 -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording"))
                else -> AlertDialog.Builder(this).setMessage("Delete this recording from the phone?").setPositiveButton("Delete") { _, _ -> r.file.delete(); reload() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }
}
