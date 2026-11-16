# Relatório — servidor com vários aparelhos (carro + Redmi)

Branch: `rascunho-redmi` (rascunho, **não** vai para o carro). Nada foi publicado.

## 1. O que eu mudei e por quê (resumo)

- Antes, o servidor tinha **uma vaga só**: toda requisição caía no mesmo cofre (`'asx'`),
  que é o carro. Um segundo aparelho não conseguia entrar (dava o erro 403 "já registada
  por outro aparelho").
- Agora o servidor tem **várias vagas**. Cada aparelho tem o seu próprio cofre (estado,
  registro, fila de comandos, backup e link do conector, tudo separado).
- A vaga é escolhida pelo **nome do aparelho** no endereço:
  - **Sem nada na frente** → aparelho `asx` = **o carro** (igual a antes).
  - `/d/redmi/...` → aparelho `redmi` = **o Redmi** (cofre separado).
- Mudei **só o começo** do servidor (a parte que escolhe a vaga). Todo o resto do código
  (registro, backup, Jarvis, ferramentas do Claude) ficou **intacto**.
- Foi mexido em **1 arquivo**: `servidor/src/index.js`. Diferença pequena e comentada em
  português.

## 2. Por que o carro (`asx`) NÃO corre risco

- O carro manda os endereços **sem** o prefixo `/d/...` (ele usa `/mcp/<token>` e `/app/*`,
  como sempre). Nesses casos o servidor escolhe **exatamente** a vaga `asx` de antes e
  **nem toca** na requisição — ela chega no cofre do carro igualzinha, byte por byte.
- O cofre do carro continua se chamando `asx`, com **todo o histórico, backup e registro**
  no lugar. Não há troca de nome de cofre, então o carro **não re-registra do zero** e
  **não perde nada**.
- Testei a lógica de roteamento com os endereços reais do carro (`/mcp/...`, `/app/relatorio`,
  `/app/registrar`, `/`): todos continuam indo para `asx`, sem alteração do caminho.
- O servidor continua sendo um JavaScript válido (conferido com `node --check`).
- Como é um ramo de rascunho, **nada foi para o ar**: o workflow `conferir.yml` só compila.
  O `conector.yml` (que publica de verdade) **não** foi disparado.

## 3. O que falta para ligar o Redmi ao Claude

### (a) Publicar o servidor novo
O servidor novo precisa ir para o ar na Cloudflare. Hoje ele só está no ramo de rascunho.
Para publicar: **revisar e juntar (merge) o `rascunho-redmi` no `main`**. Quando mudança
em `servidor/**` entra no `main`, o workflow `conector.yml` publica sozinho. (Enquanto não
publicar, o carro segue com o servidor atual, sem risco.)

### (b) Link do conector do Redmi e onde colar no Claude
- O link do conector do Redmi tem este formato:

  `https://<endereço-do-servidor>/d/redmi/mcp/<token-do-redmi>`

  Repare na diferença: o do carro é `.../mcp/<token>`; o do Redmi tem **`/d/redmi/`** no meio.
- O **token do Redmi** é gerado dentro do app, no próprio Redmi (cada aparelho gera o seu,
  aleatório). Você pega esse link tocando em **"Copiar link do conector"** no app do Redmi —
  **mas** isso só vai sair com o `/d/redmi/` no meio depois da mudança do item (c).
- Onde colar: nas **Configurações do Claude → Conectores → Adicionar conector**, na sua conta
  Claude (a mesma onde está o conector do carro). O do carro continua lá, intacto; o do Redmi
  entra como um conector **novo e separado**.

### (c) Mudança necessária no app do Redmi
O Redmi roda o **mesmo app** do carro, e esse app hoje manda tudo **sem** o prefixo `/d/...`
— ou seja, ele cairia na vaga do carro. Para o Redmi virar um aparelho separado, o app
precisa de **um ajuste pequeno** (que eu **não** fiz, para manter o foco no servidor e não
arriscar o app do carro):

- Criar um ajuste "nome do aparelho" no app, com **padrão `asx`**. Assim, qualquer celular/
  central que não mexer nisso continua sendo o **carro** (nada muda para o carro).
- No Redmi, você escolheria o nome **`redmi`** uma vez.
- Com o nome diferente de `asx`, o app passa a colocar **`/d/redmi`** na frente de todos os
  endereços (tanto o `/app/*` quanto o link `/mcp/<token>` do conector).

Onde isso entra no código do app (para quando formos fazer):
- `app/.../Conector.kt`, função `pedir(...)`: hoje monta `BuildConfig.SERVIDOR + caminho`.
  Passaria a ser `BuildConfig.SERVIDOR + base + caminho`, onde `base` é `""` para `asx` e
  `"/d/redmi"` para o Redmi.
- `app/.../Conector.kt`, `linkDoClaude(...)` e `recuperar(...)`: o mesmo `base` entra antes de
  `"/mcp/"`.
- Um lugar na tela (MainActivity) para você digitar/escolher o nome do aparelho no Redmi.

Esse ajuste do app é **seguro para o carro**: como o padrão é `asx`, o app do carro
continua mandando os endereços exatamente como hoje.

## 4. Decisões e dúvidas para você (Leonardo)

1. **Confirmar o nome do Redmi.** Usei `redmi` como exemplo. Pode ser outro nome curto (só
   letras/números/hífen), por ex. `celular` ou `redmi13`. Me diga qual você quer e eu já
   deixo o app com esse nome.
2. **Quer que eu faça o ajuste do app agora** (item c), no mesmo ramo de rascunho, para já
   sair o link certo do Redmi? Eu não fiz ainda porque você pediu para focar no servidor e
   não arriscar o app do carro. É um ajuste pequeno e o padrão continua sendo o carro.
3. **Publicar quando?** Enquanto o `rascunho-redmi` não for juntado no `main`, o servidor no
   ar continua o antigo (uma vaga). O carro não sente nada agora. A hora de publicar é quando
   você quiser ligar o Redmi — aí revisamos e fazemos o merge.

> Observação técnica (não precisa fazer nada): o texto de apresentação que o servidor mostra
> ao Claude ainda fala "central do carro ASX". Para o Redmi, isso é só cosmético (ele é um
> celular, não o carro). Dá para ajustar depois, se quiser que o Claude saiba que aquele
> conector é o Redmi e não o carro.
