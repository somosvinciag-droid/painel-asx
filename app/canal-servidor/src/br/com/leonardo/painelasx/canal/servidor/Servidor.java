package br.com.leonardo.painelasx.canal.servidor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Servidor do canal paralelo, rodando NO CELULAR como usuário shell (igual ao scrcpy-server):
 *
 *   CLASSPATH=/data/local/tmp/painelasx-canal-VERSAO.jar app_process / br.com.leonardo.painelasx.canal.servidor.Servidor VERSAO
 *
 * Escuta no soquete abstrato "painelasx-canal" (a central chega nele pelo ADB: "localabstract:painelasx-canal").
 * Cada conexão manda UMA linha: PING | MIC fonte taxa | NET host porta | SAIR.
 *
 * Termina sozinho quando a entrada padrão acaba (o stream "exec:" fechou = cabo saiu ou a central mandou parar).
 * Este arquivo é Java puro (nada de android.* na compilação): roda também na JVM dos testes com "--tcp PORTA".
 * O que é do Android fica em MicAndroid/ContextoFalso/Ajustes, carregados só no celular.
 */
public final class Servidor {

    static final String SOQUETE = "painelasx-canal";

    interface Conexao {
        InputStream entrada() throws IOException;
        OutputStream saida() throws IOException;
        void fechar();
    }

    interface Escuta {
        Conexao aceitar() throws IOException;
    }

    interface Microfone {
        /** Lê PCM s16le; bloqueia até ter dados. */
        int ler(byte[] b, int off, int len) throws IOException;
        void fechar();
    }

    interface FabricaMic {
        Microfone abrir(String fonte, int taxa) throws Exception;
    }

