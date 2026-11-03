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

## v23: volume da voz
- O Leonardo achou a voz baixa "mesmo aumentando o som". A voz própria agora toca pelo canal da MÚSICA (USAGE_MEDIA,
  segue o botão de volume; antes era navegação) e é reforçada (`VozPropria.reforcar`: ganho `voz_volume`, padrão 1,6,
  até 3, nunca passa de 95% do pico). Comandos: "voz mais alta" / "voz mais baixa" (±0,4).

## Celular (fase 1)

- Segundo app no mesmo repositório: módulo `:celular`, "Painel ASX Celular" (`br.com.leonardo.painelasx.celular`),
  Kotlin sem AndroidX, telas em código, minSdk 30, targetSdk/compileSdk 35 (o `:app` continua 34; AGP 8.5.2 com
  `android.suppressUnsupportedCompileSdk=35`). Assinado com a MESMA chave da central (`CHAVE_ARQUIVO`, pkcs12, alias `painel`).
- Publicação: `.github/workflows/celular.yml` (push no main em `celular/**` ou manual) monta e publica
  `painel-asx-celular.apk` na release FIXA `celular` (apaga e cria de novo, sempre `--prerelease --latest=false`).
  NUNCA marcar como latest: o Atualizador da central lê `releases/latest`. `montar.yml` ignora `celular/**` (mudança só no
  celular não gera versão da central, que derruba o Controle) e monta só o `:app`. `conferir.yml` compila os dois e,
  se falhar, põe os erros numa anotação (os logs do Actions não abrem no ambiente do Claude; ler com
  `gh api repos/.../check-runs/<job>/annotations`).
- Desenho (fase 1 = rede): o celular compartilha a internet por Bluetooth (ancoragem) e a central é cliente. O `Ponte`
  (serviço `connectedDevice|microphone`) abre um servidor TCP na porta 48110 SÓ na interface `bt-pan` e só aceita
  clientes da mesma sub-rede. A central (`CaboCelular`) acha o celular pelo gateway da rede Bluetooth e conecta.
  Não briga com o ZLink: o cabo continua com o Android Auto e carregando.
- Cabo AOA: o Leonardo decidiu (31/10) que o cabo é do Painel ASX (abre mão do Android Auto com fio). Padrão LIGADO desde
  a primeira execução (`cabo_painel` true, alias ligado uma vez); o Bluetooth fica para internet, ligações e reserva dos dados.
  Música: Spotify na própria central (Spotify Connect pelo celular). REGRA DO LEONARDO: tudo é configurado UMA vez
  ("usar por padrão"/"Sempre") e depois é só plugar o cabo — nada de pedir permissão ou abrir app de novo.
  No celular, a `AcessorioActivity` (showWhenLocked + turnScreenOn NO MANIFESTO, sem toques) abre por cima do bloqueio ao
  plugar e liga a Ponte com microfone no `onResume` (só "na frente" o Android 14+ dá o microfone; reserva em 1,2 s).
- Cabo, regras que já quebraram (revisão de 31/10):
  - Logo depois do modo acessório o app do celular ainda não lê: `CanalAoa.escrever` insiste até 10 s (antes a 1ª
    escrita de 1 s falhava e o cabo nunca era tentado de novo).
  - A central repete o `ola` de 3 em 3 s até o celular responder (`Conexao.respondeu`); no cabo, 10 s sem NADA
    (`Conexao.recebidas == 0`) = outro app (ZLink/Android Auto) com o cabo → `CaboUsb.alheios` até soltar; segue pela rede.
  - Cabo que cai com o celular plugado (central dormiu/reabriu o app): a central reabre (`CaboUsb.caboTerminou`) e o
    celular reabre o acessório sozinho (`Ponte.reabrirCabo`; o Android só abre a AcessorioActivity numa plugada nova).
  - Permissão USB da central: o aviso USB_DEVICE_ATTACHED NÃO pede na hora (o "usar por padrão" dá a permissão e abre a
    `CaboActivity` logo depois); não empilha diálogo sobre o "Abrir com" do Android (só pede 15 s depois, ou na hora se
    o celular estiver em modo MTP); no máximo 4 pedidos seguidos (`cabo_pedidos`, zera quando a permissão vem sozinha
    ou ao escolher o modo do cabo de novo). O "padrão" do Android 10 é por aparelho EXATO (vendor/product/série): se o celular
    aparecer em outro modo USB, pode perguntar de novo uma vez.
  - FALAR pelo celular pega o foco de áudio (pausa o Spotify da central) e dá o bipe quando o celular avisa `ouvindo`.
  - Música: se a própria central está tocando (Spotify Connect), a tela e os botões usam a sessão da central
    (`Musica.centralTocando`); a do celular só quando a central não toca (capa do celular se faltar a da central).
  Modo do cabo (desde o receptor, ver "## Receptor de Android Auto"): escolha com 3 opções na MainActivity
  (`CaboUsb.modo`, prefs `cabo_modo`): "Android Auto pelo Painel" (padrão), "app Painel ASX Celular" (teste) e "ZLink".
  No ZLink a central não mexe no cabo (o `activity-alias` `.CaboPeloPainel` com o filtro USB
  fica desabilitado). Modo "app": liga o alias, pede a permissão do Android (o Leonardo toca em Permitir) e manda os textos
  AOA `Leonardo`/`PainelASX` (os mesmos do `accessory_filter.xml` do celular). Mesmo protocolo nos dois caminhos
  (interface `Canal`: `CanalRede`, `CanalAoa`/`CanalAcessorio`).
