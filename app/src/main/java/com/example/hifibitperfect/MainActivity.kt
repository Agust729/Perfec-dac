package com.example.hifibitperfect

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var audioManager: AudioManager

    private var selectedSpec: AudioSpec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "HiFi BitPerfect Tester"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val dacButton = Button(this).apply {
            text = "Detectar DAC USB"
            setOnClickListener {
                detectDac()
            }
        }

        val fileButton = Button(this).apply {
            text = "Elegir FLAC"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, 100)
            }
        }

        val testButton = Button(this).apply {
            text = "Probar Bit-Perfect"
            setOnClickListener {
                testBitPerfect()
            }
        }

        status = TextView(this).apply {
            textSize = 16f
            text = "Primero conectá un DAC USB y elegí un FLAC."
            setPadding(0, 30, 0, 0)
        }

        root.addView(title)
        root.addView(dacButton)
        root.addView(fileButton)
        root.addView(testButton)
        root.addView(status)

        val scroll = ScrollView(this)
        scroll.addView(root)

        setContentView(scroll)
    }

    private fun detectDac() {
        val dac = findUsbDac()

        status.text = if (dac != null) {
            "DAC USB detectado:\n${dac.productName ?: "USB Audio"}"
        } else {
            "No se detectó ningún DAC USB."
        }
    }

    private fun testBitPerfect() {
        val dac = findUsbDac()
        val spec = selectedSpec

        if (dac == null) {
            status.text = "No hay DAC USB detectado."
            return
        }

        if (spec == null) {
            status.text = "Primero elegí un archivo FLAC."
            return
        }

        if (Build.VERSION.SDK_INT < 34) {
            status.text = """
                Tu Android es menor a Android 14.

                Formato del FLAC:
                $spec

                Puede funcionar en Hi-Res, pero Android no permite confirmar Bit-Perfect real con esta API.
            """.trimIndent()
            return
        }

        val result = tryBitPerfectAndroid14(dac, spec)
        status.text = result
    }

    private fun tryBitPerfectAndroid14(
        dac: AudioDeviceInfo,
        spec: AudioSpec
    ): String {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(spec.sampleRate)
            .setEncoding(spec.encoding)
            .setChannelMask(spec.channelMask)
            .build()

        val supported = audioManager.getSupportedMixerAttributes(dac)

        val bitPerfect = supported.firstOrNull { mixer ->
            mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                mixer.format.sampleRate == format.sampleRate &&
                mixer.format.encoding == format.encoding &&
                mixer.format.channelMask == format.channelMask
        }

        if (bitPerfect == null) {
            return """
                DAC detectado, pero Android no ofrece Bit-Perfect para este formato.

                FLAC:
                $spec

                Puede ser limitación del celular, ROM, kernel o DAC.
            """.trimIndent()
        }

        val accepted = audioManager.setPreferredMixerAttributes(
            attributes,
            dac,
            bitPerfect
        )

        if (!accepted) {
            return "Android rechazó activar Bit-Perfect."
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            spec.channelMask,
            spec.encoding
        )

        if (minBuffer <= 0) {
            audioManager.clearPreferredMixerAttributes(attributes, dac)
            return "AudioTrack no acepta este formato: $spec"
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
            .build()

        val routed = track.setPreferredDevice(dac)

        track.release()
        audioManager.clearPreferredMixerAttributes(attributes, dac)

        return if (routed) {
            """
                ✅ Ruta Bit-Perfect aceptada.

                FLAC:
                $spec

                Esto significa que Android aceptó el formato exacto para el DAC USB.
            """.trimIndent()
        } else {
            "No se pudo fijar el DAC USB como salida."
        }
    }

    private fun findUsbDac(): AudioDeviceInfo? {
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.isSink &&
                    (
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    )
            }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            try {
                val spec = readFlacInfo(uri)
                selectedSpec = spec

                status.text = """
                    FLAC leído correctamente.

                    Formato:
                    $spec

                    Ahora tocá "Probar Bit-Perfect".
                """.trimIndent()
            } catch (e: Exception) {
                status.text = """
                    No pude leer ese FLAC.

                    Error:
                    ${e.message}
                """.trimIndent()
            }
        }
    }

    private fun readFlacInfo(uri: Uri): AudioSpec {
        contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "No se pudo abrir el archivo" }

            val input = BufferedInputStream(raw)

            val marker = ByteArray(4)
            input.readFully(marker)

            require(marker.decodeToString() == "fLaC") {
                "El archivo no parece ser FLAC puro"
            }

            while (true) {
                val header = ByteArray(4)
                input.readFully(header)

                val isLastBlock = (header[0].toInt() and 0x80) != 0
                val blockType = header[0].toInt() and 0x7F

                val length =
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)

                if (blockType == 0) {
                    val streamInfo = ByteArray(length)
                    input.readFully(streamInfo)

                    val b10 = streamInfo[10].toInt() and 0xFF
                    val b11 = streamInfo[11].toInt() and 0xFF
                    val b12 = streamInfo[12].toInt() and 0xFF
                    val b13 = streamInfo[13].toInt() and 0xFF

                    val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
                    val channels = ((b12 and 0x0E) shr 1) + 1
                    val bitDepth = (((b12 and 0x01) shl 4) or (b13 shr 4)) + 1

                    return AudioSpec(
                        sampleRate = sampleRate,
                        bitDepth = bitDepth,
                        channelCount = channels,
                        encoding = encodingForBitDepth(bitDepth),
                        channelMask = channelMaskFor(channels)
                    )
                } else {
                    input.skip(length.toLong())
                }

                if (isLastBlock) break
            }

            error("No encontré información FLAC")
        }
    }

    private fun BufferedInputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) error("Archivo incompleto")
            offset += read
        }
    }
}

data class AudioSpec(
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val encoding: Int,
    val channelMask: Int
) {
    override fun toString(): String {
        return "$bitDepth-bit / $sampleRate Hz / $channelCount canales"
    }
}

fun channelMaskFor(channels: Int): Int {
    return when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }
}

fun encodingForBitDepth(bitDepth: Int): Int {
    return when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> if (Build.VERSION.SDK_INT >= 31) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        32 -> if (Build.VERSION.SDK_INT >= 31) {
            AudioFormat.ENCODING_PCM_32BIT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        else -> AudioFormat.ENCODING_PCM_16BIT
    }
}
