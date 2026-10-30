package br.com.leonardo.painelasx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL: tira uma foto de uma câmera da central (ré/DVR) para o Claude ver.
 * Nem toda central entrega a câmera de ré por aqui; se não der, devolve o motivo.
 */
object FotoCamera {
    fun cameras(ctx: Context): JSONArray {
        val a = JSONArray()
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) a.put(id)
        } catch (e: Exception) {
            a.put("erro: ${e.javaClass.simpleName}")
        }
        return a
    }

    /** JSON com "imagem" (jpeg em base64) ou "erro". */
    fun tirar(ctx: Context, qual: String?): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("erro", "Falta liberar a câmera no Painel ASX (cartão \"Falta liberar\").")
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        if (ids.isEmpty()) return JSONObject().put("erro", "A central não mostra nenhuma câmera para os apps.")
        val id = if (qual != null && qual in ids) qual else ids.first()

        val linha = HandlerThread("foto").also { it.start() }
        val h = Handler(linha.looper)
        val pronto = CountDownLatch(1)
        var foto: ByteArray? = null
        var erro: String? = null
        var camera: CameraDevice? = null
        var leitor: ImageReader? = null
        try {
            val mapa = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val tamanhos = mapa?.getOutputSizes(ImageFormat.JPEG)?.sortedBy { it.width * it.height } ?: emptyList()
            val tam = tamanhos.firstOrNull { it.width >= 640 } ?: tamanhos.lastOrNull()
            ?: return JSONObject().put("erro", "A câmera $id não entrega foto JPEG.")
            val r = ImageReader.newInstance(tam.width, tam.height, ImageFormat.JPEG, 3)
            leitor = r
            var quadros = 0
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                quadros++
                if (quadros >= 5 && foto == null) {
                    val buf = img.planes[0].buffer
                    foto = ByteArray(buf.remaining()).also { buf.get(it) }
                    pronto.countDown()
                }
                img.close()
            }, h)
            @Suppress("MissingPermission")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    try {
                        @Suppress("DEPRECATION")
                        c.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) { erro = "pedido: ${e.message}"; pronto.countDown() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { erro = "a câmera recusou a sessão"; pronto.countDown() }
                        }, h)
                    } catch (e: Exception) { erro = "sessão: ${e.message}"; pronto.countDown() }
                }
                override fun onDisconnected(c: CameraDevice) { erro = "a câmera foi desconectada (outro app está usando?)"; pronto.countDown() }
                override fun onError(c: CameraDevice, e: Int) { erro = "erro da câmera $e (em uso por outro app?)"; pronto.countDown() }
            }, h)
            if (!pronto.await(10, TimeUnit.SECONDS) && erro == null) erro = "a câmera não mandou imagem em 10 s"
        } catch (e: Exception) {
            erro = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { camera?.close() } catch (_: Exception) { }
            try { leitor?.close() } catch (_: Exception) { }
            linha.quitSafely()
        }
        val f = foto
        return if (f != null) JSONObject().put("camera", id).put("imagem", Base64.encodeToString(f, Base64.NO_WRAP))
        else JSONObject().put("erro", "Não deu para tirar a foto: $erro").put("cameras", JSONArray(ids))
    }
}