- Diagnóstico "--- cabo USB ---" (só leitura): `config_disableUsbPermissionDialogs`, uid/flags/assinatura do ZLink,
  quem tem filtro USB (telas e receptores, prioridade e XML) e os últimos aparelhos plugados com as interfaces.
  Serve para decidir caso A (o ZLink depende de ser o único "padrão") ou caso B (permissão automática).
- Protocolo: 4 bytes big-endian de tamanho + JSON UTF-8, máx. 256 KB (`Quadro.kt`, igual nos dois apps).
  Uma linha lê, outra escreve; a central manda `ping` a cada 5 s e derruba depois de 15 s sem nada (o celular, 20 s).
  Central → celular: `ola` (id, mensagens), `ajustes`, `controle` (anterior/alternar/proxima/play/pausa/ir), `ouvir`, `parar`, `ping`.
  Celular → central: `pareando`, `recusado`, `ola` (modelo, android, app, mic), `musica`, `capa` (JPEG 320 px base64, só
  quando muda), `bateria`, `aviso`, `ouvindo`, `ouvi` (texto ou erro), `ligacao`, `sinal`, `pong`.
- Pareamento (repo público, nada de senha no código): a central gera um id aleatório (prefs `celular`, FORA do backup).
  Central nova → o celular responde `pareando` e mostra "Permitir o Painel ASX do carro?" (código = 4 primeiros
  caracteres do id; a central mostra o mesmo código no cartão "Celular"). Permitida: aceita direto; recusada: `recusado`
  (a central espera 10 min). Nada é atendido antes do "Permitir". "Esquecer o carro" na tela do celular.
- Na central: `Musica.tocando`/`tocandoAgora` preferem o celular; os botões da TelaCarro mandam `controle` (sem música no
  celular, a tecla de mídia de sempre); FALAR manda `ouvir` → o texto passa pelo `Corretor` e vai para `Comandos`;
  erro ou 8 s sem o celular começar a ouvir (ou 20 s no total) → ouvido próprio. Bateria "CEL 87%" no topo da TelaCarro.
  Conector: `celular: {ligado, transporte, modelo, bateria, musica_titulo}`.
- PRIVACIDADE: o texto das mensagens NUNCA vai para o Registro, conector, backup, diagnóstico nem Jarvis/Claude. Só é
  falado (`Voz.falarPrivado`, não aparece na tela do carro, que o Controle/Claude conseguem ler), só com "Ler mensagens"
  ligado (padrão desligado; o celular só manda avisos se a central pedir) e nunca durante ligação (até 3 ficam na memória
  e são faladas quando a ligação acaba). Não criar comando de voz que devolva texto de mensagem (o `comando_central`
  devolve a resposta ao Claude). O app do celular tem INTERNET só por causa do socket local e não manda nada para fora
  (o reconhecimento de voz é do app Google, que pode usar a internet dele).
- Microfone no celular (Android 14+): o serviço só ganha o tipo `microphone` quando é ligado com o app na frente (abrir
  o app ou tocar no aviso da ponte). Ligado pelo boot/acesso às notificações, fica só `connectedDevice` e o `ouvir`
  responde erro na hora (a central usa o ouvido próprio) + aviso "Toque aqui...".
- Passos do HyperOS (botões na tela do app do celular): avisos, microfone ("Durante o uso do app"), acesso às
  notificações (se "configuração restrita": página do app → ⋮ → Permitir configurações restritas), bateria sem restrição,
  início automático, página do app ("Pausar atividade" desligado), "Outras permissões" (Mostrar na tela de bloqueio,
  janelas em segundo plano), ancoragem Bluetooth.

## Receptor de Android Auto (rascunho `rascunho-receptor`)

O "ZLink nosso": o próprio Painel é o receptor de Android Auto (AA) pelo cabo. Modo do cabo PADRÃO "Android Auto pelo
Painel" (`CaboUsb.MODO_AA`); "app do celular" (`MODO_APP`, o JSON de antes) fica como opção secundária; "ZLink" (`MODO_ZLINK`)
= o Painel não mexe no cabo. Só cabo (sem Bluetooth, sem Wi-Fi). Configura uma vez ("usar por padrão" do USB) e pronto.
- Decisão do Leonardo (31/10): ele NÃO usa o Android Auto do ZLink. Não há "Android Auto de hoje" a preservar: o padrão
  "Android Auto pelo Painel" vale direto desde a publicação, sem toque extra e sem regra para devolver o cabo ao ZLink.
  A opção "ZLink" continua no seletor, sem prioridade. O que é obrigatório: cabo que não responde não trava o Painel e não
  fica pedindo permissão (no máximo 4 pedidos seguidos; celular que nunca responde ou que responde e cai logo 8 vezes
  seguidas = largado até o cabo ser solto, só com aviso e registro).

### Desenho
- `CaboUsb` manda o celular para o modo acessório com a identidade do AA (`TEXTOS_AA`: "Android"/"Android Auto"/"2.0.1"/
  "HU-AAAAAA001", as do Headunit Revived). O app Android Auto do celular abre sozinho. O 18d1:2d00 que volta vai para
  `AndroidAuto.entregarCabo` (no modo app vai para o `CaboCelular`).
- `AaSessao` (sem Android, testada na JVM): versão 1.2 (o celular escolhe; ex. 1.7) com 3 tentativas e descarte dos bytes
  velhos do cabo (0,5 s) → TLS (central = cliente, `SSLEngine`) → "autenticou" (aberto) → tudo cifrado: pedido/anúncio
  de serviços, abertura de canais, foco de áudio (sempre concede), sensores, mídia (confirma cada pacote), ping nos dois
  sentidos (a central manda de 5 em 5 s; 30 s sem nada = caiu), tchau. Linha de leitura (`aa-ler`) e de escrita
  (`aa-escrever`) próprias; nada de exceção sai dela (`rodar()` devolve o motivo).
