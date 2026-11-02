package br.com.leonardo.painelasx.canal.servidor;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * Microfone do celular gravado pelo usuário shell (uid 2000), como o scrcpy faz com --audio-source=mic
 * (scrcpy/server/.../audio/AudioDirectCapture.java). O shell tem RECORD_AUDIO no manifesto do pacote
 * com.android.shell. No Android 12+ o AudioRecord precisa de um Context com o pacote "com.android.shell"
 * (ContextoFalso), senão o AppOps não sabe de quem é a gravação.
 *
 * Só funciona no Android 12+ (no 11 o scrcpy precisa abrir uma tela do shell na frente; o Redmi 15 é 15/16).
 */
final class MicAndroid implements Servidor.FabricaMic {

    MicAndroid() {
        prepararLooperPrincipal();
        Ajustes.aplicar();
    }

    /** Igual ao scrcpy: Looper.prepare() e marca como "principal" (o AudioRecord usa o looper principal). */
    private static void prepararLooperPrincipal() {
        if (Looper.getMainLooper() != null) return;
        Looper.prepare();
        try {
            Field f = Looper.class.getDeclaredField("sMainLooper");
            f.setAccessible(true);
            f.set(null, Looper.myLooper());
        } catch (ReflectiveOperationException e) {
            System.out.println("aviso: looper principal: " + e);
        }
    }

    static int fonte(String nome) {
        switch (nome) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "voice_communication": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;   // cancela eco
            case "unprocessed": return MediaRecorder.AudioSource.UNPROCESSED;
            case "camcorder": return MediaRecorder.AudioSource.CAMCORDER;
            default: return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }

    @Override
    public Servidor.Microfone abrir(String nome, int taxa) throws Exception {
        if (Build.VERSION.SDK_INT < 31) throw new IOException("precisa de Android 12+ (este é " + Build.VERSION.SDK_INT + ")");
        int canal = AudioFormat.CHANNEL_IN_MONO;
        int cod = AudioFormat.ENCODING_PCM_16BIT;
        AudioRecord.Builder b = new AudioRecord.Builder();
        b.setContext(ContextoFalso.get());
        b.setAudioSource(fonte(nome));
        b.setAudioFormat(new AudioFormat.Builder().setEncoding(cod).setSampleRate(taxa).setChannelMask(canal).build());
        int min = AudioRecord.getMinBufferSize(taxa, canal, cod);
        b.setBufferSizeInBytes(Math.max(min > 0 ? min * 4 : 0, taxa * 2 / 2));   // ~0,5 s
        final AudioRecord r = b.build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IOException("AudioRecord não iniciou");
        }
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            throw new IOException("microfone ocupado");
        }
        return new Servidor.Microfone() {
            @Override
            public int ler(byte[] buf, int off, int len) throws IOException {
                int n = r.read(buf, off, len);
                if (n < 0) throw new IOException("AudioRecord.read = " + n);
                return n;
            }

            @Override
            public void fechar() {
                try { r.stop(); } catch (Exception ignored) { }
                r.release();
            }
        };
    }
}
