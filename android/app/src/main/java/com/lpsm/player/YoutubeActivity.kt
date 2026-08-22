package com.lpsm.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Tela nativa: nunca abre navegador ou WebView. */
class YoutubeActivity : AppCompatActivity() {

    companion object {
        private const val SMARTTUBE_URL =
            "https://github.com/yuliskov/SmartTube/releases/download/32.10s/SmartTube_stable_32.10_universal.apk"
        private const val SMARTTUBE_SHA256 =
            "48044b306ded06cab939e81ad53be76a9c7b44c82a29c8bcaac8c2d8687b1579"
        private const val SMARTTUBE_FILE = "SmartTube-stable.apk"

        private val SMARTTUBE_PACKAGES =
            listOf(
                "org.smarttube.stable",
                "org.smarttube.beta",
                "com.teamsmart.videomanager.tv",
                "com.liskovsoft.smarttubetv.beta"
            )

        /** Abre direto, sem tela intermediaria ou escolha de aplicativo. */
        fun openInstalledPremium(context: Context): Boolean {
            for (packageName in SMARTTUBE_PACKAGES) {
                val launch = context.packageManager
                    .getLaunchIntentForPackage(packageName)
                    ?: continue

                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return true
            }
            return false
        }
    }

    private val smartTubePackages =
        SMARTTUBE_PACKAGES

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var openSmartTube: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var waitingForPermission = false
    private var waitingForInstallResult = false
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        val openYoutube = findViewById<Button>(R.id.youtubeOpen)
        openSmartTube = findViewById(R.id.smartTubeOpen)
        val close = findViewById<Button>(R.id.youtubeClose)
        status = findViewById(R.id.youtubeStatus)
        progress = findViewById(R.id.youtubeProgress)

        if (openInstalledPremium(this)) {
            finish()
            return
        }

        openYoutube.visibility = View.GONE
        openSmartTube.text = "INSTALAR YOUTUBE PREMIUM"

        openSmartTube.setOnClickListener {
            if (!openFirstInstalled(smartTubePackages)) {
                confirmSmartTubeDownload()
            }
        }

        close.setOnClickListener { finish() }

        openSmartTube.nextFocusUpId = close.id
        openSmartTube.nextFocusDownId = close.id
        close.nextFocusUpId = openSmartTube.id
        close.nextFocusDownId = openSmartTube.id
        openSmartTube.requestFocus()
    }

    private fun openFirstInstalled(packages: List<String>): Boolean {
        for (packageName in packages) {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            return true
        }
        return false
    }

    private fun confirmSmartTubeDownload() {
        AlertDialog.Builder(this)
            .setTitle("Instalar SmartTube")
            .setMessage(
                "SmartTube e um aplicativo independente, nao oficial do YouTube. " +
                    "O APK sera baixado da publicacao oficial do projeto no GitHub. Deseja continuar?"
            )
            .setPositiveButton("BAIXAR E INSTALAR") { _, _ -> prepareSmartTubeDownload() }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun prepareSmartTubeDownload() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            waitingForPermission = true
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(
                    this,
                    "Permita que o LPSM instale o SmartTube.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
                waitingForPermission = false
                showMessage("Nao foi possivel abrir a permissao de instalacao.")
            }
            return
        }

        downloadSmartTube()
    }

    override fun onResume() {
        super.onResume()

        if (waitingForPermission) {
            waitingForPermission = false
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls()
            ) {
                downloadSmartTube()
            } else {
                showMessage("A permissao para instalar foi negada.")
            }
        }

        if (waitingForInstallResult && isAnyInstalled(smartTubePackages)) {
            waitingForInstallResult = false
            smartTubeFile().delete()
            status.text = "YOUTUBE PREMIUM PRONTO"
            openSmartTube.text = "ABRIR YOUTUBE PREMIUM"
            if (openInstalledPremium(this)) {
                finish()
            }
        }
    }

    private fun downloadSmartTube() {
        if (downloading) return
        downloading = true
        openSmartTube.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = "Baixando SmartTube..."

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val apk = smartTubeFile()
                if (apk.exists()) apk.delete()
                connection = openConnection(SMARTTUBE_URL)
                val total = connection.contentLengthLong

                connection.inputStream.buffered().use { input ->
                    FileOutputStream(apk).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                val percent = (downloaded * 100L / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    runOnUiThread {
                                        progress.progress = percent
                                        status.text = "Baixando SmartTube... $percent%"
                                    }
                                }
                            }
                        }
                    }
                }

                if (sha256(apk) != SMARTTUBE_SHA256) {
                    apk.delete()
                    throw IllegalStateException("A verificacao de seguranca do APK falhou.")
                }

                runOnUiThread {
                    downloading = false
                    openSmartTube.isEnabled = true
                    progress.progress = 100
                    status.text = "Abrindo instalador..."
                    installSmartTube(apk)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    downloading = false
                    openSmartTube.isEnabled = true
                    progress.visibility = View.GONE
                    showMessage(error.message ?: "Falha ao baixar o SmartTube.")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun smartTubeFile(): File {
        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        if (!directory.exists()) directory.mkdirs()
        return File(directory, SMARTTUBE_FILE)
    }

    private fun installSmartTube(apk: File) {
        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("SmartTube", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            waitingForInstallResult = true
            startActivity(intent)
        } catch (_: Throwable) {
            waitingForInstallResult = false
            showMessage("O instalador deste aparelho nao conseguiu abrir o APK.")
        }
    }

    private fun openConnection(address: String): HttpURLConnection {
        var current = address
        repeat(8) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "LPSM-SmartTube-Installer/2.2.25")
                setRequestProperty("Accept", "*/*")
            }
            val statusCode = connection.responseCode
            if (statusCode in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirecionamento invalido")
                current = URL(URL(current), location).toString()
                connection.disconnect()
            } else {
                if (statusCode !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("Servidor respondeu HTTP $statusCode")
                }
                return connection
            }
        }
        throw IllegalStateException("Muitos redirecionamentos")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isAnyInstalled(packages: List<String>): Boolean =
        packages.any { packageManager.getLaunchIntentForPackage(it) != null }

    private fun showMessage(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
