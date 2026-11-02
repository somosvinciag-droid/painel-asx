package br.com.leonardo.painelasx.canal.servidor;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/**
 * Context com o pacote "com.android.shell" e o uid do shell (2000), para o AudioRecord do Android 12+.
 * Adaptado do FakeContext do scrcpy (Genymobile, Apache-2.0): só o necessário para o microfone.
 */
final class ContextoFalso extends ContextWrapper {

    static final String PACOTE = "com.android.shell";
    private static ContextoFalso unico;

    static synchronized ContextoFalso get() {
        if (unico == null) unico = new ContextoFalso();
        return unico;
    }

    private ContextoFalso() {
        super(Ajustes.contextoDoSistema());
    }

    @Override
    public String getPackageName() {
        return PACOTE;
    }

    @Override
    public String getOpPackageName() {
        return PACOTE;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACOTE).build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