- `AaQuadro`: cabeçalho [canal][flags][tamanho 2 bytes] (+ total 4 bytes só no 1º pedaço). Flags: 1 primeiro, 2 último,
  4 controle, 8 cifrado (0x0b inteira; 0x09/0x08/0x0a pedaços). `Separador` (buffer reaproveitado) e `Remontador`
  (vídeo NÃO é guardado: só avisado para confirmar; mensagens > 4 MB descartadas mas confirmadas).
- `AaProto`/`AaMensagens`: protobuf ESCRITO À MÃO (sem dependência), só as mensagens usadas. Campos tirados dos .proto do
  Headunit Revived.
- `AaTls`: certificado de referência em `assets/aa/` (abaixo). TLS 1.2 é o normal; TLS 1.3 também funciona (o "ticket"
  que chega depois em quadro aberto passa pelo motor TLS).
- `AaSom`: um `AudioTrack` por canal (música 48 kHz estéreo; voz do GPS e sistema 16 kHz mono), todos no canal da MÚSICA
  (volume da central), pedaços reaproveitados, linha própria por canal. Foco: música pede GANHO (o Jarvis falando →
  o Android abaixa; ouvido do FALAR → mudo; outro app tocando na central → mudo + "pausa" ao celular); voz do GPS pede
  "pode abaixar" enquanto fala.
- `AndroidAuto` (cola com a central): estado, música (título/artista/capa ≤320 px/tempo/app), próxima curva, teclas,
  `MediaSession` própria (volante e "próxima" do Jarvis chegam ao celular), modo noite (faróis ou pôr do sol, 1/min),
  `estadoConector()` → campo `android_auto: {ligado, etapa, app_tocando, navegando}` no estado do conector,
  diagnóstico "--- android auto ---". Reconexão: quando a conversa cai com o cabo plugado, `CaboUsb.caboTerminou` reabre
  (nunca respondeu = outro app com o cabo → largado até soltar).

### Canais anunciados (ids escolhidos por nós, os mesmos do Headunit Revived)
| id | serviço | o que fazemos |
|---|---|---|
| 1 | sensores: dirigindo (13) + noite (10) | "sem restrição" ao abrir; noite pelo `LeitorCarro.faroisAcesos()`/`Clima.ehNoite()` |
| 2 | vídeo 800x480 H.264 30 fps (obrigatório) | foco de vídeo **NATIVO** logo após combinar; pedidos de tela respondidos NATIVO; quadros que vierem são confirmados e jogados fora (nada de decodificar) |
| 3 | entrada: só teclas 85/87/88/126/127 + tela 800x480 | botões da TelaCarro, volante (MediaSession) e Jarvis (`Rotina.teclaMidia`) |
| 4 | voz (fala do GPS/assistente) 16 kHz mono | alto-falante do carro |
| 5 | sistema 16 kHz mono | alto-falante do carro |
| 6 | mídia 48 kHz estéreo | alto-falante do carro |
| 9 | informação da música (0x8003 dados, 0x8001 estado) | `Musica.tocando` (fonte "SPOTIFY · ANDROID AUTO") |
| 10 | navegação só com códigos (0x8004/0x8005 antigas, 0x8006/0x8007 novas) | frase discreta no rótulo `detalhe` da TelaCarro (no lugar dos km do dia), sem redesenho |
- NÃO anunciados: microfone (7) e Bluetooth (8). Tipo de veículo = **moto (3)** no `headunit_info` (id "painel-asx-2012-moto"):
  é o único tipo em que o celular grava com o próprio microfone ("Ok Google" do celular, opcional); com outro tipo e sem
  microfone o celular aborta ("No audio/mic"). Precisa de Android 11+ no celular (o Redmi 15 tem).
- Ligações: o AA NÃO leva o som da ligação pelo cabo (só Bluetooth HFP). Fica no celular. Canal de telefone não anunciado.

### FALAR (decisão do Leonardo, 31/10)
- O FALAR é SEMPRE do Jarvis (Comandos/Jarvis), nunca do Assistente do Google. Nada de tecla de voz para o AA.
- `FonteDeVoz` (interface) + `FontesDeVoz.escolher()`: hoje o app do celular (quando ligado); com o AA ligado pelo cabo,
  por enquanto o Ouvido próprio da central. Ponto de encaixe para depois: um canal paralelo no MESMO cabo (provavelmente
  ADB, a decidir com o Leonardo) com o microfone do celular para o Jarvis — basta implementar `FonteDeVoz`.

### Licença
- Os arquivos `Aa*.kt` e `AndroidAuto.kt` adaptam o Headunit Revived / Open Headunit (AGPLv3; Copyright 2011-2015
  Michael A. Reid e os contribuidores). Têm o aviso AGPLv3 no topo; o texto está em `LICENSE-AGPL-3.0.txt` e os avisos em
  `NOTICE.md`. Como estão no mesmo APK, o app é distribuído nos termos da AGPLv3. O repo é público: isso cumpre a AGPL.
  Ao mexer nesses arquivos, manter os avisos.

### Certificado
- `app/src/main/assets/aa/cert.pem` + `chave.pem`: certificado de REFERÊNCIA "Google-Android-Reference" (emissor
  "Google Automotive Link", válido 2014–2044), copiado do Headunit Revived (`res/raw/cert`/`privkey`); o mesmo par está
  no aasdk/OpenAuto. É a ÚNICA exceção documentada à regra "nada de chave no repo": é público e não é do Leonardo.
