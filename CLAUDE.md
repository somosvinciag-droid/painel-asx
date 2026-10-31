# Instruções para o Claude: repositório painel-asx

O dono é o **Leonardo** (pt-BR, não é programador). Fale simples, passo a passo, uma coisa de cada vez.
Este é o app da central multimídia Android (modelo K4811, Android 13, 2 GB de RAM) do Mitsubishi ASX 2012 dele.

## Como funciona

- Android nativo em Kotlin, **sem AndroidX** (só APIs do Android), telas montadas em código (`MainActivity`).
- `Controle` = AccessibilityService: toca em botões, volta/início, anota as telas que abrem (diagnóstico),
  roda a `Rotina` quando a central acorda e **confirma sozinho a instalação das atualizações**.
- `Comandos` entende frases em pt-BR, sem internet e sem custo.
- `Atualizador` lê `releases/latest` deste repositório (público) e instala o `painel-asx.apk` se o número for maior.

## Publicar uma versão

Cada push no `main` dispara `.github/workflows/montar.yml`: monta o APK (versionCode = número da execução),
assina com a chave do segredo `CHAVE_BASE64` e cria a release `vN`. A central procura atualização (de 15 em 15
minutos, quando acorda, quando o Controle liga, ou no botão "Procurar atualização"). Com o Controle ligado, ela
mesma confirma a instalação; se o app estava aberto, reabre sozinho.

## Conector do Claude (`servidor/`)

- Worker `painel-asx` na Cloudflare do Leonardo (segredos `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`),
  publicado pelo workflow `conector.yml` (só quando muda `servidor/**`). O endereço entra no app como `BuildConfig.SERVIDOR`.
- Durable Object `Central`: estado, registro (erros), diagnóstico e fila de comandos.
- A central se registra uma vez com uma chave aleatória dela (só o sha256 fica no servidor).
- O link do Claude é `<servidor>/mcp/<token>`; o token é gerado no app (botão "Copiar link do conector").
- Ferramentas: estado_central, registro_central, diagnostico_central, ler_tela_central, comando_central,
  tocar_na_tela, atualizar_app_central. Comandos com mais de 90 s são ignorados pela central.
- Para ver erros do app: `registro_central` com filtro "ERRO" ou "QUEBROU".

- A chave de assinatura fica **só** no segredo `CHAVE_BASE64` do GitHub. Nunca a coloque no repositório (é público).
- **Nunca troque essa chave**: com outra chave o Android recusa a atualização e o Leonardo teria
  de desinstalar e instalar de novo.
- Aqui no ambiente do Claude não há Android SDK; quem compila é o GitHub Actions. Depois de cada push,
  confira a execução (`gh run list`) antes de dizer que está pronto.

## Atualizações (a partir da v11)

- A verificação automática (15 em 15 min, ao acordar, ao abrir) só **avisa** ("Atualização disponível").
  Instala quando o Leonardo aprova: toque no aviso/botão do painel, ou ele pede ao Claude ("pode atualizar")
  e o Claude chama `atualizar_app_central`. Há a opção "Instalar sozinho" (desligada por padrão).
- Esta central **desliga a Acessibilidade (Controle) a cada atualização**. O app mostra "Controle desligado →
  Religar" (alerta na tela e notificação) e o Leonardo religa com um toque. **Não** usar root, nem fazer um app
  apertar sozinho o "Permitir" da Acessibilidade: isso é padrão de malware e foi descartado.
- Por isso: juntar mudanças e publicar poucas versões. Rascunhos ficam em ramos `rascunho-*`
  (o workflow `conferir.yml` só compila; nada vai para a central).
- O Painel ASX está na **whitelist** do "Config. Veículo" (senão a central o fecha a cada app aberto).

## Dados do carro (`LeitorCarro`)

- Mapa dos apps de fábrica (strings do AndroidManifest dos pacotes `com.nwd.*`, câmera, CAN…), escuta dos avisos
  encontrados, GPS, ré (pela tela da câmera) e `getprop`. Ferramentas: `dados_do_carro`, `mapa_da_central`.
- Central real: NWD, Unisoc UIS8581A, **Android 10 (API 29)** apesar de dizer 13. "Car Info" em perfil genérico.
- Leitor OBD2: suspenso pelo Leonardo (out/2026).

## Limites que já foram explicados ao Leonardo

- O app só vê do carro o que a central recebe (câmera de ré, botões do volante, etc.). O ar-condicionado do ASX é mecânico.
- Nada de mexer na parte elétrica do carro.
- Mensagens/ligações por voz: pelo Android Auto (ZLink) com "Ok Google", não por este app.

## v14: Jarvis e carro automatizado

- `TelaCarro` (preta e vermelha) abre quando o carro liga (central reinicia na partida -> `ServicoFixo` com uptime < 4 min
  -> `Jarvis.partida`: Turbo, tela do carro, "Bem-vindo, Leonardo", tempo/leads de manhã, lembretes, "Iron Man" no Spotify).
