package com.niaznix.bytelab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var imageText: TextView
    private lateinit var storageText: TextView
    private lateinit var rootText: TextView
    private val vmDir by lazy { File(filesDir, "virtual-phone").apply { mkdirs() } }
    private val guestImage by lazy { File(vmDir, "guest-system.img") }
    private val engineFile by lazy { File(vmDir, "qemu-system-aarch64") }

    companion object {
        private const val PICK_IMAGE = 4101
        private const val PICK_ENGINE = 4102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshStatus()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFF0B1220.toInt())
        }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)

        page.addView(label("BYTELAB", 13, 0xFF66D9C2.toInt(), true))
        page.addView(label("Virtual Phone", 30, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        page.addView(label("An isolated Android guest environment on your device.", 14, 0xFFB8C4D6.toInt()))
        page.addView(space(20))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF172338.toInt())
        }
        page.addView(card)
        card.addView(label("DEVICE & VIRTUALIZATION", 12, 0xFF66D9C2.toInt(), true))
        statusText = label("Checking device…", 14, 0xFFFFFFFF.toInt())
        card.addView(statusText.apply { setPadding(0, dp(10), 0, dp(4)) })
        card.addView(label("Virtualization acceleration depends on your Android build and hardware. This app cannot root the host phone.", 12, 0xFFB8C4D6.toInt()))

        page.addView(space(18))
        page.addView(label("GUEST SYSTEM", 13, 0xFF66D9C2.toInt(), true))
        imageText = label("Guest image: not imported", 14, 0xFFFFFFFF.toInt())
        page.addView(imageText.apply { setPadding(0, dp(10), 0, dp(8)) })
        page.addView(actionButton("Import Android guest image (.img / .qcow2)") { chooseFile(PICK_IMAGE) })
        page.addView(space(8))
        page.addView(label("QEMU engine: not installed", 14, 0xFFFFFFFF.toInt()).also { engineStatusText = it })
        page.addView(space(8))
        page.addView(actionButton("Import compatible QEMU engine binary") { chooseFile(PICK_ENGINE) })
        page.addView(space(18))

        page.addView(label("ISOLATED STORAGE", 13, 0xFF66D9C2.toInt(), true))
        storageText = label("", 14, 0xFFFFFFFF.toInt())
        page.addView(storageText.apply { setPadding(0, dp(9), 0, dp(8)) })
        page.addView(actionButton("Clear ByteLab virtual-phone data", true) { clearGuestData() })
        page.addView(space(18))

        page.addView(label("ROOT STATUS", 13, 0xFF66D9C2.toInt(), true))
        rootText = label("Guest root: not available until a rooted guest OS image is supplied and successfully booted.", 13, 0xFFFFD28A.toInt())
        page.addView(rootText.apply { setPadding(0, dp(9), 0, dp(8)) })

        page.addView(space(18))
        page.addView(label("LAUNCH", 13, 0xFF66D9C2.toInt(), true))
        page.addView(label("A complete Android guest requires a compatible ARM64 QEMU build, a bootable ARM64 Android image, and a display frontend. These components are not bundled because no universal low-RAM Android guest image or QEMU binary exists for all phones.", 13, 0xFFB8C4D6.toInt()).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        page.addView(actionButton("Refresh device and guest status") { refreshStatus() })
        page.addView(space(20))
        page.addView(label("ByteLab stores imported files inside its private app directory. Uninstalling the app removes this data. Imported files are not automatically isolated Android apps or a running virtual OS.", 11, 0xFF8493A9.toInt()))
    }

    private lateinit var engineStatusText: TextView

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun actionButton(text: String, danger: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(if (danger) 0xFF8E3445.toInt() else 0xFF167D78.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setOnClickListener { action() }
        }

    private fun chooseFile(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Use Activity Result APIs in a future update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                PICK_IMAGE -> copyImportedFile(uri, guestImage)
                PICK_ENGINE -> copyImportedFile(uri, engineFile).also {
                    engineFile.setExecutable(true, true)
                }
            }
            refreshStatus()
            Toast.makeText(this, "File imported into ByteLab private storage", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyImportedFile(uri: Uri, destination: File) {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected file" }
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }

    private fun clearGuestData() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear virtual-phone files?")
            .setMessage("This permanently deletes the guest image, engine file and all ByteLab private virtual-phone data.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                vmDir.deleteRecursively()
                vmDir.mkdirs()
                refreshStatus()
            }.show()
    }

    private fun refreshStatus() {
        val totalRam = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val arch = Build.SUPPORTED_ABIS.joinToString()
        val stat = StatFs(filesDir.absolutePath)
        val free = stat.availableBytes / (1024L * 1024L)
        statusText.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCPU ABI: $arch\nApp heap limit: ${totalRam} MB\nPrivate storage free: ${free} MB\nKVM: availability cannot be confirmed by this basic app."
        imageText.text = if (guestImage.exists()) "Guest image: imported (${guestImage.length() / (1024 * 1024)} MB)" else "Guest image: not imported"
        engineStatusText.text = if (engineFile.exists()) "QEMU engine file: imported (${engineFile.length() / (1024 * 1024)} MB)" else "QEMU engine: not installed"
        storageText.text = "Private directory: ${vmDir.absolutePath}\nUsed by ByteLab files: ${directorySize(vmDir) / (1024 * 1024)} MB"
    }

    private fun directorySize(file: File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf { directorySize(it) } ?: 0L

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
