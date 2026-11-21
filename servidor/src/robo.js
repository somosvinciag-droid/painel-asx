// Painel ASX — parte do "Painel Redmi" (celular robô: Redmi Note 13 Pro+ antigo do Leonardo).
//
// O Redmi usa a VAGA própria /d/redmi/... (outro Durable Object, cofre separado do carro).
// Lá dentro, as ferramentas do Claude são estas (não as do carro). Cada ferramenta vira um
// comando na fila (tipo + texto JSON) que o app "Painel Redmi" busca em /app/comandos, executa
// pela Acessibilidade e devolve em /app/resultado — o mesmo caminho da central do carro.
//
// Arquivos (imagem/vídeo para a galeria): o Claude manda em pedaços (base64) com mandar_arquivo_redmi;
// os pedaços ficam no storage (arq:<id>:<n>) até o celular baixar por /app/arquivo e são apagados depois.
// Ou manda só uma URL pública e o celular baixa direto.

const PEDACO_MAX = 1400000; // caracteres base64 por pedaço (o storage SQLite aceita até 2 MB por valor)
const ARQ_MAX_PARTES = 200; // ~200 MB no máximo

export class ParteDoRobo {
  constructor(central) {
    this.c = central;
  }

  get s() { return this.c.ctx.storage; }

  // GET /app/arquivo?id=..&n=..  (o celular já foi autorizado pela chave dele)
  async rotaArquivo(request, json) {
    const u = new URL(request.url);
    const id = String(u.searchParams.get('id') || '');
    const n = Number(u.searchParams.get('n'));
    if (!/^[a-z0-9-]{6,40}$/.test(id) || !(n >= 0)) return json({ erro: 'pedido ruim' }, 400);
    const parte = await this.s.get(`arq:${id}:${n}`);
    if (typeof parte !== 'string') return json({ erro: 'pedaço não existe' }, 404);
    return json({ id, n, base64: parte });
  }

  async apagarArquivo(id, total) {
    const chaves = [];
    for (let i = 0; i < total; i++) chaves.push(`arq:${id}:${i}`);
    for (let i = 0; i < chaves.length; i += 100) await this.s.delete(chaves.slice(i, i + 100));
    await this.s.delete(`arqmeta:${id}`);
  }

  async ferramenta(nome, a) {
    const cmd = (tipo, args, esperaMs) => this.c.enviarComando(tipo, JSON.stringify(args || {}), esperaMs);
    if (nome === 'estado_redmi') {
      const [visto, estado, registrado] = [await this.s.get('visto'), await this.s.get('estado'), await this.s.get('registrado')];
      const online = !!visto && Date.now() - visto < 75000;
      return { online, ultimo_contato: this.c.hora(visto), registrado_em: this.c.hora(registrado), estado: estado || null };
    }
    if (nome === 'registro_redmi') {
      const reg = (await this.s.get('registro')) || [];
      const n = Math.max(1, Math.min(Number(a.linhas) || 80, 600));
      let linhas = reg;
      if (a.filtro) { const f = String(a.filtro).toLowerCase(); linhas = reg.filter(l => l.toLowerCase().includes(f)); }
      return { total: reg.length, linhas: linhas.slice(-n) };
    }
    if (nome === 'tela_redmi') return cmd('tela', a);
    if (nome === 'print_redmi') {
      const r = await cmd('print', a, 30000);
      if (r.erro) return r;
      try {
        const o = JSON.parse(r.resposta);
        if (o.imagem) return { imagem: o.imagem, legenda: `Tela do Redmi (${o.largura}x${o.altura}; toques usam a tela real ${o.tela_largura}x${o.tela_altura}, escala ${o.escala}).` };
        return { erro: o.erro || 'sem imagem' };
      } catch (e) { return { erro: 'resposta estranha do celular' }; }
    }
    if (nome === 'tocar_redmi') return cmd('tocar', a);
    if (nome === 'deslizar_redmi') return cmd('deslizar', a);
    if (nome === 'digitar_redmi') return cmd('digitar', a);
    if (nome === 'tecla_redmi') return cmd('tecla', a);
    if (nome === 'abrir_app_redmi') return cmd('abrir', a);
    if (nome === 'apps_redmi') return cmd('apps', a);
    if (nome === 'passos_redmi') {
      if (!Array.isArray(a.passos) || !a.passos.length) return { erro: 'Falta a lista "passos".' };
      return cmd('lote', { passos: a.passos.slice(0, 30) }, 60000);
    }
    if (nome === 'mandar_arquivo_redmi') return this.mandarArquivo(a, cmd);
    return { erro: 'Ferramenta desconhecida: ' + nome };
  }

