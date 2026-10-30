// Painel ASX — conector entre o Claude do Leonardo e a central do carro.
//
// Duas pontas:
//  - /app/*        a central (app Painel ASX) manda o estado e os erros e busca comandos.
//  - /mcp/<token>  o Claude (claude.ai) usa as ferramentas do conector.
// Tudo fica num Durable Object ("Central"): um so para o carro.
//
// Seguranca:
//  - A central se registra uma vez com uma chave aleatoria gerada no proprio aparelho.
//    So esse aparelho fala com /app/* depois (aqui fica apenas o resumo sha256 da chave).
//  - O link do conector tem um token secreto gerado pelo app; aqui fica so o resumo sha256.
//  - Comandos com mais de 90 s sao ignorados pela central (nada acontece "atrasado").

export default {
  async fetch(request, env) {
    const id = env.CENTRAL.idFromName('asx');
    return env.CENTRAL.get(id).fetch(request);
  }
};

const ONLINE_MS = 75 * 1000;     // sem contato ha mais disto = central offline
const ESPERA_APP_MS = 20 * 1000; // quanto a central fica esperando comando por pedido
const ESPERA_RES_MS = 25 * 1000; // quanto o Claude espera a central responder
const MAX_REGISTRO = 600;

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex' }
  });
}

async function sha256(t) {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(t));
  return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
}

