// Painel ASX — conector entre o Claude do Leonardo e a central do carro. (publicado por conector.yml)
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

// O app manda os corpos grandes (diagnóstico, backup, respostas) comprimidos com gzip para gastar menos
// internet do celular. Só faz isso depois de o relatório responder "gzip: true". Se o corpo já chegar
// descomprimido (sem a marca 1f 8b do gzip), usa como veio.
async function descomprimir(request) {
  if (request.method !== 'POST') return request;
  if ((request.headers.get('Content-Encoding') || '').toLowerCase() !== 'gzip') return request;
  let corpo = new Uint8Array(await request.arrayBuffer());
  if (corpo.length > 2 && corpo[0] === 0x1f && corpo[1] === 0x8b) {
    corpo = new Uint8Array(await new Response(new Response(corpo).body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer());
  }
  const h = new Headers(request.headers);
  h.delete('Content-Encoding');
  h.delete('Content-Length');
  return new Request(request.url, { method: 'POST', headers: h, body: corpo });
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
      if (p === '/app/recuperar') return await this.recuperar(request);
      if (p.startsWith('/app/')) {
        if (!(await this.appAutorizado(request))) return json({ erro: 'nao autorizado' }, 403);
        request = await descomprimir(request);
        if (p === '/app/relatorio') return await this.relatorio(request);
        if (p === '/app/comandos') return await this.comandos();
        if (p === '/app/resultado') return await this.resultado(request);
        if (p === '/app/nota') return await this.guardarLista(request, 'notas', 1000);
        if (p === '/app/abastecimento') return await this.guardarLista(request, 'abastecimentos', 500);
        if (p === '/app/lembrete') return await this.lembrete(request);
        if (p === '/app/backup') return await this.backup(request);
        if (p === '/app/jarvis') return await this.jarvis(request);
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
    if (c.estado && typeof c.estado === 'object') {
      const { ntfy, ...resto } = c.estado;
      gravar.estado = { ...resto, recebido: agora };
      if (typeof ntfy === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(ntfy)) gravar.ntfy = ntfy;
    }
    if (typeof c.diagnostico === 'string' && c.diagnostico) {
      gravar.diagnostico = { texto: c.diagnostico.slice(0, 120000), em: agora };
    }
    if (Array.isArray(c.linhas) && c.linhas.length) {
      const reg = (await this.ctx.storage.get('registro')) || [];
      for (const l of c.linhas.slice(-300)) reg.push(String(l).slice(0, 2000));
      gravar.registro = reg.slice(-MAX_REGISTRO);
    }
    await this.ctx.storage.put(gravar);
    // gzip: o app (v23+) pode mandar os corpos grandes comprimidos (ver descomprimir).
    return json({ ok: true, gzip: true, jarvis: !!this.env.ANTHROPIC_API_KEY, atlas: !!(this.env.ANTHROPIC_API_KEY && this.env.ATLAS_MCP_URL) });
  }

  // ------------------------------------------------------------------ diário (anotações, abastecimentos)

  async guardarLista(request, chave, max) {
    const c = await request.json();
    const item = {};
    for (const [k, v] of Object.entries(c || {})) {
      if (['string', 'number', 'boolean'].includes(typeof v)) item[k] = typeof v === 'string' ? v.slice(0, 2000) : v;
    }
    item.hora = Number(item.hora) || Date.now();
    item.recebido = Date.now();
    const lista = (await this.ctx.storage.get(chave)) || [];
    lista.push(item);
    await this.ctx.storage.put(chave, lista.slice(-max));
    return json({ ok: true });
  }

  // ------------------------------------------------------------------ avisos no celular (ntfy)

  async lembrete(request) {
    const c = await request.json();
    const min = Math.max(1, Math.min(Number(c.minutos) || 0, 7 * 24 * 60));
    const texto = String(c.texto || 'Lembrete do carro').slice(0, 300);
    const lista = (await this.ctx.storage.get('lembretes')) || [];
    lista.push({ em: Date.now() + min * 60000, texto, criado: Date.now() });
    lista.sort((a, b) => a.em - b.em);
    await this.ctx.storage.put('lembretes', lista.slice(0, 50));
    await this.agendar();
    return json({ ok: true, em: horaBR(Date.now() + min * 60000) });
  }

  async agendar() {
    const lista = (await this.ctx.storage.get('lembretes')) || [];
    if (lista.length) await this.ctx.storage.setAlarm(lista[0].em);
    else await this.ctx.storage.deleteAlarm();
  }

  async avisarCelular(texto, titulo = 'Painel ASX') {
    const topico = await this.ctx.storage.get('ntfy');
    if (!topico) return { erro: 'A central ainda não mandou o canal do ntfy.' };
    const r = await fetch('https://ntfy.sh/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ topic: topico, message: texto, title: titulo, tags: ['red_car'], priority: 4 })
    });
    return r.ok ? { ok: true } : { erro: 'ntfy respondeu ' + r.status };
  }

  async alarm() {
    const lista = (await this.ctx.storage.get('lembretes')) || [];
    const agora = Date.now();
    const vencidos = lista.filter(l => l.em <= agora + 5000);
    const resto = lista.filter(l => l.em > agora + 5000);
    for (const l of vencidos) {
      try { await this.avisarCelular(l.texto, 'Lembrete do carro'); } catch (e) { /* tenta o próximo */ }
    }
    await this.ctx.storage.put('lembretes', resto);
    await this.agendar();
  }

  // ------------------------------------------------------------------ backup e recuperação

  async backup(request) {
    const texto = await request.text();
    if (texto.length > 6000000) return json({ erro: 'backup grande demais' }, 413);
    const PEDACO = 100000;
    const partes = Math.ceil(texto.length / PEDACO) || 1;
    const antigo = (await this.ctx.storage.get('backup_meta')) || { partes: 0 };
    const gravar = {};
    for (let i = 0; i < partes; i++) gravar['backup_' + i] = texto.slice(i * PEDACO, (i + 1) * PEDACO);
    gravar.backup_meta = { partes, em: Date.now(), tamanho: texto.length };
    // put aceita até 128 chaves por vez
    const chaves = Object.keys(gravar);
    for (let i = 0; i < chaves.length; i += 100) {
      const lote = {};
      for (const k of chaves.slice(i, i + 100)) lote[k] = gravar[k];
      await this.ctx.storage.put(lote);
    }
    const sobras = [];
    for (let i = partes; i < antigo.partes; i++) sobras.push('backup_' + i);
    if (sobras.length) await this.ctx.storage.delete(sobras);
    return json({ ok: true, tamanho: texto.length });
  }

  async lerBackup() {
    const meta = await this.ctx.storage.get('backup_meta');
    if (!meta) return null;
    const chaves = [];
    for (let i = 0; i < meta.partes; i++) chaves.push('backup_' + i);
    const m = await this.ctx.storage.get(chaves);
    let t = '';
    for (const k of chaves) t += m.get(k) || '';
    try { return JSON.parse(t); } catch (e) { return null; }
  }

  async recuperar(request) {
    const c = await request.json();
    const antigo = String(c.mcp_antigo || '');
    const chave = String(c.chave || '');
    if (antigo.length < 24 || chave.length < 32) return json({ erro: 'dados curtos' }, 400);
    const h = await this.ctx.storage.get('mcpHash');
    if (!h || !iguais(h, await sha256(antigo))) return json({ erro: 'link não confere' }, 403);
    await this.ctx.storage.put({ appHash: await sha256(chave), recuperado: Date.now() });
    return json({ ok: true, backup: await this.lerBackup() });
  }

  // ------------------------------------------------------------------ Jarvis (conversa pela API do Claude)

  async jarvis(request) {
    const env = this.env;
    if (!env.ANTHROPIC_API_KEY) {
      return json({ erro: 'A conversa com o Jarvis ainda não está ligada: falta a chave da API do Claude no servidor.' }, 501);
    }
    const c = await request.json();
    const pergunta = String(c.pergunta || '').slice(0, 1000);
    if (!pergunta) return json({ erro: 'sem pergunta' }, 400);
    // Limite diário (proteção de custo)
    const dia = new Date().toISOString().slice(0, 10);
    const uso = (await this.ctx.storage.get('jarvis_uso')) || {};
    const hoje = (uso[dia] || 0) + 1;
    if (hoje > 300) return json({ erro: 'Já conversamos demais hoje, senhor. Amanhã eu volto.' }, 429);
    await this.ctx.storage.put('jarvis_uso', { [dia]: hoje });

    const ctxCarro = c.contexto && typeof c.contexto === 'object' ? c.contexto : {};
    const usarAtlas = !!env.ATLAS_MCP_URL && (c.tipo === 'bom_dia' || /atlas|lead|campanha|an[uú]nci|consultor|formul|meta ads|tr[aá]fego|cliente/i.test(pergunta));
    const sistema =
      'Você é o Jarvis, o assistente de voz do carro do Leonardo (Mitsubishi ASX 2012), no estilo do Jarvis do filme: ' +
      'educado, elegante, um pouco espirituoso, trata-o por "senhor" de vez em quando. Ele está dirigindo e vai OUVIR a resposta: ' +
      'responda em português do Brasil, em no máximo 2 ou 3 frases curtas, sem listas, sem markdown, sem emojis, sem links. ' +
      'Números por extenso quando curtos. Se não souber, diga que não sabe. Não incentive mexer no celular dirigindo. ' +
      (usarAtlas ? 'Você tem acesso às ferramentas da Atlas (empresa de recuperação de valores do Leonardo, países PT/ES/PL); use-as só para ler dados. Nunca proponha nem confirme mudanças em anúncios. ' : '') +
      'Situação do carro agora: ' + JSON.stringify(ctxCarro).slice(0, 800);

    const hist = ((await this.ctx.storage.get('jarvis_hist')) || []).filter(m => Date.now() - m.t < 10 * 60000);
    const mensagens = hist.map(m => ({ role: m.role, content: m.content }));
    mensagens.push({ role: 'user', content: pergunta });

    const corpo = {
      model: env.JARVIS_MODELO || 'claude-haiku-5-5',
      max_tokens: 400,
      system: sistema,
      messages: mensagens
    };
    const headers = {
      'x-api-key': env.ANTHROPIC_API_KEY,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json'
    };
    if (usarAtlas) {
      headers['anthropic-beta'] = 'mcp-client-2025-11-20';
      corpo.mcp_servers = [{ type: 'url', url: env.ATLAS_MCP_URL, name: 'atlas' }];
      corpo.tools = [{ type: 'mcp_toolset', mcp_server_name: 'atlas' }];
      corpo.max_tokens = 1500;
    }
    let r;
    try {
      r = await fetch('https://api.anthropic.com/v1/messages', { method: 'POST', headers, body: JSON.stringify(corpo) });
    } catch (e) {
      return json({ erro: 'Não consegui falar com o Claude agora.' }, 502);
    }
    const o = await r.json().catch(() => ({}));
    if (!r.ok) {
      const msg = (o && o.error && o.error.message) || ('status ' + r.status);
      await this.anotarRegistro('jarvis: API respondeu ' + r.status + ': ' + String(msg).slice(0, 200));
      return json({ erro: 'O Claude não respondeu agora (' + r.status + ').' }, 502);
    }
    let texto = (o.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ').trim();
    texto = texto.replace(/[*_#`>]/g, '').replace(/\s+/g, ' ').trim() || 'Sem resposta, senhor.';
    hist.push({ role: 'user', content: pergunta, t: Date.now() }, { role: 'assistant', content: texto, t: Date.now() });
    await this.ctx.storage.put('jarvis_hist', hist.slice(-8));
    return json({ resposta: texto.slice(0, 1200) });
  }

  async anotarRegistro(linha) {
    const reg = (await this.ctx.storage.get('registro')) || [];
    reg.push(new Date().toISOString().slice(11, 19) + ' [servidor] ' + linha);
    await this.ctx.storage.put('registro', reg.slice(-MAX_REGISTRO));
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
    const r = { ok: c.ok !== false, resposta: String(c.resposta ?? '').slice(0, 600000), em: Date.now() };
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
    if (nome === 'viagens_do_carro') {
      if (a.com_trajeto) return this.enviarComando('viagens_mapa', String(a.dias || 7));
      return this.enviarComando('viagens', String(a.dias || 7));
    }
    if (nome === 'anotacoes_do_carro') {
      const dias = Math.max(1, Math.min(Number(a.dias) || 30, 3650));
      const desde = Date.now() - dias * 86400000;
      let lista = ((await s.get('notas')) || []).filter(n => n.hora >= desde);
      if (a.tipo) lista = lista.filter(n => n.tipo === a.tipo);
      return { total: lista.length, anotacoes: lista.map(n => ({ ...n, quando: horaBR(n.hora) })) };
    }
    if (nome === 'abastecimentos_do_carro') {
      const lista = (await s.get('abastecimentos')) || [];
      const comConsumo = lista.filter(x => x.km_por_litro);
      const media = comConsumo.length ? Math.round(comConsumo.reduce((t, x) => t + x.km_por_litro, 0) / comConsumo.length * 10) / 10 : null;
      const gasto30 = lista.filter(x => x.hora > Date.now() - 30 * 86400000).reduce((t, x) => t + (x.reais || 0), 0);
      return { consumo_medio_km_l: media, gasto_ultimos_30_dias: Math.round(gasto30 * 100) / 100, abastecimentos: lista.map(x => ({ ...x, quando: horaBR(x.hora) })) };
    }
    if (nome === 'manutencao_do_carro') return this.enviarComando('manutencao', '');
    if (nome === 'avisar_no_celular') {
      if (!a.texto) return { erro: 'Falta o texto.' };
      if (a.minutos) {
        const lista = (await s.get('lembretes')) || [];
        const em = Date.now() + Math.max(1, Number(a.minutos)) * 60000;
        lista.push({ em, texto: String(a.texto).slice(0, 300), criado: Date.now() });
        lista.sort((x, y) => x.em - y.em);
        await s.put('lembretes', lista.slice(0, 50));
        await this.agendar();
        return { ok: true, avisa_em: horaBR(em) };
      }
      return this.avisarCelular(String(a.texto).slice(0, 300));
    }
    if (nome === 'falar_na_central') {
      if (!a.texto) return { erro: 'Falta o texto.' };
      return this.enviarComando('falar', String(a.texto).slice(0, 400));
    }
    if (nome === 'foto_da_camera') {
      const r = await this.enviarComando('foto', a.camera ? String(a.camera) : '');
      if (r.erro) return r;
      try {
        const o = JSON.parse(r.resposta);
        if (o.imagem) return { imagem: o.imagem, camera: o.camera };
        return { erro: o.erro || 'sem imagem', cameras: o.cameras };
      } catch (e) { return { erro: 'resposta estranha da central' }; }
    }
    if (nome === 'backup_do_carro') {
      const meta = await s.get('backup_meta');
      const lemb = (await s.get('lembretes')) || [];
      return {
        ultimo_backup: meta ? horaBR(meta.em) : 'nunca',
        tamanho_kb: meta ? Math.round(meta.tamanho / 1024) : 0,
        canal_ntfy_configurado: !!(await s.get('ntfy')),
        lembretes_pendentes: lemb.map(l => ({ texto: l.texto, quando: horaBR(l.em) })),
        jarvis_conversa: !!this.env.ANTHROPIC_API_KEY,
        jarvis_atlas: !!(this.env.ANTHROPIC_API_KEY && this.env.ATLAS_MCP_URL)
      };
    }
    if (nome === 'onde_esta_o_carro') {
      const r = await this.enviarComando('onde', '');
      if (!r.erro) return r;
      const e = await this.ctx.storage.get('estado');
      return { aviso: r.erro, ultimo_estado_recebido: e || null };
    }
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
            if (r && r.imagem) {
              respostas.push(okMcp(id, { content: [
                { type: 'image', data: r.imagem, mimeType: 'image/jpeg' },
                { type: 'text', text: 'Foto da câmera ' + (r.camera || '') + ' da central.' }
              ] }));
            } else {
              respostas.push(okMcp(id, { content: [{ type: 'text', text: JSON.stringify(r) }], isError: !!(r && r.erro) }));
            }
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
  'O app também guarda: viagens (com trajeto para mapa), anotações e pedidos de clientes ditados no carro (anotacoes_do_carro), ' +
  'abastecimentos e consumo (abastecimentos_do_carro, para o sistema de gastos), manutenção e odômetro (manutencao_do_carro). ' +
  'avisar_no_celular manda um aviso para o celular dele (app ntfy). falar_na_central fala uma frase na voz do Jarvis no carro. ' +
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
  { name: 'viagens_do_carro', description: 'Diário de viagens (pelo GPS da central): número de viagens, km, horas dirigindo, velocidade média/máxima, nota de direção, origem e destino de cada uma. Use dias=1 (hoje), 7 (semana), 30 (mês). com_trajeto=true traz os pontos [lat, lon] para desenhar o mapa.', inputSchema: { type: 'object', properties: { dias: { type: 'number' }, com_trajeto: { type: 'boolean' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'onde_esta_o_carro', description: 'Última posição GPS guardada pela central (onde o carro está ou onde ficou quando desligou), com hora e link do Google Maps.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'anotacoes_do_carro', description: 'Anotações e pedidos de clientes que o Leonardo ditou no carro ("anota que…", "pedido do cliente…"), com data, hora e local.', inputSchema: { type: 'object', properties: { dias: { type: 'number', description: 'últimos N dias (padrão 30)' }, tipo: { type: 'string', enum: ['nota', 'pedido'] } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'abastecimentos_do_carro', description: 'Abastecimentos ditados no carro: litros, reais, preço do litro, km, consumo km/l, média e gasto dos últimos 30 dias.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'manutencao_do_carro', description: 'Manutenção do carro: odômetro estimado, quanto falta para óleo/filtro, fluido do CVT, rodízio/alinhamento e calibragem.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'avisar_no_celular', description: 'Manda um aviso para o celular do Leonardo (app ntfy). Com minutos, agenda para depois.', inputSchema: { type: 'object', properties: { texto: { type: 'string' }, minutos: { type: 'number' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'falar_na_central', description: 'Fala uma frase em voz alta no carro (voz do Jarvis).', inputSchema: { type: 'object', properties: { texto: { type: 'string' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'foto_da_camera', description: 'EXPERIMENTAL: tira uma foto de uma câmera da central (ré/DVR) e mostra. Pode não funcionar nesta central.', inputSchema: { type: 'object', properties: { camera: { type: 'string', description: 'id da câmera (padrão: a primeira)' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'backup_do_carro', description: 'Quando foi o último backup na nuvem, lembretes pendentes no celular e se o Jarvis (conversa pela API) está ligado.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'mapa_da_central', description: 'Mapa dos apps de fábrica da central (NWD, câmera, CAN, rádio, ZLink): nomes dos avisos internos que cada um usa, permissões e propriedades. Serve para descobrir de onde vêm os dados do carro.', inputSchema: sem, annotations: { readOnlyHint: true } }
];