  async mandarArquivo(a, cmd) {
    const nomeArq = String(a.nome || '').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);
    if (!nomeArq) return { erro: 'Falta o "nome" do arquivo (ex.: capa.jpg, teste.mp4).' };
    if (a.url) {
      const url = String(a.url);
      if (!/^https:\/\//.test(url)) return { erro: 'A url precisa começar com https://' };
      return cmd('arquivo', { nome: nomeArq, url, pasta: a.pasta || '' }, 90000);
    }
    // Em pedaços: id (o mesmo em todos), parte (0..total-1), total, base64.
    const id = String(a.id || '');
    const parte = Number(a.parte);
    const total = Number(a.total);
    if (!/^[a-z0-9-]{6,40}$/.test(id)) return { erro: 'Falta "id" (6 a 40 letras minúsculas/números/hífen), o mesmo em todos os pedaços.' };
    if (!(total >= 1 && total <= ARQ_MAX_PARTES) || !(parte >= 0 && parte < total)) return { erro: 'parte/total inválidos' };
    const b64 = String(a.base64 || '').replace(/\s+/g, '');
    if (!b64 || b64.length > PEDACO_MAX) return { erro: `Cada pedaço precisa ter de 1 a ${PEDACO_MAX} caracteres de base64.` };
    await this.s.put(`arq:${id}:${parte}`, b64);
    const meta = (await this.s.get(`arqmeta:${id}`)) || { total, recebidas: [], criado: Date.now() };
    if (!meta.recebidas.includes(parte)) meta.recebidas.push(parte);
    await this.s.put(`arqmeta:${id}`, meta);
    if (meta.recebidas.length < total) return { ok: true, recebido: meta.recebidas.length, de: total };
    const r = await cmd('arquivo', { nome: nomeArq, id, total, pasta: a.pasta || '' }, 90000);
    if (!r.erro) await this.apagarArquivo(id, total);
    return r;
  }
}

export const ROBO_INSTRUCOES =
  'Este conector é o PAINEL REDMI: o celular robô (Redmi Note 13 Pro+ antigo) do Leonardo, com o app "Painel Redmi" ' +
  '(Acessibilidade). Serve para o Claude usar apps do celular (principalmente o Instagram) como um dedo: ' +
  'tela_redmi lê a árvore da tela (textos, botões, posições), print_redmi tira foto da tela, tocar_redmi toca por texto/id/descrição ' +
  'ou por x,y (pixels da tela real), deslizar_redmi rola, digitar_redmi escreve no campo com foco, tecla_redmi (voltar, inicio, ' +
  'recentes, notificacoes, enter, acordar), abrir_app_redmi abre um app (pacote, nome ou url), mandar_arquivo_redmi põe imagem/vídeo ' +
  'na galeria, passos_redmi faz vários passos de uma vez (cada passo = {tipo: tocar|deslizar|digitar|tecla|abrir|esperar|tela, ...}). ' +
  'REGRAS: nunca publique, compartilhe, apague nem mude nada da conta (senha, e-mail, telefone) sem o Leonardo confirmar na conversa; ' +
  'nunca digite senha nem código de verificação. Antes de tocar em "Compartilhar", "Publicar", "Salvar" ou "Concluir", pare e pergunte. ' +
  'Fale com o Leonardo em português do Brasil, simples. Se o Redmi estiver offline, diga isso.';