function iguais(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let d = a.length ^ b.length;
  for (let i = 0; i < Math.min(a.length, b.length); i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function horaBR(ms) {
  if (!ms) return 'nunca';
  return new Date(ms).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' });
}

export class Central {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.esperandoComando = [];   // pedidos da central parados esperando comando
    this.esperandoResultado = {}; // id do comando -> funcao que entrega o resultado
  }

  async fetch(request) {
    const url = new URL(request.url);
    const p = url.pathname;
    try {
      if (p === '/' || p === '') return new Response('Painel ASX — conector da central do ASX.', { status: 200 });
      if (p === '/app/registrar') return await this.registrar(request);
      if (p.startsWith('/app/')) {
        if (!(await this.appAutorizado(request))) return json({ erro: 'nao autorizado' }, 403);
        if (p === '/app/relatorio') return await this.relatorio(request);
        if (p === '/app/comandos') return await this.comandos();
        if (p === '/app/resultado') return await this.resultado(request);
        return json({ erro: 'rota desconhecida' }, 404);
      }
      if (p.startsWith('/mcp/')) return await this.mcp(request, p.slice('/mcp/'.length).replace(/\/+$/, ''));
      return new Response('Não encontrado', { status: 404 });
    } catch (e) {
      return json({ erro: String((e && e.message) || e).slice(0, 300) }, 500);
    }
  }

  // ------------------------------------------------------------------ central

  async registrar(request) {
    const c = await request.json();
    const chave = String(c.chave || '');
    const mcp = String(c.mcp || '');
    if (chave.length < 32 || mcp.length < 24) return json({ erro: 'chave curta' }, 400);
    const h = await sha256(chave);
    const atual = await this.ctx.storage.get('appHash');
    if (atual && !iguais(atual, h)) {
      return json({ erro: 'Esta central do ASX já foi registada por outro aparelho.' }, 403);
    }
    await this.ctx.storage.put({ appHash: h, mcpHash: await sha256(mcp), registrado: Date.now() });
    return json({ ok: true });
  }

  async appAutorizado(request) {
    const a = request.headers.get('Authorization') || '';
    const chave = a.startsWith('Bearer ') ? a.slice(7) : '';
    if (chave.length < 32) return false;
    const atual = await this.ctx.storage.get('appHash');
    return !!atual && iguais(atual, await sha256(chave));
  }

  async relatorio(request) {
    const c = await request.json();
    const agora = Date.now();
    const gravar = { visto: agora };
    if (c.estado && typeof c.estado === 'object') gravar.estado = { ...c.estado, recebido: agora };
    if (typeof c.diagnostico === 'string' && c.diagnostico) {
      gravar.diagnostico = { texto: c.diagnostico.slice(0, 120000), em: agora };
    }
    if (Array.isArray(c.linhas) && c.linhas.length) {
      const reg = (await this.ctx.storage.get('registro')) || [];
      for (const l of c.linhas.slice(-300)) reg.push(String(l).slice(0, 2000));
      gravar.registro = reg.slice(-MAX_REGISTRO);
    }
    await this.ctx.storage.put(gravar);
    return json({ ok: true });
  }

  async comandos() {
    await this.ctx.storage.put('visto', Date.now());
    const fila = (await this.ctx.storage.get('fila')) || [];
    if (fila.length) {
      await this.ctx.storage.put('fila', []);
      return json({ comandos: fila });
    }
    const cmds = await new Promise(resolve => {
      const w = c => { clearTimeout(t); resolve(c); };
      const t = setTimeout(() => {
        this.esperandoComando = this.esperandoComando.filter(x => x !== w);
        resolve([]);
      }, ESPERA_APP_MS);
      this.esperandoComando.push(w);
    });
    return json({ comandos: cmds });
  }

  async resultado(request) {
    const c = await request.json();
    const id = String(c.id || '');
    if (!id) return json({ erro: 'sem id' }, 400);
    const r = { ok: c.ok !== false, resposta: String(c.resposta ?? '').slice(0, 100000), em: Date.now() };
    const entregar = this.esperandoResultado[id];
    if (entregar) {
      delete this.esperandoResultado[id];
      entregar(r);
    }
    return json({ ok: true });
  }

  async enviarComando(tipo, texto) {
    const visto = (await this.ctx.storage.get('visto')) || 0;
    if (Date.now() - visto > ONLINE_MS) {
      return { erro: `A central está offline (carro desligado, sem internet ou app fechado). Último contato: ${horaBR(visto)}.` };
    }
    const cmd = { id: crypto.randomUUID(), tipo, texto: texto || '', criado: Date.now() };
    const espera = new Promise(resolve => {
      const t = setTimeout(() => {
        delete this.esperandoResultado[cmd.id];
        resolve(null);
      }, ESPERA_RES_MS);
      this.esperandoResultado[cmd.id] = r => { clearTimeout(t); resolve(r); };
    });
    // Entrega na hora a quem estiver esperando (o pedido mais novo); senao, deixa na fila.
    if (this.esperandoComando.length) {
      const ws = this.esperandoComando;
      this.esperandoComando = [];
      ws.pop()([cmd]);
      ws.forEach(w => w([]));
    } else {
      const fila = (await this.ctx.storage.get('fila')) || [];
      fila.push(cmd);
      await this.ctx.storage.put('fila', fila.slice(-10));
    }
    const r = await espera;
    if (!r) return { erro: 'A central não respondeu a tempo (pode ter perdido a internet). Tente de novo.' };
    return r.ok ? { resposta: r.resposta } : { erro: r.resposta || 'falhou na central' };
  }

  // ------------------------------------------------------------------ Claude (MCP)

  async ferramenta(nome, a) {
    const s = this.ctx.storage;
    if (nome === 'estado_central') {
      const [visto, estado, registrado] = [await s.get('visto'), await s.get('estado'), await s.get('registrado')];
      return {
        online: !!visto && Date.now() - visto < ONLINE_MS,
        ultimo_contato: horaBR(visto),
        registrada_em: horaBR(registrado),
        estado: estado || null
      };
    }
    if (nome === 'registro_central') {
      const reg = (await s.get('registro')) || [];
      const n = Math.max(1, Math.min(Number(a.linhas) || 80, MAX_REGISTRO));
      let linhas = reg;
      if (a.filtro) {
        const f = String(a.filtro).toLowerCase();
        linhas = reg.filter(l => l.toLowerCase().includes(f));
      }
      return { total: reg.length, linhas: linhas.slice(-n) };
    }
    if (nome === 'diagnostico_central') {
      if (a.atualizar !== false) {
        const r = await this.enviarComando('diagnostico', '');
        if (!r.erro) return { atualizado: true, diagnostico: r.resposta };
        const d = await s.get('diagnostico');
        return { atualizado: false, aviso: r.erro, diagnostico_guardado_em: d ? horaBR(d.em) : null, diagnostico: d ? d.texto : null };
      }
      const d = await s.get('diagnostico');
      return d ? { em: horaBR(d.em), diagnostico: d.texto } : { erro: 'Ainda não chegou nenhum diagnóstico da central.' };
    }
    if (nome === 'ler_tela_central') return this.enviarComando('tela', '');
    if (nome === 'comando_central') {
      if (!a.texto) return { erro: 'Falta o texto do comando.' };
      return this.enviarComando('comando', String(a.texto).slice(0, 300));
    }
    if (nome === 'tocar_na_tela') {
      if (!a.texto) return { erro: 'Falta o texto do botão.' };
      return this.enviarComando('tocar', String(a.texto).slice(0, 120));
    }
    if (nome === 'atualizar_app_central') return this.enviarComando('atualizar', '');
    if (nome === 'dados_do_carro') return this.enviarComando('dados', '');
    if (nome === 'mapa_da_central') return this.enviarComando('mapa', '');
    return { erro: 'Ferramenta desconhecida: ' + nome };
  }

  async mcp(request, token) {
    const nada = () => new Response('Não encontrado', { status: 404 });
    if (token.length < 24 || token.length > 120) return nada();
    const h = await this.ctx.storage.get('mcpHash');
    if (!h || !iguais(h, await sha256(token))) return nada();

    const metodo = request.method.toUpperCase();
    if (metodo === 'DELETE') return new Response(null, { status: 204 });
    if (metodo !== 'POST') return new Response('Método não permitido', { status: 405, headers: { Allow: 'POST' } });

    let corpo;
    try { corpo = await request.json(); } catch (e) { return json(erroMcp(null, -32700, 'JSON inválido'), 400); }
    const lote = Array.isArray(corpo);
    const respostas = [];
    for (const p of (lote ? corpo : [corpo])) {
      if (!p || typeof p !== 'object') { respostas.push(erroMcp(null, -32600, 'Pedido inválido')); continue; }
      const id = p.id;
      const temId = id !== undefined && id !== null;
      try {
        if (p.method === 'initialize') {
          respostas.push(okMcp(id, {
            protocolVersion: (p.params && p.params.protocolVersion) || '2025-06-18',
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: 'painel-asx', title: 'Painel ASX — central do carro', version: '1.0.0' },
            instructions: INSTRUCOES
          }));
        } else if (p.method === 'ping') {
          if (temId) respostas.push(okMcp(id, {}));
        } else if (p.method === 'tools/list') {
          respostas.push(okMcp(id, { tools: FERRAMENTAS }));
        } else if (p.method === 'tools/call') {
          let nome = p.params && p.params.name;
          const args = (p.params && p.params.arguments) || {};
          if (args.acao && FERRAMENTAS.some(f => f.name === args.acao)) nome = args.acao;
          if (!FERRAMENTAS.some(f => f.name === nome)) {
            respostas.push(erroMcp(id, -32602, 'Ferramenta desconhecida: ' + nome));
          } else {
            let r;
            try { r = await this.ferramenta(nome, args); } catch (e) { r = { erro: 'falhou: ' + String((e && e.message) || e).slice(0, 200) }; }
            respostas.push(okMcp(id, { content: [{ type: 'text', text: JSON.stringify(r) }], isError: !!(r && r.erro) }));
          }
        } else if (temId) {
          respostas.push(erroMcp(id, -32601, 'Método não suportado: ' + p.method));
        }
      } catch (e) {
        if (temId) respostas.push(erroMcp(id, -32603, 'Erro interno'));
      }
    }
    if (!respostas.length) return new Response(null, { status: 202 });
    return json(lote ? respostas : respostas[0]);
  }
}

