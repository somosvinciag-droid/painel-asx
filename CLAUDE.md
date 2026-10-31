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

### O que só o teste no carro responde
- Se o ZLink pega o cabo antes (caso A/B; ver o diagnóstico "--- cabo USB ---") e se o "usar por padrão" segura.
- Se o AA do Redmi aceita o certificado de referência e o tipo "moto" sem perguntar nada (1ª vez: aceitar o carro novo
  no celular desbloqueado).
- Se o celular PARA de mandar vídeo com o foco nativo (o registro conta "vídeo recebido e descartado") e se o AA aceita
  ficar sempre em nativo sem desistir.
- Volume/latência do som pelo `AudioTrack` nesta central; convivência do foco de áudio com o Jarvis e o ouvido.
- Se o volante (MediaSession) chega ao celular e se o Google Maps manda a navegação nova (0x8006/0x8007) ou a antiga.
- Se o "Ok Google" do celular funciona com a central dizendo "moto".

## Mapa próprio (rascunho `rascunho-mapa`)

Mapa e rotas NOSSOS (OpenStreetMap), sem Google e sem internet na central. Decisão e fontes em
`scratchpad/mapa/decisao.md` (fora do repo). Desenho aprovado: prancheta "Mapa do Painel ASX" (3D blocos brancos / 2D).
- **Sem biblioteca**: desenho próprio em OpenGL ES 2.0 (`MapaDesenho`), roteador A* próprio (`Roteador` em `MapaRota.kt`),
  frases próprias (`MapaFrases`), guia (`MapaGuia`) e busca (`MapaBusca`). MapLibre foi descartado (+3–4 MB por ABI,
  AndroidX/OkHttp, exige GLES 3; o app tem ~1 MB). GraphHopper largou o Android; Valhalla/Ferrostar/BRouter pesados ou outro app.
- **Pacote da região** `regiao.bin` (um arquivo, memória mapeada, little-endian, formato v1 descrito em
  `ferramentas/mapa/gerar_mapa.py` e lido por `MapaDados`): nós/arestas (mão de direção), vias (classe, velocidade,
  terra, rotatória, nome), pontos, grade de 500 m, prédios (telhado já triangulado, altura do OSM ou 4,5–8 m),
  nomes, lugares (cidade/bairro) e índice de ruas por cidade. Coordenadas em decímetros numa projeção plana.
- **Workflow `mapa.yml`**: SÓ quando o próprio arquivo muda (ou à mão). Geofabrik `sudeste` → `osmium extract`
  (caixa `CAIXA`, hoje Sul de Minas lon −47..−44, lat −23..−21) → `gerar_mapa.py` → release FIXA **`mapa-1`**
  (`--prerelease --latest=false`, NUNCA latest) com `mapa.json` (versão, tamanho, sha-256, contagens, prédios de Caxambu)
  e `regiao.bin.gz`. Para gerar de novo: subir `VERSAO_MAPA` (a central vê a versão nova no `mapa.json` 1×/dia).
- **Download** (`MapaPacote`): uma vez, sozinho, 4 min depois do início (nunca na partida), quando a internet volta
  (conector/despertador), ao abrir o mapa ou pedir rota. `Arquivo.baixar` (continua de onde parou) → gunzip com sha-256 →
  troca atômica → `files/mapa/ok`. Estado no conector: campo `mapa`.
- **Rotas** (`Rotas.navegar` → `Navegacao`): padrão = mapa do Painel (`Config.rotasNoPainel`, prefs `rotas_painel`).
  Destino: favorito com coordenadas → "lat,lon" → busca offline → Nominatim (se houver internet). Sem pacote, destino
  fora da região ou sem caminho → Waze/Maps como antes (`Rotas.navegarFora`, que também liga o aviso de chegada do `Carro`).
  Comandos: "rota para…", "vamos pra casa", "quanto falta", "cancela a rota", "abre o mapa", "mapa 2D/3D",
  "rotas no Waze" / "rotas no Painel". Na `MainActivity`: cartão "Mapa do Painel" (abrir + interruptor).
- **Guia**: GPS do `Carro.posicao` → `Navegacao.posicao` (continua falando com a tela fechada). Avisos com a voz do
  Jarvis (`Voz.Tipo.IMPORTANTE`): longe (1–2 km só na estrada), perto (~14 s antes) e agora (~5 s), "e depois…",
  "Siga na X por N km", rotatória com a n-ésima saída, paradas, chegada (encerra a rota 45 s depois).
  Saiu da rota (>35–80 m conforme a precisão, 3 leituras seguidas, ou contramão) → "Recalculando." (máx. 1 a cada 8 s).
- **Tela** `TelaMapa` (mesma tarefa da TelaCarro; só abre quando pedida). 30 fps só enquanto mexe (senão ~1 quadro/s),
  células de 500 m montadas na linha "mapa-carga" (máx. 3 sobem por quadro), névoa no limite carregado, rótulos das ruas
  em Canvas por cima. Ao sair (`onStop`) solta a placa de vídeo, as células e (sem rota) o pacote. Registro anota
  "mapa: GPU …" (GL_RENDERER) na primeira abertura. Não mexe na TelaCarro, na saudação nem na voz.
- **Testes JVM** (fora do repo): `scratchpad/mapa/teste/rodar.sh` — cidade falsa em OSM XML (grade, mão única,
  rotatória de 4 braços, estrada só com `ref`, rua de terra, rua privada, ilha, prédio em L) → gerador → 74 testes:
  pacote, frases, curvas, mão única, rotatória 1ª/2ª/3ª saída, guia falando até chegar, saiu da rota, recalcular,
  paradas, busca; e uma grade de 62 500 cruzamentos para medir o A*.
- **Pacote v1 (31/10)**: 35,8 MB para baixar, 75,2 MB instalado; 327 mil cruzamentos, 432 mil vias, 250 mil prédios,
  3 860 lugares, 63 mil ruas na busca. **Caxambu quase não tem prédios no OSM** (17 na área urbana, 28 na caixa do
  município): lá o 3D fica só com as ruas. Teste com o pacote real (`scratchpad/mapa/teste/TesteReal.kt`, fora do repo):
  Caxambu → Poços 237,7 km/3 h 27 (A* 95 mil nós, ~0,2 s no PC), → Pouso Alegre 135,7 km/1 h 56, sem "saiu da rota"
  falso andando a 60 km/h. Menos falação: "siga em frente" só ao entrar em rodovia, nada de "mantenha-se"/"vire
  levemente" quando a mesma rua só segue, manobras a < 25 m juntadas (Poços: 29 manobras, ~70 falas).
- **Dados © colaboradores do OpenStreetMap (ODbL)**: a release cita a fonte; manter a atribuição se houver tela "sobre".