- `Carro` (GPS): limite de velocidade (`Config.limite`, padrão 110, "limite 100"), parado ligado 5 min, freada forte,
  nota da viagem, chegada ao destino, "chegando em casa" (500 m). Ré (pela tela da câmera, precisa do Controle): volume 30%.
- `Voz`: tipos RESPOSTA / IMPORTANTE (abaixa a música) / INFO (calada se houver música).
- `Lugares` (favoritos: casa, mineirita, familia), `Musica` (Spotify por busca, rádio online Radio Browser),
  `Clima` (Open-Meteo), `Relogio` (Turbo se memória < 20%, clima, modo noite pelo brilho, backup 6/6 h, fila),
  `Diario` (odômetro, abastecimentos, manutenção, calibragem 15 dias, anotações/pedidos -> servidor), `Backup`,
  `FotoCamera` (experimental).
- Servidor: `/app/nota`, `/app/abastecimento`, `/app/lembrete` (alarme do Durable Object -> ntfy no celular),
  `/app/backup`, `/app/recuperar` (link antigo do conector = dono; mantém o mesmo link), `/app/jarvis` (API do Claude).
- Jarvis conversa só se existir o segredo `ANTHROPIC_API_KEY` no GitHub (o `conector.yml` passa para a Cloudflare);
  `ATLAS_MCP_URL` (opcional) dá acesso de leitura à Atlas. Limite de 300 conversas/dia.
- Ferramentas novas do conector (chamar com `acao` se não aparecerem): anotacoes_do_carro, abastecimentos_do_carro,
  manutencao_do_carro, avisar_no_celular, falar_na_central, foto_da_camera, backup_do_carro; viagens_do_carro com_trajeto.

## v15–v17: voz própria e partida

- A central **não tem nenhum motor de voz** (TTS) do Android. Voz própria: `VozPropria` (sherpa-onnx v1.13.8 + Piper
  `pt_BR-faber-medium`), pacote baixado uma vez da release `voz-1` (pré-lançamento, criada pelo workflow `voz.yml`;
  NUNCA marcar como latest). API Kotlin copiada em `com/k2fsa/sherpa/onnx/` sem `System.loadLibrary`.
- Ajustes por voz: "voz mais grave/fina", "fala mais rápido/devagar", "voz normal" (prefs `voz_grave`, `voz_vel`).
- Saudação gravada em `files/voz/saudacao_{manha,tarde,noite}.pcm` (com fsync: a central perde energia de repente).
- A central às vezes **não reinicia**: dorme (`com.nwd.action.ACTION_MCU_STATE_CHANGE extra_mcu_state=3`,
  `sys.nwd.mcu.state`) e acorda. `LeitorCarro.mudouMcu` chama `Jarvis.partida` ao acordar.
- Câmera de ré desta central: pacote `com.nwd.backcar` (pisca na partida; ignorada nos primeiros 45–60 s).
- Ouvido do "Ei, Jarvis": pacote `ouvido-1` (workflow `ouvido.yml`, KWS gigaspeech 3.3M) já publicado; falta o app.
- A Acessibilidade (Controle) é desligada pela central em várias partidas: é opcional, sem alerta.

## v19: anjo da guarda e tela inicial

- Causa confirmada: a NWD dá force-stop no app quando a central dorme, e o force-stop APAGA a Acessibilidade (AOSP).
  O acesso às notificações (`Ouvinte`, NotificationListenerService) NÃO é apagado e o Android o religa sozinho.
- `Ouvinte.onListenerConnected` ("anjo da guarda"): religa o `ServicoFixo` e, se a central está acordada e
  `Jarvis.devoSaudar` (dormiu depois da última saudação, ou >10 min sem saudação e sem atualização recente), chama `Jarvis.partida`.
  Ligar UMA vez: botão "🛡 Ligar o Jarvis voltar sozinho" na tela do carro (ou `adb shell cmd notification allow_listener
  br.com.leonardo.painelasx/.Ouvinte` pelo notebook).
- `TelaCarro` tem filtro HOME; botão "★ Fazer do Painel ASX o sistema do carro" pede `persist.nwd.launcher.default` pelo
  canal da NWD (`Nwd.pedirProp`). `TodosApps` lista os apps e volta para a tela de fábrica (`com.android.launcher`).
- `Nwd.tecla` aperta voltar/início/volume pela MCU sem Acessibilidade. `Nwd.relatorio` (em `dados_do_carro`, campo "nwd")
  traz as props do "passo 0" do root (só leitura: ro.debuggable, ro.build.type, verifiedbootstate, treble...) e as regras da NWD.
- Root/firmware próprio: só com cópia completa + arquivo oficial (.pac); trocar o Android inteiro perde rádio, ré, BT e volante.

## v20: tela do carro "F3 · Gran Turismo"

- Escolhida pelo Leonardo entre 9 desenhos (prancheta "Tela do Carro: novas versões" e "Galeria da Tela do Carro").
  Faixa verde-branco-vermelho; 2/3 à esquerda (velocidade fina, barra até o limite, CONSUMO km/l e CUSTO R$/km, Jarvis + FALAR,
  destinos Casa/Mineirita/Família/Apps); 1/3 à direita a música (capa, tempo, botões grandes).