- Risco: o Google pode deixar de aceitar esse certificado (ou mudar o AA, como fez com o sem fio no AA 17.4+). Aí todos os
  receptores abertos param juntos. O certificado do lado do celular vence em 31/12/2026 (não conferimos, como o HUR).

### Revisão independente (31/10, ramo `rascunho-receptor`)
- `AaSom`: o "parar" do celular (fim do som) NÃO corta mais o fim da voz do GPS (a linha do som toca o que já chegou e só
  então `AudioTrack.stop()`); foco negado tenta de novo de 2 em 2 s (antes a música ficava muda até o celular recomeçar);
  depois de perder o foco de vez (outro app na central), o foco volta quando o celular recomeça a mandar som após >1,5 s
  de pausa; `AudioTrack` que não abre não derruba a conversa (fica sem som naquele canal); a linha do som termina antes
  do `release()`.
- `AaTls`/`AaSessao`: registros de controle do TLS (TLS 1.3 KeyUpdate) saem na ordem da numeração (`cifrarComControle`,
  a leitura só acorda a escrita); antes uma troca de chave com tráfego nos dois sentidos derrubava a conexão (reproduzido
  no teste). Bytes do aperto de mão que chegam abertos depois do TLS (ticket do TLS 1.3) partidos entre quadros ficam
  guardados até o resto chegar (antes: "unwrap BUFFER_UNDERFLOW" e queda). Erro do lado da central ao tratar UMA
  mensagem (som, tela) vira "ERRO aa: ao tratar…" no registro e a conversa segue.
- `CaboUsb.caboTerminou`: responde mas cai em < 20 s mais de `MAX_QUEDAS` (8) vezes seguidas = larga até soltar.
- Riscos que continuam (só o carro responde): foco de vídeo NATIVO desde o início (válido no protocolo: é como um carro de
  fábrica que fica na tela dele até o motorista escolher o Android Auto, mas nenhum receptor aberto de referência faz
  isso; o HUR sempre projeta); `escreverBruto` repete por até 3 s uma escrita USB que falhou (o HUR desiste na 1ª, menos a
  versão): se o USB tiver mandado só parte, o celular cai e a central reconecta; ping da central de 5 em 5 s (o HUR não
  manda; o aasdk/OpenAuto manda).

### Testes JVM (fora do repo)
- `scratchpad/receptor/rodar.sh`: compila `AaProto/AaMensagens/AaQuadro/AaTls/AaSessao` com o kotlinc e roda 145 testes:
  protobuf igual ao protobuf OFICIAL (bytes gerados em Python com os .proto do HUR, nos dois sentidos), quadros/pedaços,
  e uma conversa completa com um "celular falso" (TLS 1.2 e 1.3 de verdade, cabo entregando em pedaços aleatórios).
  Revisão (31/10): +29 testes (174 no total) — TLS do celular partido entre quadros (1.2 e 1.3), as 4 respostas de foco
  de áudio, confirmações (dados sim, codec não), canais fora de ordem, protobuf quebrado/tipos e canais desconhecidos/
  microfone pedido, erros do lado da central, tchau/parar duas vezes, celular mudo, escrita recusada, lixo e quadro
  gigante; e uma 2ª execução com KeyUpdate a cada 16 KB (`keylimits.properties`, 400 pacotes de som + teclas).

### Briga com o ZLink (ramo `rascunho-zlink`, 31/10)
- Teste de 31/10 20:03: o Redmi entrou como 2717:ff80 (ancoragem USB); o nosso pedido AOA falhou calado e o ZLINK5 pôs o
  celular no modo acessório DELE (2d00, "nenhum app para este acessório"); o ZLink repetiu o pedido no 2d00 a cada 1–2 s
  por ~10 s e parou (o celular voltou sozinho como 2717:ff40 PTP, pelo `ACCESSORY_REQUEST_TIMEOUT` de 10 s do celular).
  O ff40 o ZLink não tocou: o nosso pedido do AA saiu, versão ok (1.7) e o TLS ficou calado ~8 s até o celular sair do cabo
  (provável: celular esperando desbloquear/aceitar o carro novo; o registro novo responde). O "Abrir com" (Fotos x Painel)
  apareceu porque o ff40 tem interface PTP: escolher "Painel ASX" + "Sempre" uma vez.
- `CaboUsb`: receptor USB (prioridade 999) na linha `cabo-rapido`; o pedido do AA sai na hora e o registro diz quantos ms
  depois do aviso e qual passo falhou. "Plugada" = do 1º aviso até 6 s sem celular no cabo. 2d00 que aparece sem pedido
  nosso nos últimos 4 s (depois de ver o celular normal) = de outro app → pedido de novo com a identidade do AA (o modo
  acessório aceita). Acessório que não responde à versão, ou que respondeu e parou no TLS, também recebe pedido novo.
  Máx. 8 pedidos por plugada (3 na hora, depois 1, 2, 3… s); passou = desiste até soltar o cabo, com aviso e voz.
  Zera quando o AA liga. Avisos do ZLink (ação `com.zjinnova.zlink`, extra `status`) vão para o registro e o diagnóstico.
- `AaSessao`: celular calado no TLS espera até 60 s; depois de 4 s a etapa `tls: esperando o celular…` faz a central falar
  "Desbloqueie o celular e aceite o Android Auto" (1 vez por plugada). Registro: "o celular respondeu ao TLS em X ms".
- Se o ZLink nunca cansar (8 pedidos perdidos), a saída que sobra é a do Headunit Revived: desligar o ZLink por ADB
  (fora das regras atuais do Leonardo; perguntar antes).