const sem = { type: 'object', properties: {}, additionalProperties: true };
export const ROBO_FERRAMENTAS = [
  { name: 'estado_redmi', description: 'Se o Redmi está online, bateria, tela ligada, Acessibilidade ligada, app aberto agora, último arquivo recebido.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'registro_redmi', description: 'Últimas linhas do registro do app Painel Redmi (erros: filtro "ERRO").', inputSchema: { type: 'object', properties: { linhas: { type: 'number' }, filtro: { type: 'string' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'tela_redmi', description: 'Lê a tela do Redmi agora: app aberto e a lista de elementos (texto, descrição, id, se dá para tocar/escrever/rolar, posição [x1,y1,x2,y2] e centro). todos=true traz também os elementos sem texto.', inputSchema: { type: 'object', properties: { todos: { type: 'boolean' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'print_redmi', description: 'Foto da tela do Redmi (JPEG). largura padrão 540.', inputSchema: { type: 'object', properties: { largura: { type: 'number' }, qualidade: { type: 'number' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'tocar_redmi', description: 'Toca na tela do Redmi: por texto (texto), descrição (desc), id (id, ex. com.instagram.android:id/...), ou coordenadas (x, y em pixels da tela real). exato=true só aceita texto igual; indice escolhe entre vários iguais (0 = primeiro); longo=true = toque longo.', inputSchema: { type: 'object', properties: { texto: { type: 'string' }, desc: { type: 'string' }, id: { type: 'string' }, x: { type: 'number' }, y: { type: 'number' }, longo: { type: 'boolean' }, exato: { type: 'boolean' }, indice: { type: 'number' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'deslizar_redmi', description: 'Desliza o dedo de (x1,y1) até (x2,y2) em ms milissegundos (padrão 400). Ou direcao: cima|baixo|esquerda|direita (rola a tela).', inputSchema: { type: 'object', properties: { x1: { type: 'number' }, y1: { type: 'number' }, x2: { type: 'number' }, y2: { type: 'number' }, ms: { type: 'number' }, direcao: { type: 'string' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'digitar_redmi', description: 'Escreve o texto no campo com foco (ou no campo com este texto/id em "campo"). limpar=true apaga o que tinha; enter=true aperta o enter/enviar do teclado. Nunca use para senha ou código.', inputSchema: { type: 'object', properties: { texto: { type: 'string' }, campo: { type: 'string' }, limpar: { type: 'boolean' }, enter: { type: 'boolean' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'tecla_redmi', description: 'Aperta: voltar, inicio, recentes, notificacoes, enter, acordar (liga a tela e tira o bloqueio sem senha), print_sistema.', inputSchema: { type: 'object', properties: { tecla: { type: 'string' } }, required: ['tecla'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'abrir_app_redmi', description: 'Abre um app no Redmi: pacote (ex. com.instagram.android), nome (ex. Instagram) ou url (ex. https://instagram.com/... abre no app).', inputSchema: { type: 'object', properties: { pacote: { type: 'string' }, nome: { type: 'string' }, url: { type: 'string' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'apps_redmi', description: 'Lista os apps instalados no Redmi (nome e pacote).', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'passos_redmi', description: 'Vários passos seguidos (até 30): passos = [{tipo:"tocar", texto:"..."}, {tipo:"esperar", ms:1500}, {tipo:"deslizar", direcao:"cima"}, {tipo:"digitar", texto:"..."}, {tipo:"tecla", tecla:"voltar"}, {tipo:"abrir", pacote:"..."}, {tipo:"tela"}]. Para no primeiro erro.', inputSchema: { type: 'object', properties: { passos: { type: 'array', items: { type: 'object' } } }, required: ['passos'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'mandar_arquivo_redmi', description: 'Põe uma imagem ou vídeo na galeria do Redmi (pasta Pictures/Painel ou Movies/Painel; pasta="DCIM/Camera" para outra). Com url (https) o celular baixa direto. Sem url: mande em pedaços de até 1.400.000 caracteres de base64 com o mesmo id, parte (0,1,2...) e total; no último o celular salva.', inputSchema: { type: 'object', properties: { nome: { type: 'string' }, url: { type: 'string' }, id: { type: 'string' }, parte: { type: 'number' }, total: { type: 'number' }, base64: { type: 'string' }, pasta: { type: 'string' } }, required: ['nome'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } }
];