- Desenhada em 1280×720 e escalada (`s`) para a tela; fonte Big Shoulders Display (OFL) em `assets/fontes/`.
- Consumo: `Diario.consumoMedio` (último `km_por_litro` dos abastecimentos falados) e `Diario.custoPorKm`. Consumo
  instantâneo só com leitor OBD2 (suspenso). Comando de voz "consumo"/"quanto está o consumo".
- Música: `Musica.tocando` (título, artista, capa, posição) pela sessão de mídia (precisa do acesso às notificações).
- A voz do Jarvis continua a mesma (faber + grave 0,86): o Leonardo testou outras e preferiu manter.

## v21: ouvido próprio (botão FALAR sem internet)

- A central **não tem app de reconhecimento de voz** (o RecognizerIntent pedia para instalar um). `Ouvido`: Whisper base
  multilíngue (int8, sherpa-onnx, `language="pt"`) + Silero VAD, ~160 MB baixados uma vez (continua de onde parou) da release
  `ouvido-2` (workflow `ouvido-fala.yml`, pré-lançamento; NUNCA latest). Usa as `.so` do pacote `voz-1`.
- FALAR: bipe, grava (VOICE_RECOGNITION, 16 kHz), para 0,9 s depois que ele para de falar (máx. 12 s), texto -> `Comandos`.
  Mostra "OUVI: …" no rótulo do Jarvis. Filtra frases inventadas do Whisper ("legendas pela comunidade Amara.org"...).
- Permissão RECORD_AUDIO pedida no primeiro FALAR (o Leonardo toca em Permitir). Se o app Google existir e o ouvido ainda não
  baixou, usa o do Google. Memória < 12%: `Ouvido.liberar()`. Estado no conector: `ouvido`, `microfone_liberado`.
- Teste com voz sintética: o base erra palavras curtas ("rota" -> "poto"); frases longas saem bem. O tiny era pior. Ajustar com
  o que aparecer no registro ("ouvido: \"…\"").
- Possível conflito: o assistente de fábrica (com.txznet.txz) pode segurar o microfone; o registro avisa "microfone parece mudo".

## v22: revisão, saudação instantânea e partida leve

- Revisão por agente (ramo `rascunho-revisao`): ouvido não derruba o app com arquivo cortado (tamanho conferido, marcador
  "carregando"), VAD com janelas de 512, `Corretor.kt` (palavras que o Whisper troca: "poto"->rota, cachambu->Caxambu...),
  Fila sem travar a tela, gravações atômicas (viagens, registro, voz), `Arquivo.baixar` com continuação (ouvido, voz, APK),
  Atualizador confere o APK, Jarvis não sauda 2 vezes (`saudou_em` + BOOT_COUNT), "chegando em casa" só depois de >2 km.
- Saudação: `App.onCreate` -> `Jarvis.talvezPartidaNoInicio` (primeira coisa do processo); inicialização pesada fora da
  linha principal; o registro mede cada etapa ("inicio: … ms", "jarvis: carro ligou (X ms…)").
- Ouvido pré-carrega só depois da saudação e >= 20 s do início. Partida sem "Iron Man" (`musica_entrada_v2`, padrão desligado).
- `Partida.kt`: "--- abriu na partida ---" no diagnóstico (UsageStats; o Leonardo liga "Acesso ao uso" uma vez).
- Decidido pelo Leonardo: desligar e ligar rápido (soneca curta) continua saudando.

## v23: revisão 3 e meio da viagem

- `Corretor` não troca frase que perderia uma palavra real ("troquei o pneu" não vira óleo; "mais baixo" não vira rápido).
- Ouvido tenta baixar de novo assim que a internet volta (conector/despertador), no máximo 1 vez a cada 15 s.
- Meio da viagem (pedido do Leonardo): `Carro` grava `andando_em` (>15 km/h, a cada 20 s); `Jarvis.noMeioDaViagem` —
  andando agora, ou andou há < 3 min sem boot/sono depois — impede saudar e abrir a tela do carro por cima do Waze.

## v23 (rodada 4): otimizações
- Conector: relatório só quando o estado muda (fora `hora_central`) ou há linhas novas; senão de 5 em 5 min.
  Corpos > 1 KB vão com gzip depois de o relatório responder `gzip: true` (servidor: `descomprimir`). Servidor
  novo aceita com e sem gzip; o app volta a mandar sem gzip se receber 400/415/500.
- Backup automático: hora guardada em prefs `backup_envio` (fora do backup) e não manda se o sha-256 não mudou.
- Atualizador: ETag em prefs `atualizacao_cache` (304 = usa a resposta guardada).
- `Props` (Nwd.kt): SystemProperties direto, com volta para o getprop. O leitor só lê direto depois de conferir
  que dá igual ao getprop; getprop completo 1 vez por minuto. Lista de avisos de fábrica em `acoes_fabrica.txt`.
- Música: `MediaController.Callback` (a capa só chega quando muda). Viagens: texto das viagens fechadas guardado
  durante a viagem. `Viagens.resumo(comLista = false)` para telas e frases.