function okMcp(id, result) { return { jsonrpc: '2.0', id, result }; }
function erroMcp(id, code, message) { return { jsonrpc: '2.0', id, error: { code, message } }; }

const INSTRUCOES =
  'Central multimídia Android (K4811, Android 13) do Mitsubishi ASX 2012 do Leonardo, com o app Painel ASX. ' +
  'O app faz na tela tudo o que o dedo faz (Acessibilidade): abrir apps, tocar em botões, voltar, volume, música. ' +
  'Fale com o Leonardo em português do Brasil, simples. A central só está online com o carro ligado e com internet; ' +
  'se estiver offline, diga isso em vez de insistir. Para descobrir onde tocar, use ler_tela_central antes de tocar_na_tela. ' +
  'O registro (registro_central, filtro "ERRO" ou "QUEBROU") mostra os erros do app para corrigir. ' +
 'Dados do motor ainda não existem (falta o leitor OBD2); use dados_do_carro e mapa_da_central para o que a central recebe. Não toque em botões de pagamento, compras ou apagar dados sem o Leonardo confirmar. ' +
  'Se uma ferramenta nova não aparecer na sua lista, chame qualquer ferramenta existente com o campo extra "acao" = nome da ferramenta nova.';

const sem = { type: 'object', properties: {}, additionalProperties: true };
const FERRAMENTAS = [
  { name: 'estado_central', description: 'Se a central do carro está online, último contato, versão do app, se o controle (Acessibilidade) está ligado, que app/tela está aberto agora.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'registro_central', description: 'Últimas linhas do registro do app na central: telas que abriram, comandos, atualizações e ERROS. Use filtro "ERRO" ou "QUEBROU" para ver só problemas.', inputSchema: { type: 'object', properties: { linhas: { type: 'number', description: 'quantas linhas (padrão 80)' }, filtro: { type: 'string', description: 'só linhas que contêm este texto' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'diagnostico_central', description: 'Diagnóstico completo da central: modelo, Android, apps instalados (nome = pacote), pacotes do sistema ligados ao carro (câmera, CAN, rádio...), permissões e registro. Pede um novo à central se ela estiver online.', inputSchema: { type: 'object', properties: { atualizar: { type: 'boolean', description: 'false = só o último guardado' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'ler_tela_central', description: 'Lê o que está na tela da central agora: textos, botões e o que dá para tocar.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'comando_central', description: 'Manda um comando para a central, em português, como se o Leonardo falasse: "abre o YouTube", "câmera de ré", "volume 30", "próxima", "pausa", "tela inicial", "voltar", "rotina".', inputSchema: { type: 'object', properties: { texto: { type: 'string' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'tocar_na_tela', description: 'Toca no botão da tela da central que tem este texto (use ler_tela_central antes para ver os textos).', inputSchema: { type: 'object', properties: { texto: { type: 'string' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'atualizar_app_central', description: 'Instala agora a versão mais nova do app Painel ASX na central. Use só quando o Leonardo aprovar ("pode atualizar"). Depois de atualizar, o Controle cai e ele precisa religar com um toque.', inputSchema: sem, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'dados_do_carro', description: 'Dados que a central recebe agora: ré engatada, GPS (velocidade km/h, posição, direção), últimos sinais internos da central (avisos de fábrica com seus valores) e propriedades internas ligadas ao carro.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'mapa_da_central', description: 'Mapa dos apps de fábrica da central (NWD, câmera, CAN, rádio, ZLink): nomes dos avisos internos que cada um usa, permissões e propriedades. Serve para descobrir de onde vêm os dados do carro.', inputSchema: sem, annotations: { readOnlyHint: true } }
];
