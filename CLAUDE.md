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
assina com a chave do segredo `CHAVE_BASE64` e cria a release `vN`. A central atualiza sozinha (de 30 em 30
minutos, quando acorda, quando o Controle liga, ou no botão "Procurar atualização"). Com o Controle ligado, ela
mesma confirma a instalação; se o app estava aberto, reabre sozinho.

## Conector do Claude (`servidor/`)

- Worker `painel-asx` na Cloudflare do Leonardo (segredos `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`),
  publicado pelo mesmo workflow antes do APK. O endereço entra no app como `BuildConfig.SERVIDOR`.
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

## Limites que já foram explicados ao Leonardo

- O app só vê do carro o que a central recebe (câmera de ré, botões do volante, etc.). O ar-condicionado do ASX é mecânico.
- Nada de mexer na parte elétrica do carro.
- Mensagens/ligações por voz: pelo Android Auto (ZLink) com "Ok Google", não por este app.
