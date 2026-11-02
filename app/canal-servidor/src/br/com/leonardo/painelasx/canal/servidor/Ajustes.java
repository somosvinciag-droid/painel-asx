package br.com.leonardo.painelasx.canal.servidor;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Prepara o processo app_process para parecer um app (ActivityThread, appInfo, Application).
 * Adaptado de Workarounds.java do scrcpy (Genymobile, Apache-2.0). Tudo por reflexão: falhar aqui não é erro.
 */
final class Ajustes {

    private static Class<?> classeAT;
    private static Object at;

    private Ajustes() { }

    private static synchronized void criarActivityThread() throws Exception {
        if (at != null) return;
        classeAT = Class.forName("android.app.ActivityThread");
        Constructor<?> k = classeAT.getDeclaredConstructor();
        k.setAccessible(true);
        at = k.newInstance();
        Field atual = classeAT.getDeclaredField("sCurrentActivityThread");
        atual.setAccessible(true);
        atual.set(null, at);
        Field sistema = classeAT.getDeclaredField("mSystemThread");
        sistema.setAccessible(true);
        sistema.setBoolean(at, true);
    }

    static void aplicar() {
        try {
            criarActivityThread();
        } catch (Throwable t) {
            System.out.println("aviso: ActivityThread: " + t);
            return;
        }
        // Android 12+: ConfigurationController (antes do contexto do sistema)
        try {
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Class<?> ati = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> k = cc.getDeclaredConstructor(ati);
            k.setAccessible(true);
            Object o = k.newInstance(at);
            Field f = classeAT.getDeclaredField("mConfigurationController");
            f.setAccessible(true);
            f.set(at, o);
        } catch (Throwable t) {
            System.out.println("aviso: ConfigurationController: " + t);
        }
        // appInfo com o pacote do shell
        try {
            Class<?> abd = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> k = abd.getDeclaredConstructor();
            k.setAccessible(true);
            Object dados = k.newInstance();
            Class<?> ai = Class.forName("android.content.pm.ApplicationInfo");
            Object info = ai.getConstructor().newInstance();
            ai.getField("packageName").set(info, ContextoFalso.PACOTE);
            Field f = abd.getDeclaredField("appInfo");
            f.setAccessible(true);
            f.set(dados, info);
            Field b = classeAT.getDeclaredField("mBoundApplication");
            b.setAccessible(true);
            b.set(at, dados);
        } catch (Throwable t) {
            System.out.println("aviso: appInfo: " + t);
        }
        // Application com o ContextoFalso
        try {
            Class<?> app = Class.forName("android.app.Application");
            Method nova = Class.forName("android.app.Instrumentation").getMethod("newApplication", Class.class, Context.class);
            Object a = nova.invoke(null, app, ContextoFalso.get());
            Field f = classeAT.getDeclaredField("mInitialApplication");
            f.setAccessible(true);
            f.set(at, a);
        } catch (Throwable t) {
            System.out.println("aviso: Application: " + t);
        }
    }

    static Context contextoDoSistema() {
        try {
            criarActivityThread();
            Method m = classeAT.getDeclaredMethod("getSystemContext");
            return (Context) m.invoke(at);
        } catch (Throwable t) {
            System.out.println("aviso: contexto do sistema: " + t);
            return null;
        }
    }
}
