package com.lpsm.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

import org.json.JSONObject

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors


class UpdateActivity : AppCompatActivity() {

    companion object {

        /*
         * Arquivo publicado automaticamente
         * pelo GitHub Actions.
         */
        private const val UPDATE_JSON_URL =
            "https://github.com/tvonbox3-byte/lpsm-player-m3u/releases/latest/download/update.json"


        /*
         * Nome usado para salvar
         * o APK temporariamente.
         */
        private const val UPDATE_APK_NAME =
            "LPSM-Update.apk"


        /*
         * Código usado ao abrir a tela
         * "Instalar apps desconhecidos".
         */
        private const val REQUEST_INSTALL_PERMISSION =
            7001
    }


    /*
     * Executor separado para internet/download.
     */
    private val executor =
        Executors.newFixedThreadPool(2)


    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )


    private val startupFallback =
        Runnable {

            if (
                !mainOpened &&
                !isFinishing &&
                !isDestroyed
            ) {

                statusText.text =
                    "Iniciando LPSM..."

                openMain()
            }
        }


    private lateinit var statusText:
        TextView


    private lateinit var progress:
        ProgressBar


    /*
     * Guarda a atualização enquanto
     * esperamos uma autorização do Android.
     */
    private var pendingUpdate:
        UpdateInfo? = null


    /*
     * Evita abrir MainActivity duas vezes.
     */
    private var mainOpened =
        false


    /*
     * Impede downloads repetidos.
     */
    private var downloading =
        false



    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        /*
         * A própria tela é construída
         * por código.
         *
         * Não precisamos criar layout XML
         * só para o atualizador.
         */
        createLoadingScreen()


        /*
         * Acorda o backend gratuito em paralelo.
         * Isso antecipa o cold start do Render sem
         * prender o cliente na tela de atualização.
         */
        warmBackend()


        /*
         * A verificação de update nunca pode segurar
         * a abertura do player por uma rede lenta.
         */
        mainHandler.postDelayed(
            startupFallback,
            850
        )


        /*
         * Assim que o LPSM abre,
         * procura uma atualização.
         */
        checkForUpdate()
    }



    /*
     * =====================================================
     * TELA DE VERIFICAÇÃO
     * =====================================================
     */

    private fun createLoadingScreen() {

        val root =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL


                gravity =
                    Gravity.CENTER


                setPadding(
                    dp(28),
                    dp(20),
                    dp(28),
                    dp(20)
                )


                setBackgroundColor(
                    Color.rgb(
                        5,
                        10,
                        18
                    )
                )
            }


        val logo =
            TextView(
                this
            ).apply {

                text =
                    "LPSM"


                setTextColor(
                    Color.rgb(
                        255,
                        224,
                        0
                    )
                )


                textSize =
                    34f


                gravity =
                    Gravity.CENTER


                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
            }


        root.addView(
            logo
        )


        statusText =
            TextView(
                this
            ).apply {

                text =
                    "Verificando atualizações..."


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    16f


                gravity =
                    Gravity.CENTER


                setPadding(
                    0,
                    dp(20),
                    0,
                    dp(18)
                )
            }


        root.addView(
            statusText
        )


        progress =
            ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {

                isIndeterminate =
                    true


                max =
                    100
            }


        root.addView(
            progress,

            LinearLayout.LayoutParams(
                dp(280),
                dp(12)
            )
        )


        setContentView(
            root
        )
    }



    /*
     * =====================================================
     * VERIFICAR ATUALIZAÇÃO
     * =====================================================
     */

    private fun checkForUpdate() {

        statusText.text =
            "Verificando atualizações..."


        progress.isIndeterminate =
            true


        executor.execute {

            try {

                val jsonText =
                    downloadText(
                        UPDATE_JSON_URL
                    )


                val json =
                    JSONObject(
                        jsonText
                    )


                val info =
                    UpdateInfo(

                        versionCode =
                            json.optLong(
                                "versionCode",
                                0L
                            ),


                        versionName =
                            json.optString(
                                "versionName",
                                ""
                            ),


                        apkUrl =
                            json.optString(
                                "apkUrl",
                                ""
                            ),


                        sha256 =
                            json.optString(
                                "sha256",
                                ""
                            ),


                        force =
                            json.optBoolean(
                                "force",
                                false
                            ),


                        message =
                            json.optString(
                                "message",
                                "Uma nova versão do LPSM está disponível."
                            )
                    )


                val installedVersion =
                    currentVersionCode()


                runOnUiThread {

                    if (
                        mainOpened ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@runOnUiThread
                    }

                    /*
                     * Existe atualização?
                     */
                    if (
                        info.versionCode >
                            installedVersion &&

                        info.apkUrl
                            .isNotBlank()
                    ) {

                        showUpdateDialog(
                            info
                        )

                    } else {

                        /*
                         * Já está atualizado.
                         */
                        openMain()
                    }
                }

            } catch (
                error: Exception
            ) {

                /*
                 * Se a internet do GitHub estiver
                 * fora do ar, não bloqueia o cliente.
                 *
                 * O aplicativo abre normalmente.
                 */
                runOnUiThread {
                    if (
                        !mainOpened &&
                        !isFinishing &&
                        !isDestroyed
                    ) {
                        openMain()
                    }
                }
            }
        }
    }



    /*
     * =====================================================
     * VERSION CODE INSTALADO
     * =====================================================
     */

    @Suppress("DEPRECATION")
    private fun currentVersionCode():
        Long {

        val packageInfo =
            packageManager
                .getPackageInfo(
                    packageName,
                    0
                )


        return if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
        ) {

            packageInfo.longVersionCode

        } else {

            packageInfo.versionCode
                .toLong()
        }
    }



    /*
     * =====================================================
     * JANELA: ATUALIZAÇÃO DISPONÍVEL
     * =====================================================
     */

    private fun showUpdateDialog(
        info: UpdateInfo
    ) {

        mainHandler.removeCallbacks(
            startupFallback
        )

        pendingUpdate =
            info


        statusText.text =
            "Nova versão disponível"


        progress.visibility =
            View.INVISIBLE


        val currentVersion =
            try {

                packageManager
                    .getPackageInfo(
                        packageName,
                        0
                    )
                    .versionName
                    ?: ""

            } catch (
                _: Exception
            ) {

                ""
            }


        val message =
            buildString {

                append(
                    info.message
                )


                append(
                    "\n\n"
                )


                if (
                    currentVersion
                        .isNotBlank()
                ) {

                    append(
                        "Versão instalada: "
                    )

                    append(
                        currentVersion
                    )

                    append(
                        "\n"
                    )
                }


                append(
                    "Nova versão: "
                )

                append(
                    info.versionName
                )


                append(
                    "\n\n"
                )


                append(
                    "Deseja atualizar agora?"
                )
            }


        val builder =
            AlertDialog
                .Builder(
                    this
                )
                .setTitle(
                    "Atualização disponível"
                )
                .setMessage(
                    message
                )
                .setPositiveButton(
                    "ATUALIZAR AGORA"
                ) {
                        _,
                        _ ->

                    prepareUpdate(
                        info
                    )
                }


        /*
         * force = false
         *
         * Cliente pode entrar no aplicativo
         * e atualizar depois.
         */
        if (
            !info.force
        ) {

            builder
                .setNegativeButton(
                    "DEPOIS"
                ) {
                        _,
                        _ ->

                    openMain()
                }
        }


        val dialog =
            builder.create()


        /*
         * Atualização obrigatória:
         * não permite fechar com Voltar.
         */
        dialog.setCancelable(
            !info.force
        )


        dialog
            .setOnCancelListener {

                if (
                    !info.force
                ) {

                    openMain()
                }
            }


        dialog.show()
    }



    /*
     * =====================================================
     * PREPARAR ATUALIZAÇÃO
     * =====================================================
     */

    private fun prepareUpdate(
        info: UpdateInfo
    ) {

        pendingUpdate =
            info


        /*
         * Android 8+
         *
         * Precisa verificar se o LPSM
         * tem autorização para abrir
         * o instalador de APK.
         */
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {

            if (
                !packageManager
                    .canRequestPackageInstalls()
            ) {

                requestInstallPermission()

                return
            }
        }


        downloadUpdate(
            info
        )
    }



    /*
     * =====================================================
     * PERMISSÃO PARA INSTALAR APK
     * =====================================================
     */

    private fun requestInstallPermission() {

        try {

            val intent =
                Intent(
                    Settings
                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES,

                    Uri.parse(
                        "package:$packageName"
                    )
                )


            startActivityForResult(
                intent,
                REQUEST_INSTALL_PERMISSION
            )


            Toast
                .makeText(
                    this,
                    "Permita que o LPSM instale atualizações.",
                    Toast.LENGTH_LONG
                )
                .show()

        } catch (
            error: Exception
        ) {

            showUpdateError(
                pendingUpdate,
                "Não foi possível abrir a permissão de instalação."
            )
        }
    }



    /*
     * =====================================================
     * RETORNO DA PERMISSÃO
     * =====================================================
     */

    @Deprecated(
        "Compatibilidade com Android TV e TV Box"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )


        if (
            requestCode ==
                REQUEST_INSTALL_PERMISSION
        ) {

            val info =
                pendingUpdate
                    ?: return


            if (
                Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.O ||

                packageManager
                    .canRequestPackageInstalls()
            ) {

                downloadUpdate(
                    info
                )

            } else {

                showPermissionDenied(
                    info
                )
            }
        }
    }



    /*
     * =====================================================
     * PERMISSÃO NEGADA
     * =====================================================
     */

    private fun showPermissionDenied(
        info: UpdateInfo
    ) {

        AlertDialog
            .Builder(
                this
            )
            .setTitle(
                "Permissão necessária"
            )
            .setMessage(
                "Para atualizar o LPSM diretamente pelo aplicativo, permita a instalação de apps desta fonte."
            )
            .setPositiveButton(
                "PERMITIR"
            ) {
                    _,
                    _ ->

                requestInstallPermission()
            }
            .apply {

                if (
                    !info.force
                ) {

                    setNegativeButton(
                        "DEPOIS"
                    ) {
                            _,
                            _ ->

                        openMain()
                    }
                }
            }
            .setCancelable(
                !info.force
            )
            .show()
    }



    /*
     * =====================================================
     * DOWNLOAD DO APK
     * =====================================================
     */

    private fun downloadUpdate(
        info: UpdateInfo
    ) {

        if (
            downloading
        ) {

            return
        }


        downloading =
            true


        statusText.text =
            "Baixando atualização..."


        progress.visibility =
            View.VISIBLE


        progress.isIndeterminate =
            false


        progress.progress =
            0


        executor.execute {

            try {

                val directory =
                    getExternalFilesDir(
                        Environment
                            .DIRECTORY_DOWNLOADS
                    )
                        ?: filesDir


                if (
                    !directory.exists()
                ) {

                    directory.mkdirs()
                }


                val apkFile =
                    File(
                        directory,
                        UPDATE_APK_NAME
                    )


                if (
                    apkFile.exists()
                ) {

                    apkFile.delete()
                }


                val connection =
                    openConnection(
                        info.apkUrl
                    )


                val totalBytes =
                    connection
                        .contentLengthLong


                connection
                    .inputStream
                    .buffered()
                    .use {
                        input ->


                        FileOutputStream(
                            apkFile
                        )
                            .buffered()
                            .use {
                                output ->


                                val buffer =
                                    ByteArray(
                                        64 * 1024
                                    )


                                var downloaded =
                                    0L


                                var lastPercent =
                                    -1


                                while (
                                    true
                                ) {

                                    val read =
                                        input.read(
                                            buffer
                                        )


                                    if (
                                        read <=
                                        0
                                    ) {

                                        break
                                    }


                                    output.write(
                                        buffer,
                                        0,
                                        read
                                    )


                                    downloaded +=
                                        read


                                    if (
                                        totalBytes >
                                        0
                                    ) {

                                        val percent =
                                            (
                                                downloaded *
                                                    100L /
                                                    totalBytes
                                                )
                                                .toInt()
                                                .coerceIn(
                                                    0,
                                                    100
                                                )


                                        if (
                                            percent !=
                                            lastPercent
                                        ) {

                                            lastPercent =
                                                percent


                                            runOnUiThread {

                                                progress.progress =
                                                    percent


                                                statusText.text =
                                                    "Baixando atualização... $percent%"
                                            }
                                        }
                                    }
                                }


                                output.flush()
                            }
                    }


                connection.disconnect()


                if (
                    !apkFile.exists() ||

                    apkFile.length() <=
                    0L
                ) {

                    throw IllegalStateException(
                        "O APK baixado está vazio."
                    )
                }


                val expectedHash =
                    info.sha256
                        .trim()
                        .lowercase()


                if (
                    expectedHash.length != 64 ||
                    expectedHash.any {
                        it !in '0'..'9' &&
                        it !in 'a'..'f'
                    }
                ) {

                    apkFile.delete()

                    throw IllegalStateException(
                        "A atualização não possui uma assinatura de integridade válida."
                    )
                }


                val downloadedHash =
                    sha256(
                        apkFile
                    )


                if (
                    downloadedHash !=
                    expectedHash
                ) {

                    apkFile.delete()

                    throw IllegalStateException(
                        "A verificação de segurança do APK falhou."
                    )
                }


                downloading =
                    false


                runOnUiThread {

                    progress.progress =
                        100


                    statusText.text =
                        "Abrindo instalador..."


                    installApk(
                        apkFile
                    )
                }

            } catch (
                error: Exception
            ) {

                downloading =
                    false


                runOnUiThread {

                    showUpdateError(
                        info,

                        error.message
                            ?: "Falha ao baixar atualização."
                    )
                }
            }
        }
    }



    /*
     * =====================================================
     * ABRIR INSTALADOR DO ANDROID
     * =====================================================
     */

    private fun installApk(
        apkFile: File
    ) {

        try {

            val authority =
                "$packageName.fileprovider"


            val apkUri =
                FileProvider
                    .getUriForFile(
                        this,
                        authority,
                        apkFile
                    )


            val intent =
                Intent(
                    Intent.ACTION_VIEW
                ).apply {

                    setDataAndType(
                        apkUri,
                        "application/vnd.android.package-archive"
                    )


                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )


                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }


            startActivity(
                intent
            )

        } catch (
            error: Exception
        ) {

            showUpdateError(
                pendingUpdate,

                "Não foi possível abrir o instalador: ${error.message ?: "erro desconhecido"}"
            )
        }
    }


    /*
     * =====================================================
     * INTEGRIDADE DO APK BAIXADO
     * =====================================================
     */

    private fun sha256(
        file: File
    ): String {

        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )


        file.inputStream()
            .buffered()
            .use {
                input ->

                val buffer =
                    ByteArray(
                        64 * 1024
                    )


                while (true) {
                    val read =
                        input.read(
                            buffer
                        )

                    if (read <= 0) {
                        break
                    }

                    digest.update(
                        buffer,
                        0,
                        read
                    )
                }
            }


        return digest
            .digest()
            .joinToString("") {
                byte ->

                "%02x".format(
                    byte.toInt() and 0xff
                )
            }
    }



    /*
     * =====================================================
     * ERRO DE ATUALIZAÇÃO
     * =====================================================
     */

    private fun showUpdateError(
        info: UpdateInfo?,
        reason: String
    ) {

        progress.visibility =
            View.INVISIBLE


        statusText.text =
            "Não foi possível atualizar"


        val builder =
            AlertDialog
                .Builder(
                    this
                )
                .setTitle(
                    "Falha na atualização"
                )
                .setMessage(
                    "Não foi possível baixar ou instalar a atualização.\n\n$reason"
                )


        if (
            info !=
            null
        ) {

            builder
                .setPositiveButton(
                    "TENTAR NOVAMENTE"
                ) {
                        _,
                        _ ->

                    prepareUpdate(
                        info
                    )
                }


            if (
                !info.force
            ) {

                builder
                    .setNegativeButton(
                        "ENTRAR NO APP"
                    ) {
                            _,
                            _ ->

                        openMain()
                    }
            }


            builder
                .setCancelable(
                    !info.force
                )

        } else {

            builder
                .setPositiveButton(
                    "ENTRAR NO APP"
                ) {
                        _,
                        _ ->

                    openMain()
                }
        }


        builder.show()
    }



    /*
     * =====================================================
     * BAIXAR JSON
     * =====================================================
     */

    private fun downloadText(
        address: String
    ): String {

        val connection =
            openConnection(
                address
            )


        return try {

            connection
                .inputStream
                .bufferedReader(
                    Charsets.UTF_8
                )
                .use {

                    it.readText()
                }

        } finally {

            connection.disconnect()
        }
    }


    /*
     * =====================================================
     * ACORDAR BACKEND SEM ATRASAR A ABERTURA
     * =====================================================
     */

    private fun warmBackend() {

        executor.execute {

            var connection:
                HttpURLConnection? = null

            try {

                connection =
                    URL(
                        BuildConfig.API_BASE_URL
                            .trimEnd('/') +
                            "/api/health"
                    )
                        .openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "GET"

                connection.connectTimeout =
                    5_000

                connection.readTimeout =
                    5_000

                connection.setRequestProperty(
                    "User-Agent",
                    "LPSM-Android-Warmup/2.2.16"
                )

                connection.inputStream
                    .use {
                        it.read()
                    }

            } catch (
                _: Exception
            ) {
                /* A chamada já foi suficiente para acordar o Render. */

            } finally {
                connection?.disconnect()
            }
        }
    }



    /*
     * =====================================================
     * HTTP + REDIRECIONAMENTOS
     * =====================================================
     */

    private fun openConnection(
        address: String
    ): HttpURLConnection {

        var currentAddress =
            address


        repeat(
            8
        ) {

            val connection =
                URL(
                    currentAddress
                )
                    .openConnection()
                    as HttpURLConnection


            connection
                .instanceFollowRedirects =
                false


            connection
                .requestMethod =
                "GET"


            connection
                .connectTimeout =
                15_000


            connection
                .readTimeout =
                60_000


            connection
                .setRequestProperty(
                    "User-Agent",
                    "LPSM-Android-Updater/2.2"
                )


            connection
                .setRequestProperty(
                    "Accept",
                    "*/*"
                )


            val responseCode =
                connection
                    .responseCode


            /*
             * GitHub Releases usa
             * redirecionamentos.
             */
            if (
                responseCode in
                300..399
            ) {

                val location =
                    connection
                        .getHeaderField(
                            "Location"
                        )
                        ?: throw IllegalStateException(
                            "Redirecionamento sem endereço."
                        )


                val next =
                    URL(
                        URL(
                            currentAddress
                        ),
                        location
                    )
                        .toString()


                connection.disconnect()


                currentAddress =
                    next


                return@repeat
            }


            if (
                responseCode !in
                200..299
            ) {

                connection.disconnect()


                throw IllegalStateException(
                    "Servidor respondeu HTTP $responseCode."
                )
            }


            return connection
        }


        throw IllegalStateException(
            "Muitos redirecionamentos."
        )
    }



    /*
     * =====================================================
     * ENTRAR NO LPSM
     * =====================================================
     */

    private fun openMain() {

        if (
            mainOpened
        ) {

            return
        }


        mainOpened =
            true


        mainHandler.removeCallbacks(
            startupFallback
        )


        startActivity(
            Intent(
                this,
                MainActivity::class.java
            )
        )


        finish()
    }



    /*
     * =====================================================
     * DP
     * =====================================================
     */

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources
                    .displayMetrics
                    .density
            )
            .toInt()
    }



    /*
     * =====================================================
     * ENCERRAR
     * =====================================================
     */

    override fun onDestroy() {

        mainHandler.removeCallbacks(
            startupFallback
        )

        executor.shutdownNow()


        super.onDestroy()
    }



    /*
     * =====================================================
     * MODELO DA ATUALIZAÇÃO
     * =====================================================
     */

    private data class UpdateInfo(

        val versionCode: Long,

        val versionName: String,

        val apkUrl: String,

        val sha256: String,

        val force: Boolean,

        val message: String
    )
}