- Testes JVM: `scratchpad/zlink-a2/rodar.sh` (188 + 5), com o teste [10] "celular calado no TLS".

### Desligar o ZLink (pacotes `zlink/` e `canal/`, ramo `rascunho-zlink`)
- Por que o Painel não ganha a briga sozinho (CONFIRMADO no AOSP 10): o aviso USB vai a TODOS os receptores de tempo de
  execução ao mesmo tempo (prioridade não vale), `config_disableUsbPermissionDialogs=true` dá o cabo a qualquer app, e
  qualquer app com o celular aberto manda os pedidos AOA. Desligar outro app exige `CHANGE_COMPONENT_ENABLED_STATE`
  (signature|privileged); `killBackgroundProcesses` não mata serviço em 1º plano.
- Diagnóstico "--- zlink ---" (`DiagnosticoZlink`, SÓ leitura): estado do pacote, raio-x do manifesto (`RaioXApk`, AXML sem
  dependências), quem recebe os avisos, props do adb, SONDAGEM do adbd em 127.0.0.1 (só manda CNXN, nunca a chave: nenhum
  aviso aparece), se existe o `UsbDebuggingActivity`, e fábrica NWD só lida (`factory_feature` bit 0x40000, .ini).
  As strings do dex/arsc do ZLink (alguns MB) só entram quando o Claude pede `diagnostico_central` (`completo = true`);
  no botão "Enviar diagnóstico" (linha principal) vai o último raio-x guardado.
