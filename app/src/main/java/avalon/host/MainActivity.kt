package avalon.host

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import avalon.server.AvalonServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Host screen: starts the server, shows the hotspot address as a QR code, opens the host's own player screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView
    private lateinit var hint: TextView
    private lateinit var hotspotSettings: Button
    private lateinit var openPlayer: Button
    private lateinit var openBrowser: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastUrl: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = findViewById(R.id.status)
        qr = findViewById(R.id.qr)
        url = findViewById(R.id.url)
        hint = findViewById(R.id.hint)
        hotspotSettings = findViewById(R.id.hotspotSettings)
        openPlayer = findViewById(R.id.openPlayer)
        openBrowser = findViewById(R.id.openBrowser)

        hotspotSettings.setOnClickListener { openHotspotSettings() }
        openPlayer.setOnClickListener {
            HostService.server?.let { startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, it.hostUrl())) }
        }
        openBrowser.setOnClickListener {
            HostService.server?.let {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.hostUrl()))) }
                catch (e: Exception) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            HostService.stop(this)
            finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        HostService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun update() {
        val server = HostService.server
        if (server == null) {
            status.text = HostService.lastError?.let { "Could not start the server: $it" } ?: getString(R.string.starting)
            openPlayer.isEnabled = false
            openBrowser.isEnabled = false
            return
        }
        openPlayer.isEnabled = true
        openBrowser.isEnabled = true
        status.text = getString(R.string.server_running, server.boundPort)

        val addresses = AvalonServer.lanAddresses()
        val best = addresses.firstOrNull()
        if (best == null) {
            qr.visibility = View.GONE
            url.text = ""
            hint.text = getString(R.string.hotspot_hint)
            hotspotSettings.visibility = View.VISIBLE
            lastUrl = null
            return
        }
        val joinUrl = server.joinUrlFor(best.second)!!
        hotspotSettings.visibility = View.GONE
        hint.text = getString(R.string.friends_scan) +
            if (addresses.size > 1) "\n\nOther addresses on this phone: " + addresses.drop(1).joinToString { it.second } else ""
        if (joinUrl != lastUrl) {
            lastUrl = joinUrl
            url.text = joinUrl
            qr.setImageBitmap(makeQr(joinUrl, 800))
            qr.visibility = View.VISIBLE
            // Refresh the notification text with the current address.
            HostService.start(this)
        }
    }

    private fun openHotspotSettings() {
        val attempts = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in attempts) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