    private static String versao = "?";
    private static FabricaMic mic;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) versao = args[0];
        int portaTeste = -1;
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--tcp")) portaTeste = Integer.parseInt(args[i + 1]);

        Escuta escuta;
        try {
            escuta = portaTeste >= 0 ? escutaTcp(portaTeste) : escutaAbstrata(SOQUETE);
        } catch (Exception e) {
            // Já existe outro servidor com este nome (ou sem permissão): este termina.
            System.out.println("ERRO escuta: " + e);
            System.exit(2);
            return;
        }
        if (portaTeste >= 0) {
            mic = new MicTeste();
        } else {
            mic = (FabricaMic) Class.forName("br.com.leonardo.painelasx.canal.servidor.MicAndroid").getDeclaredConstructor().newInstance();
        }

        // Vigia: a entrada padrão é o stream do ADB. Acabou = a central foi embora.
        Thread vigia = new Thread(() -> {
            try {
                byte[] b = new byte[64];
                while (System.in.read(b) >= 0) { /* nada */ }
            } catch (IOException ignored) {
            }
            System.out.println("fim da entrada: saindo");
            System.exit(0);
        }, "vigia");
        vigia.setDaemon(true);
        vigia.start();

        System.out.println("pronto " + versao + " sdk " + sdk());
        System.out.flush();
        while (true) {
            final Conexao c = escuta.aceitar();
            Thread t = new Thread(() -> atender(c), "conexao");
            t.setDaemon(true);
            t.start();
        }
    }

    static int sdk() {
        try {
            return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void atender(Conexao c) {
        try {
            InputStream in = c.entrada();
            OutputStream out = c.saida();
            String linha = lerLinha(in);
            if (linha == null) return;
            String[] p = linha.trim().split(" ");
            switch (p[0]) {
                case "PING":
                    escrever(out, "PONG " + versao + " " + sdk() + "\n");
                    break;
                case "SAIR":
                    System.out.println("pediram para sair");
                    System.exit(0);
                    break;
                case "MIC":
                    microfone(out, p.length > 1 ? p[1] : "voice_recognition", p.length > 2 ? Integer.parseInt(p[2]) : 16000);
                    break;
                case "NET":
                    if (p.length < 3) { escrever(out, "ERRO uso: NET host porta\n"); break; }
                    rede(in, out, p[1], Integer.parseInt(p[2]));
                    break;
                default:
                    escrever(out, "ERRO comando desconhecido\n");
            }
        } catch (Throwable e) {
            System.out.println("ERRO conexao: " + e);
        } finally {
            c.fechar();
        }
    }

    private static void microfone(OutputStream out, String fonte, int taxa) throws IOException {
        Microfone m;
        try {
            m = mic.abrir(fonte, taxa);
        } catch (Throwable e) {
            escrever(out, "ERRO " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            System.out.println("ERRO microfone: " + e);
            return;
        }
        System.out.println("microfone ligado (" + fonte + ", " + taxa + " Hz)");
        try {
            escrever(out, "OK\n");
            byte[] b = new byte[taxa / 25 * 2];   // blocos de 40 ms
            while (true) {
                int n = m.ler(b, 0, b.length);
                if (n < 0) break;
                if (n > 0) { out.write(b, 0, n); out.flush(); }
            }
        } catch (IOException fim) {
            // a central fechou o stream: normal
        } finally {
            m.fechar();
            System.out.println("microfone desligado");
        }
    }

    private static void rede(InputStream in, OutputStream out, String host, int porta) throws IOException {
        final Socket alvo = new Socket();
        try {
            alvo.connect(new InetSocketAddress(host, porta), 10_000);   // DNS do celular
            alvo.setTcpNoDelay(true);
            alvo.setKeepAlive(true);
        } catch (IOException e) {
            escrever(out, "ERRO " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            try { alvo.close(); } catch (IOException ignored) { }
            return;
        }
        escrever(out, "OK\n");
        Thread ida = new Thread(() -> {
            byte[] b = new byte[16384];
            try {
                OutputStream ao = alvo.getOutputStream();
                while (true) { int n = in.read(b); if (n < 0) break; ao.write(b, 0, n); ao.flush(); }
            } catch (IOException ignored) {
            }
            try { alvo.shutdownOutput(); } catch (IOException ignored) { }
        }, "net-ida");
        ida.setDaemon(true);
        ida.start();
        byte[] b = new byte[16384];
        try {
            InputStream ai = alvo.getInputStream();
            while (true) { int n = ai.read(b); if (n < 0) break; out.write(b, 0, n); out.flush(); }
        } catch (IOException ignored) {
        } finally {
            try { alvo.close(); } catch (IOException ignored) { }
        }
    }

    static String lerLinha(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 512) {
            int c = in.read();
            if (c < 0) return b.size() > 0 ? b.toString("UTF-8") : null;
            if (c == '\n') return b.toString("UTF-8");
            b.write(c);
        }
        return b.toString("UTF-8");
    }

    static void escrever(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---------------------------------------------------------------- escutas

    private static Escuta escutaTcp(int porta) throws IOException {
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("127.0.0.1", porta));
        return () -> {
            final Socket s = ss.accept();
            return new Conexao() {
                public InputStream entrada() throws IOException { return s.getInputStream(); }
                public OutputStream saida() throws IOException { return s.getOutputStream(); }
                public void fechar() { try { s.close(); } catch (IOException ignored) { } }
            };
        };
    }

    /** android.net.LocalServerSocket(nome) = soquete abstrato. Por reflexão para este arquivo não depender do android.jar. */
    private static Escuta escutaAbstrata(String nome) throws Exception {
        Class<?> cls = Class.forName("android.net.LocalServerSocket");
        Constructor<?> k = cls.getConstructor(String.class);
        final Object ss = k.newInstance(nome);
        final Method aceitar = cls.getMethod("accept");
        Class<?> ls = Class.forName("android.net.LocalSocket");
        final Method ent = ls.getMethod("getInputStream");
        final Method sai = ls.getMethod("getOutputStream");
        final Method fechar = ls.getMethod("close");
        return () -> {
            final Object s;
            try {
                s = aceitar.invoke(ss);
            } catch (Exception e) {
                throw new IOException(e);
            }
            return new Conexao() {
                public InputStream entrada() throws IOException { return (InputStream) chamar(ent, s); }
                public OutputStream saida() throws IOException { return (OutputStream) chamar(sai, s); }
                public void fechar() { try { chamar(fechar, s); } catch (IOException ignored) { } }
            };
        };
    }

    private static Object chamar(Method m, Object o) throws IOException {
        try {
            return m.invoke(o);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------- microfone de teste (JVM)

    /** Tom de 440 Hz em tempo real: só para os testes na JVM (sem AudioRecord). */
    static final class MicTeste implements FabricaMic {
        public Microfone abrir(String fonte, final int taxa) {
            return new Microfone() {
                long amostra = 0;
                final long inicio = System.nanoTime();

                public int ler(byte[] b, int off, int len) throws IOException {
                    int n = len / 2;
                    // ritmo real: não manda adiantado
                    long devido = inicio + (amostra + n) * 1_000_000_000L / taxa;
                    long espera = devido - System.nanoTime();
                    if (espera > 0) {
                        try { Thread.sleep(espera / 1_000_000L, (int) (espera % 1_000_000L)); } catch (InterruptedException e) { throw new IOException(e); }
                    }
                    for (int i = 0; i < n; i++) {
                        short v = (short) (Math.sin(2 * Math.PI * 440 * (amostra + i) / taxa) * 8000);
                        b[off + 2 * i] = (byte) v;
                        b[off + 2 * i + 1] = (byte) (v >> 8);
                    }
                    amostra += n;
                    return n * 2;
                }

                public void fechar() { }
            };
        }
    }
}