- Botões "⛔ Desligar o ZLink (uma vez)" e "↩ Religar o ZLink" no cartão Celular (`DesligarZlink`). SÓ com o toque do
  Leonardo, nunca sozinho nem pelo conector. Sonda o adbd local; fechado = uma frase ("a central não deixa o Painel usar o
  modo de depuração dela") e nada muda. Aberto = cliente ADB (`canal/AdbUsb.kt`, chave em `noBackupFilesDir/adb/
  central-local.pk8`) roda `pm disable-user --user 0 com.zjinnova.zlink` (o shell pode, CONFIRMADO no PMS do Android 10;
  vale depois do boot), `am force-stop` e confere em `pm list packages -d`. Religar = `pm enable`. Na 1ª vez a central
  mostra "Permitir depuração USB?": quem toca é o Leonardo ("Sempre permitir" + OK). Cada passo vai para o registro
  ("zlink: …"). A porta do adb não é mexida (o Painel não a abre nem fecha).
- SUPOSIÇÃO (só a central responde): o adbd da central escutar em rede. Aposta: fechado (configfs com ep0 = só USB).
- Opções que existem mas NÃO estão no app (perguntar ao Leonardo antes; regra: nada de escrever em fábrica sem ele pedir):
  - Menu de fábrica NWD (`com.nwd.factory.setting/.LoginActivity`, senha padrão 1617 na suíte GDM K4811): desligar a função
    "CarPlay" (0x40000) põe o ZLink no `/cache/Carkit_Uninstall.ini` ("não instalar no boot"), salvo se
    `carplay_install_enable=1` no `UartConfig.ini`. ~8–10 toques do Leonardo; visto no código NWD, não nesta central.
  - Abrir a porta do adb pelo canal NWD de propriedades (`persist.adb.tcp.port=5555`): precisaria de um app ajudante com
    targetSdk 19 (o Settings.System recusa o Painel) e de um reinício; o canal não aplicou nada em 31/10.
- Testes JVM (fora do repo): `scratchpad/zlink/teste/rodar.sh` (18): sondagem (fechada, porta que não é adb, adbd falso
  sem mandar chave), desligar com `pm`/`am` falsos (1ª vez pede o toque, 2ª "já estava"), religar sem toque, sem adb,
  relatório com e sem strings. Estudo completo: `scratchpad/zlink/agente1.md`.

### O que só o teste no carro responde
- Se o ZLink pega o cabo antes (caso A/B; ver o diagnóstico "--- cabo USB ---") e se o "usar por padrão" segura.
- Se o AA do Redmi aceita o certificado de referência e o tipo "moto" sem perguntar nada (1ª vez: aceitar o carro novo
  no celular desbloqueado).
- Se o celular PARA de mandar vídeo com o foco nativo (o registro conta "vídeo recebido e descartado") e se o AA aceita
  ficar sempre em nativo sem desistir.
- Volume/latência do som pelo `AudioTrack` nesta central; convivência do foco de áudio com o Jarvis e o ouvido.
- Se o volante (MediaSession) chega ao celular e se o Google Maps manda a navegação nova (0x8006/0x8007) ou a antiga.
- Se o "Ok Google" do celular funciona com a central dizendo "moto".

## v30–v32 (01/11): Android Auto na tela e F3 apagada
- Teste 01/11 09:09: com o foco de vídeo NATIVO o Android Auto do celular desiste em ~9 s ("erro de comunicação 7").
  v30: foco PROJETADO. O ZLink perdeu a corrida (AA ligou 2,2 s depois de plugar) e o Spotify tocou pelo cabo.
- Pista das quedas: mandar áudio (ditado do app Claude) com o Bluetooth ligado na central abre "ligação" HFP
  (`com.bt.ACTION_BT_BEGIN_CALL_ONLINE`) e o AA caiu ~4 s depois. Testar sem mandar áudio.
- O cabo com o AA não leva internet: a central usa o roteador Wi-Fi (ou ancoragem Bluetooth) do celular.
- DECISÃO do Leonardo (01/11 10:15): o Painel MOSTRA a tela do Android Auto (substitui o "sem espelhamento" para o AA).
  v31: `TelaAndroidAuto` (MediaCodec H.264 direto na SurfaceView, fila própria `aa-video`, toques -> `Aa.toque`,
  conferido com o protobuf oficial). Abre sozinha quando o AA liga. Vídeo 1280x720 (densidade 213) se a tela tiver
  >= 1280 px, senão 800x480 (160): o AA precisa de >= 748x450 dp (Open Headunit #1101). `AaSessao.ligarVideo`/`pedirImagem`
  (solta e pega o foco = quadro-chave). "Sair" do AA (foco nativo pedido) fecha a tela.
- DECISÃO do Leonardo (01/11 10:29): APAGAR a F3 de vez. v32: `Painel.F3_LIGADA = false` (nada abre a TelaCarro; se algo
  abrir, ela manda para a tela de fábrica), filtro HOME tirado, tela inicial devolvida à de fábrica uma vez
  (`Painel.devolverTelaInicial`). O FALAR (`Painel.abrir(ouvir = true)`) abre a MainActivity. Sem botão PAINEL no AA.
- Próximo (depois de o AA funcionar direito): personalizar.

## Canal pelo cabo (ADB): microfone e internet do celular (ramo `rascunho-canal`)
- Pedido do Leonardo: o Jarvis ouve pelo microfone do CELULAR pelo MESMO cabo do Android Auto. Só cabo, sem Bluetooth.
- Como: com a "Depuração USB" ligada, o celular no modo acessório vira 18d1:2d01 (interface 0 = acessório do AA,
  interface 0xFF/0x42/0x01 = ADB). `ConexoesUsb` (CaboUsb.kt) dá UMA `UsbDeviceConnection` por aparelho, dividida
  (contagem de usos) entre o `CanalAoa` e o canal ADB; cada um reivindica só a sua interface. Sem ADB (2d00): canal
  desligado, AA igual.
- Código: `canal/AdbUsb.kt` (cliente ADB: CNXN/AUTH RSA, OPEN/WRTE/OKAY, sync push, exec; o MESMO usado pelo
  `zlink/DesligarZlink` por TCP; `TransporteUsb` lê 1 pacote USB por vez com prazo de 1 s), `canal/CanalParalelo.kt`
  (`CanalParalelo`, `MicrofoneRemoto`, `ProxyLocal`, `ProxyDoApp`, `CanalCabo` = laço de plugada sem Context) e
  `CanalDoCabo.kt` (cola com a central). Chave da central: `noBackupFilesDir/adb/chave_central.pk8` (fora do backup).
- Servidor no celular: `app/canal-servidor/src` (Java, roda como usuário shell via `app_process`, igual ao scrcpy;
  Apache-2.0 no NOTICE). `app/canal-servidor/montar.sh` (javac + d8) gera `app/src/main/assets/canal-servidor.jar`
  (no .gitignore) num passo do `montar.yml` e do `conferir.yml` antes do Gradle. A central manda o .jar por `sync:`
  só quando muda (versão = sha-256), inicia com `exec:`, fala por `localabstract:painelasx-canal`
  (`PING`, `MIC fonte taxa` → PCM 16 kHz mono, `NET host porta` → túnel TCP). Cabo solto = o servidor sai sozinho.
- Fluxo: CaboUsb abre o acessório → `CanalDoCabo.plugou` (1,5 s depois, linha `canal-cabo`) → ADB (1ª vez: "Permitir
  depuração USB?" no celular + voz "Toque em Permitir…") → `settings put global adb_allowed_connection_time 0` (uma vez
  por celular: a autorização não expira em 7 dias) → servidor → proxy. PING de 20 em 20 s. Máx. 5 tentativas por
  plugada; sem "Permitir" em 2 min = parado até soltar o cabo (reabrir do AA no mesmo aparelho não pergunta de novo).
- Ouvido: `gravar` lê do `CanalDoCabo.microfone()` quando ligado (rótulo "OUVINDO (MIC CEL)"); erro no meio = segue
  com o microfone da central ("canal: o microfone do celular parou…"); celular mandando silêncio puro = central por 10 min.
- Internet: `ProxyDoApp` (ProxySelector só deste app) manda os HttpURLConnection pelo celular SÓ quando a central não
  tem rede com internet conferida (VALIDATED). Não é VPN (Spotify/rádio não passam). `Arquivo.baixar` recusa > 20 MB
  pelo celular (ouvido, voz esperam outra internet); o APK (~1 MB) passa.
- Conector: `canal: {estado, modelo, internet_pelo_celular, proxy_porta, ultimo_erro, microfone}`; diagnóstico
  "--- canal pelo cabo ---" (estado + linhas "canal:" do registro).
- O que o Leonardo faz UMA vez no celular: Configurações → Sobre o telefone → tocar 7× em "Versão do SO"; Configurações
  adicionais → Opções do desenvolvedor → "Depuração USB" ligada; na 1ª plugada no carro, marcar "Sempre permitir deste
  computador" e Permitir.
- Riscos: apps de banco podem reclamar das Opções do desenvolvedor ligadas (BMG/Caixa Tem documentam); HyperOS NÃO
  confirmado (se dá o 2d01 com o AA, se deixa o shell gravar o microfone com a tela bloqueada, se aceita o `settings
  put`, se desliga a depuração depois de atualizar); o Android mostra o ponto verde "Shell usando o microfone".
- Testes JVM (fora do repo): `scratchpad/canal-integra/teste/rodar.sh` (vetores + adb de verdade + celular falso +
  cabo USB falso + CanalCabo; 16 + 7 + 20 + 32).

## Revisão da noite 03/11 (ramo `rascunho-noite`)
- Quedas do AA de 02/11 (18:20:32, 18:20:41, 18:24:38, 18:25:29): "quadro aberto depois do TLS ignorado (canal 136/88/19)"
  e logo "Unable to parse TLS packet header", sempre com "leituras com erro na hora". Causa provável: no usbfs da central,
  bulkTransfer de LEITURA que volta com erro/prazo JOGA FORA os bytes já recebidos naquela transferência → fluxo
  desalinhado → TLS morto (o "SSL_write" de 01/11 17:14 é o mesmo desalinho visto pela escrita). O teste JVM [11]
  reproduz o registro do carro perdendo 4/7/1000 bytes.
- `LeitorUsb.kt`: a leitura do AA usa UM `UsbRequest` na fila (como o aasdk/OpenAuto com libusb assíncrono): o prazo não
  cancela nada e, com erro, os bytes que chegaram são entregues. `FilaUsb` por conexão (em `ConexoesUsb`): trava, pedidos
  "colhidos" por outra leitura (conversa velha e nova no mesmo cabo) e órfãos (pedido que pode estar no kernel NUNCA é
  `close()` antes de a conexão fechar: o kernel escreve nele ao colher). Sem UsbRequest: bulkTransfer de antes. O canal
  ADB continua síncrono (1 pacote por leitura). O resumo da conversa traz "problemas: erro aos X s/Y KB".
  Testes: `scratchpad/noite/usb/rodar.sh` (16, cabo falso que imita o usbfs).
- Registro novo "aa: quadro que o TLS recusou: canal, flags, tamanho, registro começa com [..]" (17 03 03 = certo).
  Cabeçalho impossível (flags > 0x0f ou canal > 15) = fluxo desalinhado: encerra na hora ("cabeçalho impossível").
  `Remontador` reaproveita o buffer de cada canal (até 512 KB). Testes do receptor: `scratchpad/noite/aa/rodar.sh`
  (226 + 5; o [11] perde 1 a 9000 bytes no meio do fluxo).
- `CaboUsb.caboTerminou`: queda depois do TLS com o cabo plugado → reinicia o USB do celular SEMPRE (antes só ≥ 20 s;
  reabrir o mesmo acessório lia a conversa velha). Quedas < 20 s seguidas: no máximo 8 por plugada, depois para até soltar.
  Plano B (`planoB`): 4 s depois do reinício, se o mesmo aparelho continua no cabo e ninguém fala com ele, pede o modo AA
  de novo (02/11 18:20:44 o reinício não fez o celular sair e o AA ficou 70 s parado). Vale também na partida.
- Canal ADB: `settings put` recusado pelo HyperOS (SecurityException) fica anotado (`adb/configurado-negado`) e só é
  tentado de novo depois de 7 dias. "Ninguém tocou em Permitir" vale para a PLUGADA física (o `Plugada` do CaboUsb vai
  como `episodio`): o celular que muda de nome quando o AA reinicia o USB não pergunta de novo; a voz "Toque em Permitir"
  sai uma vez por plugada. Testes [18] e [19] em `scratchpad/noite/canal/` (cópia ampliada do teste do canal).
- O que NÃO é do nosso código: o celular trocar de modo USB ("cabo solto" → 2717:ff08/ff88 → 2d01) 1–3 s depois de
  "canal: pronto" em 18:22:09 e 18:22:21 (logo depois de ligar a Depuração USB às 18:21:53). O canal só fala ADB
  (CNXN/AUTH, `exec:`, `sync:`, `localabstract:`) e o `settings put` foi recusado (não mudou nada); nada mexe na
  configuração USB do celular. Provável HyperOS reaplicando o USB depois de ligar a depuração / do "Permitir" sem "Sempre".
- Jarvis: `Corretor.NOME_JARVIS`/`SAUDACAO` (uma regra para Corretor e Comandos: "Hey, Jervos", "Olá Jarvis", "Jérvis";
  "Gabi me ligou" não vira Jarvis). Ajuda falada: "ajuda", "quais são as opções/comandos", "o que você sabe fazer".
  "Previsão" (com erros do Whisper, "…vosão do tempo") é o clima ANTES da música ("coloca pra vosão do tempo" ia para o
  Spotify); anotações/pedidos/lembretes com "tempo"/"previsão" não viram clima; "toca O Tempo Não Para" segue música.
- `TelaAndroidAuto.Decodificador`: erros seguidos → `codec.reset()` + configuração + imagem inteira (até 3 vezes; depois
  solta o vídeo); quadro perdido (fila cheia / 200 ms sem vaga) → espera SPS/IDR por até 3 s (sem borrão).
  `onNewIntent` liga o decodificador a uma conversa nova com a tela já aberta.
- `Turbo.limpar`: uma limpeza por vez.
- Ideia não feita (só com teste no carro): a central é `musb-hdrc`; se as quedas continuarem SÓ com o canal ADB ligado,
  suspeitar das leituras ADB com prazo de 1 s (cancelamento de URB no MUSB) e passar o ADB também para pedido assíncrono
  (precisa de uma linha única que colhe os pedidos das duas interfaces).

## v36: lê tudo do carro (só leitura)
- **TUDO em v36 é SOMENTE LEITURA.** Nada escreve no MCU/carro. `DadosCarro.kt` (novo) só escuta avisos, lê ajustes
  (Settings) e registra os callbacks de LEITURA do CanService. Cada dado vai para o Registro com o prefixo **"carro:"**
  (o Claude lê por `registro_central` filtro "carro:") e o último valor de cada sinal entra na seção de diagnóstico
  **"--- dados do carro (v36) ---"** (`diagnostico_central`). Nenhum comando/tecla é enviado; nenhum ajuste é mudado.
- Ligado de dentro do **Ouvinte** (NotificationListenerService — sobrevive ao force-stop da central) e também na partida
  (`ServicoFixo`, linha `inicio`). `DadosCarro.iniciar` é idempotente, roda numa `HandlerThread` própria, try/catch em tudo.
- **O que o coletor capta (e como ler pelo conector):**
  - *Avisos (broadcast), `registerReceiver` explícito + sticky:* tensão da bateria (`ACTION_CAN_BATTERY_VOLTAGE`, Bundle
    inteiro); faróis/ill (`ACTION_ILL_STATE_CHANGE`, `ACTION_BACKLIGHT_SWITCH_CHANGE`); **teclas do volante**
    (`ACTION_KEY_VALUE`, `ACTION_TEST_KEY` — só o CÓDIGO, nunca reenviado); portas (`ACTION_CAN_DOOR_STATE_CHANGE`,
    `ACTION_CAN_DOOR_WINDOW`); radar (`ACTION_CAN_RADAR_STATE_CHANGE`, `ACTION_BACK_SHOW_RADAR`); mídia
    (`ACTION_STREAM_MUSIC`, `MEDIA_LIST_CHANGE`); **ligação BT** (`ACTION_BT_BEGIN_CALL_ONLINE/_INCOMING/_END/_OUTGOING`
    — SÓ o estado, NUNCA número/contato); USB/SD (`ACTION_TIPS_INSERT_USB/_SD/_DISK/_CONNECT_IPOD`); energia (`ACTION_MCU_STATE_CHANGE`).
  - *Ajustes (`Settings.System`) por `query` + `ContentObserver` (notifyForDescendants):* `mcu_backcar_state`,
    `mcu_system_volume`, `key_top_app`, `mcu_current_source`, `mcu_state`, e varre o `CONTENT_URI` filtrando
    nwd/mcu/acc/volt/batt/back/vol/white/source/top (descobre os nomes reais). Só leitura.
  - *CanService:* UM bind só-leitura (`com.nwd.can.setting`/`CanService`, ação `...ACTION_CAN_SERVICE`, AIDL
    `ICanRemote4OuterFeature`, canal `nwdthirdapp` + segredo, `initSdkCfg` tx 2, callbacks de LEITURA 9/11/15/17/23/27).
    Decodifica carInfo (vel, rpm, combustível, tensão, marcha, freio de mão, cinto, pisca, faróis, portas, coolant),
    ar, portas, radar. Se o serviço não existir/bind recusado -> "carro: CAN não disponível"; se ligar mas não vier dado
    em 20 s -> "carro: CAN vazio — provável sem caixa de CAN no ASX". Nunca derruba o app.
  - `LeitorCarro.PALAVRAS_SINAL` ampliada: + bms, volt, batt, sensor, native.reverse, source, top_app, can (pega mais
    props no getprop sem receiver novo).
- **Roteiro de 1 passeio (o Leonardo faz, depois o Claude lê por `registro_central` "carro:" + `diagnostico_central`):**
  ligar a chave (ACC), dar a partida, apertar CADA botão do volante (um a um), girar o botão de volume, engatar a ré
  parado, acender e apagar o farol, play/pause na música, abrir o Spotify e a câmera de ré, desligar. Isso preenche de
  uma vez os códigos de tecla do volante, os nomes reais das chaves de Settings, e confirma quais avisos/CAN chegam.
- Correções de bug seguras juntas nesta versão: `Turbo.limpar` por voz/Claude agora roda em segundo plano (não trava a
  tela) e fala o resultado; `CaboUsb.hora` (SimpleDateFormat) virou `ThreadLocal` (thread-safe); o vigia do Controle
  (`ServicoFixo.olharControle`) agora É iniciado, mas o aviso "Controle desligado → Religar" só aparece depois de uma
  ATUALIZAÇÃO (pref `config/controle_versao` = VERSION_CODE da última vez com o Controle ligado; soneca/partida com a
  mesma versão fica calada, como manda o CLAUDE.md).

## v37 (03/11): canal do microfone DESLIGADO por padrão
- Teste no carro 03/11 15:45–15:57 (v36, Depuração USB ligada): o Android Auto caía a cada 10–40 s, SEMPRE enquanto o
  canal ADB conectava/esperava o "Permitir"/reiniciava ("canal: servidor do celular sem resposta; reiniciando" e 3 s
  depois "aa: cabeçalho impossível… bytes perdidos no cabo"; também "cabo solto" com "espera falhou" no meio).
  Ontem (sem canal) o AA aguentou a viagem. Confirma a suspeita da revisão da noite (ADB síncrono com prazo de 1 s na
  MESMA conexão do AA no controlador musb-hdrc).
- `CanalDoCabo.plugou` só faz algo com a pref `config/canal_ligado = true` (padrão false). Sem ela, nada fala ADB: o
  celular não pergunta "Permitir depuração USB" e o Jarvis usa o microfone da central.
- Antes de religar: refazer o canal com leitura ADB assíncrona (UsbRequest, uma linha única que colhe os pedidos das
  duas interfaces) e testar no carro com o AA rodando por vários minutos.
- No teste o CanService MANDOU carInfo com o carro andando (vel=5/4/0) e o resto inválido (255/-1): algo fala CAN
  (velocidade), sem combustível/portas/RPM. Investigar depois.
- Problema do dia (não é código): o Redmi chegou "travado" no modo acessório (2d01 no boot) e ficou caindo e voltando a
  cada 1–3 s em qualquer modo USB; reiniciar o CELULAR resolveu.
