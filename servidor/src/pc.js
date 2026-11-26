// Conector do PC — o Claude (também por voz) manda tarefas para os computadores do Leonardo.
//
// Mesmo Worker e mesmo Durable Object do carro, mas tudo do PC fica em chaves "pc*".
// Vários PCs: cada um tem id, nome do Windows (hostname), apelido ("PC do Leonardo") e um deles é o PRINCIPAL
// (vai para ele quando o pedido não diz qual PC).
//  - /pc/registrar   o agente do PC pede um código de 6 dígitos (primeira vez ou chave nova).
//  - /pc/pedidos     o agente busca tarefas (espera até 20 s) e manda o estado do PC.
//  - /pc/andamento   o agente manda o que está fazendo (uma linha por vez).
//  - /pc/resultado   o agente manda o fim da tarefa (resposta, pergunta ou erro).
//  - ferramentas MCP (no mesmo link do conector do Claude): ligar_pc, estado_pc, ajustar_pc, tarefa_no_pc,
//    ver_tarefa_pc, responder_tarefa_pc, cancelar_tarefa_pc, listar_tarefas_pc.
//
// Segurança:
//  - O PC só passa a valer depois que o código de 6 dígitos é conferido pelo próprio conector (ligar_pc).
//    Quem não tem o link do conector não consegue ligar um PC.
//  - Aqui fica só o sha256 da chave de cada PC. O PC fala só por HTTPS, de dentro para fora (sem porta aberta).
//  - As regras do que o Claude do PC pode fazer ficam no agente (não apagar, não instalar, perguntar antes).

const PC_ONLINE_MS = 75 * 1000;
const PC_ESPERA_MS = 20 * 1000;
const PC_ESPERA_FERRAMENTA_MS = 22 * 1000;
const PC_CODIGO_MS = 15 * 60 * 1000;
const PC_MAX_TAREFAS = 60;
const PC_MAX_LINHAS = 40;
const PC_MAX_PCS = 6;
// Trava de tela: num mesmo PC só UMA tarefa que usa tela/mouse/teclado/navegador roda por vez; as outras esperam.
// Tarefa de tela sem NENHUM sinal há este tempo = considerada morta e solta a tela. Maior que o limite de 45 min
// de cada tarefa no agente: tarefa viva e calada (render longo) não perde a tela. O agente 1.7.0+ manda os ids
// das tarefas que tem a cada busca (sinal de vida); tarefa que ele diz que não tem mais é resolvida em minutos.
const PC_TELA_MORTA_MS = 50 * 60 * 1000;
const PC_REENTREGAR_MS = 90 * 1000;        // entregue há mais que isso e o PC diz que não tem: manda de novo
const PC_PERDIDA_MS = 3 * 60 * 1000;       // rodando e o PC diz que não tem há mais que isso: erro (não segura a tela)
const PC_GUARDADA_MS = 6 * 60 * 60 * 1000; // tarefa guardada com o PC desligado vale 6 h
const ABERTAS = ['fila', 'rodando', 'pergunta'];
const PC_ESPERA_VER_MS = 45 * 1000;        // ver_tarefa_pc segura até 45 s esperando o fim (menos conferidas)
const PC_REAPROVEITAR_MS = 12 * 60 * 60 * 1000;
const PESOS = ['leve', 'normal', 'forte'];

function hora(ms) {
  if (!ms) return 'nunca';
  return new Date(ms).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' });
}

function semAcento(x) {
  return String(x || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}

export class ParteDoPC {
  constructor(central) {
    this.c = central;              // o Durable Object (storage, sha256, json…)
    this.esperandoPedido = {};     // id do PC -> pedidos do agente parados esperando tarefa
    this.esperandoFim = {};        // id da tarefa -> funções que querem saber quando mudar
  }

  get s() { return this.c.ctx.storage; }

  // ---------------------------------------------------------------- PCs ligados

  async pcs() {
    let lista = await this.s.get('pcs');
    if (lista) return lista;
    // Migração do formato antigo (um PC só).
    const h = await this.s.get('pcHash');
    lista = [];
    if (h) {
      lista.push({ id: 'pc1', hash: h, nome: (await this.s.get('pcNome')) || 'PC', apelido: '', principal: true, ligado: (await this.s.get('pcLigado')) || Date.now() });
      await this.s.delete(['pcHash', 'pcNome', 'pcLigado']);
    }
    await this.s.put('pcs', lista);
    return lista;
  }

  rotulo(pc) { return pc.apelido ? pc.apelido + ' (' + pc.nome + ')' : pc.nome; }

  // Escolhe o PC pelo que o Claude/Leonardo disse ("meu PC", "o da Gabriela", nome do Windows). Vazio = principal.
  // exigir = true: com mais de um PC ligado, vazio NÃO vai calado para o principal (regra do Leonardo de 26/11:
  // cada conversa pergunta no começo em qual PC roda e manda tudo só para ele).
  async escolher(qual, exigir = false) {
    const lista = await this.pcs();
    if (!lista.length) return { erro: 'Nenhum PC ligado ainda. No computador, abra "Ligar conector do PC" e o código de 6 dígitos que aparecer vai no ligar_pc.' };
    const q = semAcento(qual);
    if (!q && exigir && lista.length > 1) {
      return { erro: 'Falta dizer em qual PC (campo pc). Regra do Leonardo: no começo de cada conversa PERGUNTE a ele em qual PC ela roda e mande todas as tarefas da conversa só para esse PC (a não ser que ele peça outro). Não escolha sozinho. PCs ligados: ' + lista.map(p => this.rotulo(p) + (p.principal ? ' [principal]' : '')).join('; ') };
    }
    if (!q || q === 'principal' || q === 'padrao' || q === 'meu' || q === 'meu pc') {
      return { pc: lista.find(p => p.principal) || lista[0] };
    }
    const exato = lista.filter(p => semAcento(p.id) === q || semAcento(p.nome) === q || semAcento(p.apelido) === q);
    if (exato.length === 1) return { pc: exato[0] };
    const parte = lista.filter(p => semAcento(p.apelido).includes(q) || semAcento(p.nome).includes(q) || (q.length >= 3 && semAcento(p.apelido).split(/\s+/).some(w => w.length >= 3 && q.includes(w))));
    if (parte.length === 1) return { pc: parte[0] };
    return { erro: 'Não sei qual PC é "' + qual + '". PCs ligados: ' + lista.map(p => this.rotulo(p) + (p.principal ? ' [principal]' : '')).join('; ') };
  }

  // ---------------------------------------------------------------- lado do agente (PC)

  async rota(request, p, util) {
    const { json, sha256, iguais } = util;
    if (p === '/pc/registrar') {
      const c = await request.json().catch(() => ({}));
      const chave = String(c.chave || '');
      if (chave.length < 32 || chave.length > 200) return json({ erro: 'chave curta' }, 400);
      const h = await sha256(chave);
      const lista = await this.pcs();
      if (lista.some(x => iguais(x.hash, h))) return json({ ok: true, ligado: true });
      // Código de 6 dígitos para o ligar_pc. Até 4 PCs esperando ao mesmo tempo (o mais velho sai).
      const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
      const codigo = String(n).padStart(6, '0');
      const agora = Date.now();
      const pend = ((await this.s.get('pcPendentes')) || []).filter(x => agora - x.criado < PC_CODIGO_MS && !iguais(x.chaveHash, h));
      const nome = String(c.nome || 'PC').replace(/[^\p{L}\p{N} ._-]/gu, '').trim().slice(0, 60) || 'PC';
      pend.push({ codigoHash: await sha256('pc-codigo:' + codigo), chaveHash: h, nome, criado: agora, tentativas: 0 });
      await this.s.put('pcPendentes', pend.slice(-4));
      return json({ ok: true, ligado: false, codigo, vale_minutos: PC_CODIGO_MS / 60000 });
    }
    // daqui para baixo: só PC ligado
    const a = request.headers.get('Authorization') || '';
    const chave = a.startsWith('Bearer ') ? a.slice(7) : '';
    if (chave.length < 32) return json({ erro: 'PC não ligado' }, 403);
    const h = await sha256(chave);
    const pc = (await this.pcs()).find(x => iguais(x.hash, h));
    if (!pc) return json({ erro: 'PC não ligado' }, 403);

    if (p === '/pc/pedidos') return this.pedidos(request, json, pc);
    if (p === '/pc/andamento') return this.andamento(request, json, pc);
    if (p === '/pc/resultado') return this.resultado(request, json, pc);
    return json({ erro: 'rota desconhecida' }, 404);
  }

  async tarefas() { return (await this.s.get('pcTarefas')) || []; }

  // Guarda as últimas tarefas; as que ainda estão abertas nunca saem da lista (mesmo com mais de 60).
  async gravarTarefas(lista) {
    if (lista.length > PC_MAX_TAREFAS) {
      const corte = lista.length - PC_MAX_TAREFAS;
      lista = lista.filter((t, i) => i >= corte || ABERTAS.includes(t.estado));
    }
    await this.s.put('pcTarefas', lista);
  }

  // Último sinal de que a tarefa existe no PC: andamento, sinal de vida (ids) ou entrega.
  sinal(t) { return Math.max(t.atualizado || 0, t.vivo || 0, t.entregue || 0, t.criado || 0); }

  usaTela(t) { return t.tela !== false; }

  // O que o PC recebe para começar a tarefa (1.8.0+: tela e peso, para o PC ligar o navegador e o modelo certos).
  itemNova(t) { return { tipo: 'nova', id: t.id, texto: t.texto, pasta: t.pasta, criado: t.criado, tela: this.usaTela(t), peso: t.peso || 'normal' }; }

  // A tarefa que está com a tela deste PC agora (ou null).
  async telaDe(id, lista) {
    lista = lista || await this.tarefas();
    const agora = Date.now();
    // Tarefa ainda não entregue (PC desligado) continua dona da vez: senão duas chegariam juntas quando ele ligar.
    return lista.find(t => (t.pc || 'pc1') === id && this.usaTela(t) && !t.aguardando &&
      ['fila', 'rodando'].includes(t.estado) &&
      ((t.estado === 'fila' && !t.entregue) || agora - this.sinal(t) < PC_TELA_MORTA_MS)) || null;
  }

  // Se a tela do PC ficou livre, começa a próxima tarefa de tela que estava esperando (a mais antiga primeiro).
  async liberarTela(id) {
    const lista = await this.tarefas();
    if (await this.telaDe(id, lista)) return;
    const prox = lista.find(t => (t.pc || 'pc1') === id && t.aguardando && t.estado === 'fila');
    if (!prox) return;
    delete prox.aguardando;
    prox.atualizado = Date.now();
    prox.progresso = (prox.progresso || []).concat('A tela do PC ficou livre: começou agora.').slice(-PC_MAX_LINHAS);
    const cont = prox.continuar;
    delete prox.continuar;
    await this.gravarTarefas(lista);
    if (cont) await this.entregar(id, { tipo: 'continuar', id: prox.id, texto: cont, pasta: prox.pasta, sessao: prox.sessao || null, criado: Date.now(), tela: this.usaTela(prox), peso: prox.peso || 'normal' });
    else await this.entregar(id, this.itemNova(prox));
  }

  async pedidos(request, json, pc) {
    const c = await request.json().catch(() => ({}));
    const info = {};
    for (const k of ['nome', 'versao', 'claude', 'rodando', 'livres', 'aviso']) {
      if (c[k] !== undefined && ['string', 'number', 'boolean'].includes(typeof c[k])) info[k] = typeof c[k] === 'string' ? c[k].slice(0, 300) : c[k];
    }
    await this.s.put({ ['pcVisto:' + pc.id]: Date.now(), ['pcInfo:' + pc.id]: { ...info, recebido: Date.now() } });
    if (Array.isArray(c.ids)) await this.conferirIds(pc.id, c.ids.slice(0, 200).map(String));
    await this.liberarTela(pc.id);  // solta a tela de tarefa morta e começa a próxima que esperava
    const prontos = await this.tirarDaFila(pc.id);
    if (prontos.length) return json({ tarefas: prontos });
    const lista = await new Promise(resolve => {
      const w = l => { clearTimeout(t); resolve(l); };
      const t = setTimeout(() => {
        this.esperandoPedido[pc.id] = (this.esperandoPedido[pc.id] || []).filter(x => x !== w);
        resolve([]);
      }, PC_ESPERA_MS);
      (this.esperandoPedido[pc.id] = this.esperandoPedido[pc.id] || []).push(w);
    });
    return json({ tarefas: lista.length ? lista : await this.tirarDaFila(pc.id) });
  }

  // Agente 1.7.0+: a cada busca ele diz quais tarefas tem (rodando, esperando vaga ou com o fim guardado).
  //  - tem: sinal de vida (tarefa calada num comando longo não é dada por morta);
  //  - entregue e ele não tem: a entrega se perdeu (internet caiu na resposta) → manda de novo, até 3 vezes;
  //  - rodando e ele não tem mais: o conector reiniciou no meio → erro, para não segurar a tela nem ficar "rodando" para sempre.
  async conferirIds(id, ids) {
    const lista = await this.tarefas();
    const agora = Date.now();
    const tem = new Set(ids);
    const fila = (await this.s.get('pcFila:' + id)) || [];
    let mudou = false;
    const perdidas = [];
    for (const t of lista) {
      if ((t.pc || 'pc1') !== id || !['fila', 'rodando'].includes(t.estado) || t.aguardando) continue;
      if (tem.has(t.id)) {
        if (agora - (t.vivo || 0) > 60 * 1000) { t.vivo = agora; mudou = true; }
        continue;
      }
      if (fila.some(x => x.id === t.id && x.tipo !== 'cancelar')) continue;   // ainda vai ser entregue
      if (!t.entregue) continue;
      if (t.estado === 'fila' && agora - t.entregue > PC_REENTREGAR_MS) {
        if ((t.reentregas || 0) < 3) {
          t.reentregas = (t.reentregas || 0) + 1;
          t.entregue = 0;
          t.progresso = (t.progresso || []).concat('O PC não recebeu a tarefa: mandando de novo.').slice(-PC_MAX_LINHAS);
          fila.push(t.item || this.itemNova(t));
          await this.s.put('pcFila:' + id, fila.slice(-20));
        } else {
          t.estado = 'erro';
          t.resposta = 'O PC não recebeu a tarefa depois de 3 tentativas. Mande de novo.';
          t.atualizado = agora;
          perdidas.push(t);
        }
        mudou = true;
      } else if (t.estado === 'rodando' && agora - this.sinal(t) > PC_PERDIDA_MS) {
        t.estado = 'erro';
        t.resposta = 'O conector do PC reiniciou (ou o PC desligou) no meio desta tarefa e ela parou. O que já tinha sido feito continua feito; confira e mande de novo o que faltar.';
        t.atualizado = agora;
        perdidas.push(t);
        mudou = true;
      }
    }
    if (mudou) await this.gravarTarefas(lista);
    perdidas.forEach(t => this.avisarMudanca(t.id, t));
  }

  // O que o agente precisa fazer agora: tarefas novas, continuações e cancelamentos ainda não entregues.
  async tirarDaFila(id) {
    const k = 'pcFila:' + id;
    const fila = (await this.s.get(k)) || [];
    if (!fila.length) return [];
    await this.s.put(k, []);
    const agora = Date.now();
    const bons = fila.filter(x => agora - x.criado < PC_GUARDADA_MS);
    await this.marcarEntregues(bons, fila.filter(x => !bons.includes(x)));
    return bons;
  }

  // Anota a hora da entrega (para reentregar se o PC disser que não recebeu) e fecha as que venceram guardadas.
  async marcarEntregues(itens, vencidos = []) {
    if (!itens.length && !vencidos.length) return;
    const lista = await this.tarefas();
    const agora = Date.now();
    let mudou = false;
    for (const x of itens) {
      if (x.tipo === 'cancelar') continue;
      const t = lista.find(y => y.id === x.id);
      if (t && ['fila', 'rodando'].includes(t.estado)) { t.entregue = agora; mudou = true; }
    }
    for (const x of vencidos) {
      const t = lista.find(y => y.id === x.id);
      if (t && t.estado === 'fila' && x.tipo !== 'cancelar') {
        t.estado = 'erro';
        t.resposta = 'A tarefa ficou guardada mais de 6 horas com o PC desligado e venceu. Mande de novo.';
        t.atualizado = agora;
        mudou = true;
      }
    }
    if (mudou) await this.gravarTarefas(lista);
  }

  async entregar(id, item) {
    if (item.tipo === 'continuar') {   // guarda para poder mandar de novo se a entrega se perder
      const lista = await this.tarefas();
      const t = lista.find(y => y.id === item.id);
      if (t) { t.item = item; t.entregue = 0; delete t.reentregas; await this.gravarTarefas(lista); }
    }
    const ws = this.esperandoPedido[id] || [];
    if (ws.length) {
      this.esperandoPedido[id] = [];
      await this.marcarEntregues([item]);
      ws.pop()([item]);
      ws.forEach(w => w([]));
      return;
    }
    const k = 'pcFila:' + id;
    const fila = (await this.s.get(k)) || [];
    fila.push(item);
    await this.s.put(k, fila.slice(-20));
  }

  async andamento(request, json, pc) {
    const c = await request.json().catch(() => ({}));
    const id = String(c.id || '');
    const lista = await this.tarefas();
    const t = lista.find(x => x.id === id && (x.pc || 'pc1') === pc.id);
    if (!t) return json({ erro: 'tarefa desconhecida' }, 404);
    if (t.estado === 'cancelada') return json({ ok: true, cancelar: true });
    t.progresso = (t.progresso || []).concat(String(c.linha || '').slice(0, 300)).slice(-PC_MAX_LINHAS);
    if (t.estado === 'fila') t.estado = 'rodando';
    t.atualizado = Date.now();
    await this.gravarTarefas(lista);
    return json({ ok: true });
  }

  async resultado(request, json, pc) {
    const c = await request.json().catch(() => ({}));
    const id = String(c.id || '');
    const lista = await this.tarefas();
    const t = lista.find(x => x.id === id && (x.pc || 'pc1') === pc.id);
    if (!t) return json({ erro: 'tarefa desconhecida' }, 404);
    const estado = ['feita', 'erro', 'pergunta', 'cancelada'].includes(c.estado) ? c.estado : 'feita';
    if (t.estado !== 'cancelada') t.estado = estado;
    t.resposta = String(c.resposta ?? '').slice(0, 20000);
    if (typeof c.sessao === 'string' && /^[A-Za-z0-9-]{8,80}$/.test(c.sessao)) t.sessao = c.sessao;
    if (Array.isArray(c.arquivos)) t.arquivos = c.arquivos.slice(0, 20).map(x => String(x).slice(0, 300));
    delete t.item;
    t.atualizado = Date.now();
    const g = this.limparGasto(c.gasto);
    if (g) { t.gasto = g; await this.somarGasto(pc.id, t, g); }
    await this.gravarTarefas(lista);
    this.avisarMudanca(id, t);
    await this.liberarTela(pc.id);
    // Aviso no celular (o mesmo canal ntfy do carro), se ele estiver configurado.
    if (t.estado === 'feita' || t.estado === 'pergunta' || t.estado === 'erro') {
      const quem = pc.apelido || pc.nome;
      const titulo = (t.estado === 'feita' ? 'tarefa pronta' : t.estado === 'pergunta' ? 'o Claude tem uma pergunta' : 'tarefa com erro') + ' — ' + quem;
      try { await this.c.avisarCelular(t.texto.slice(0, 80) + ' — ' + String(t.resposta || '').slice(0, 160), titulo); } catch (e) { /* sem aviso */ }
    }
    return json({ ok: true });
  }

  // ---------------------------------------------------------------- gasto (agente 1.8.0+)
  limparGasto(g) {
    if (!g || typeof g !== 'object') return null;
    const n = x => (Number.isFinite(+x) && +x >= 0 ? +x : 0);
    return {
      custo_usd: Math.round(n(g.custo_usd) * 10000) / 10000, rodadas: Math.round(n(g.rodadas)), segundos: Math.round(n(g.segundos)),
      tokens: Math.round(n(g.entrada) + n(g.saida) + n(g.cache_novo) + n(g.cache_lido)),
      tokens_novos: Math.round(n(g.entrada) + n(g.saida) + n(g.cache_novo)), modelo: String(g.modelo || '').slice(0, 80)
    };
  }

  // Soma por dia (fuso do Brasil), por PC, por "de onde" e por tipo. Guarda 31 dias.
  async somarGasto(pcId, t, g) {
    const dia = new Date(Date.now() - 3 * 3600e3).toISOString().slice(0, 10);
    const todos = (await this.s.get('pcGasto')) || {};
    const d = todos[dia] = todos[dia] || { total: { n: 0, custo: 0, tokens: 0 } };
    const tipo = (t.pasta === 'editor' ? 'editor' : (this.usaTela(t) ? 'tela' : 'arquivos')) + (t.peso && t.peso !== 'normal' ? '/' + t.peso : '');
    for (const chave of ['total', 'pc:' + pcId, 'de:' + (t.origem || '?'), 'tipo:' + tipo]) {
      const x = d[chave] = d[chave] || { n: 0, custo: 0, tokens: 0 };
      x.n++; x.custo = Math.round((x.custo + g.custo_usd) * 10000) / 10000; x.tokens += g.tokens;
    }
    const dias = Object.keys(todos).sort();
    while (dias.length > 31) delete todos[dias.shift()];
    await this.s.put('pcGasto', todos);
  }

  // Quanto costuma levar uma tarefa parecida (mesmo PC, pasta e tela), pela mediana das últimas 10 que acabaram.
  previsao(t, lista) {
    const parecidas = lista.filter(x => x !== t && (x.pc || 'pc1') === (t.pc || 'pc1') && x.pasta === t.pasta &&
      this.usaTela(x) === this.usaTela(t) && ['feita', 'pergunta'].includes(x.estado) && x.atualizado > x.criado)
      .slice(-10).map(x => x.atualizado - x.criado).sort((a, b) => a - b);
    if (parecidas.length < 3) return null;
    const med = parecidas[Math.floor(parecidas.length / 2)];
    const falta = Math.max(1, Math.round((med - (Date.now() - t.criado)) / 60000));
    return { costuma_levar_min: Math.max(1, Math.round(med / 60000)), falta_min: falta };
  }

  avisarMudanca(id, t) {
    const fs = this.esperandoFim[id];
    if (fs) {
      delete this.esperandoFim[id];
      fs.forEach(f => f(t));
    }
  }

  esperarFim(id, ms) {
    return new Promise(resolve => {
      const f = t => { clearTimeout(timer); resolve(t); };
      const timer = setTimeout(() => {
        this.esperandoFim[id] = (this.esperandoFim[id] || []).filter(x => x !== f);
        resolve(null);
      }, ms);
      (this.esperandoFim[id] = this.esperandoFim[id] || []).push(f);
    });
  }

  // ---------------------------------------------------------------- ferramentas do Claude

  async online(id) {
    const v = (await this.s.get('pcVisto:' + id)) || 0;
    return { online: Date.now() - v < PC_ONLINE_MS, visto: v };
  }

  async situacaoTela(id, tarefas) {
    const dono = await this.telaDe(id, tarefas);
    const fila = (tarefas || []).filter(t => (t.pc || 'pc1') === id && t.aguardando && t.estado === 'fila').length;
    return {
      tela_ocupada: !!dono,
      tela_ocupada_por: dono ? { id: dono.id, pedido: dono.texto.slice(0, 80), de_onde: dono.origem || null, desde: hora(dono.criado) } : null,
      esperando_tela: fila
    };
  }

  async resumoTarefa(t, completo = false) {
    const lista = await this.pcs();
    const pc = lista.find(p => p.id === (t.pc || 'pc1'));
    // Tarefa ainda rodando: resposta magra (cada conferida é relida pela conversa inteira) + previsão.
    if (!completo && ['fila', 'rodando'].includes(t.estado)) {
      const prev = this.previsao(t, await this.tarefas());
      const m = { id: t.id, pc: pc ? (pc.apelido || pc.nome) : (t.pc || 'pc1'), pedido: t.texto.slice(0, 60) + (t.texto.length > 60 ? '…' : ''),
        estado: t.estado, desde: hora(t.criado), usa_tela: this.usaTela(t), andamento: (t.progresso || []).slice(-2) };
      if (t.origem) m.de_onde = t.origem;
      if (t.aguardando) m.esperando_tela = true;
      if (prev) m.previsao = 'tarefas assim levam ~' + prev.costuma_levar_min + ' min; falta ~' + prev.falta_min + ' min';
      return m;
    }
    const r = {
      id: t.id,
      pc: pc ? this.rotulo(pc) : (t.pc || 'pc1'),
      pedido: t.texto.length > 160 && !completo ? t.texto.slice(0, 160) + '…' : t.texto,
      estado: t.estado,
      criada: hora(t.criado),
      atualizada: hora(t.atualizado),
      onde: t.pasta,
      usa_tela: this.usaTela(t)
    };
    if (t.origem) r.de_onde = t.origem;
    if (t.aguardando) r.esperando_tela = 'Esperando a tela deste PC ficar livre (outra tarefa está usando a tela). Começa sozinha.';
    if (t.estado === 'pergunta') r.pergunta = t.resposta;
    if (['feita', 'erro', 'cancelada'].includes(t.estado)) r.resposta = completo ? t.resposta : String(t.resposta || '').slice(0, 1500);
    if (t.progresso && t.progresso.length) r.andamento = completo ? t.progresso : t.progresso.slice(-6);
    if (t.arquivos && t.arquivos.length) r.arquivos = t.arquivos;
    if (t.gasto) r.gasto = t.gasto;
    return r;
  }

  // Sem id não se escolhe "a última": devolve as tarefas abertas (e algumas recentes) para o Claude achar a da SUA conversa.
  async pedirId(lista, frase, recentes) {
    const abertas = lista.filter(t => ABERTAS.includes(t.estado));
    const outras = recentes ? lista.filter(t => !ABERTAS.includes(t.estado)).slice(-recentes) : [];
    const out = [];
    const pcs = await this.pcs();
    for (const t of abertas.concat(outras).reverse()) {
      const pc = pcs.find(p => p.id === (t.pc || 'pc1'));
      out.push({ id: t.id, pc: pc ? (pc.apelido || pc.nome) : (t.pc || 'pc1'), estado: t.estado, pedido: t.texto.slice(0, 100), de_onde: t.origem || null, criada: hora(t.criado) });
    }
    return { erro: frase, tarefas: out };
  }

  async ferramenta(nome, a, util) {
    const { sha256, iguais } = util;
    if (nome === 'ligar_pc') {
      const codigo = String(a.codigo || '').replace(/\D/g, '');
      const agora = Date.now();
      let pend = ((await this.s.get('pcPendentes')) || []).filter(x => agora - x.criado < PC_CODIGO_MS && x.tentativas < 5);
      if (!pend.length) {
        await this.s.put('pcPendentes', []);
        return { erro: 'Nenhum PC esperando (ou o código venceu: vale 15 minutos). No computador, abra "Ligar conector do PC" para aparecer um código novo.' };
      }
      const h = codigo.length === 6 ? await sha256('pc-codigo:' + codigo) : '';
      const achou = h ? pend.find(x => iguais(x.codigoHash, h)) : null;
      if (!achou) {
        pend.forEach(x => x.tentativas++);
        await this.s.put('pcPendentes', pend);
        return { erro: 'Código não confere. Confira o número de 6 dígitos na tela do computador.' };
      }
      await this.s.put('pcPendentes', pend.filter(x => x !== achou));
      let lista = await this.pcs();
      // Mesmo computador (mesmo nome do Windows) com chave nova: troca a chave e mantém apelido e principal.
      let pc = a.novo === true ? null : lista.find(x => semAcento(x.nome) === semAcento(achou.nome));
      // Mesmo nome do Windows de um PC que está ONLINE agora com outra chave: pode ser OUTRO computador com o mesmo
      // nome. Trocar a chave calado mandaria as tarefas do PC antigo para o novo: pergunta antes.
      if (pc && a.trocar !== true && (await this.online(pc.id)).online) {
        await this.s.put('pcPendentes', pend);   // devolve o código (ele continua valendo)
        return { erro: 'Já existe um PC ligado e ONLINE agora com o nome "' + achou.nome + '" (' + this.rotulo(pc) + '). Pergunte ao Leonardo: é OUTRO computador com o mesmo nome (chame de novo com novo = true e um apelido) ou é o MESMO com chave nova (trocar = true)? O código continua valendo.' };
      }
      if (pc) {
        pc.hash = achou.chaveHash;
        pc.ligado = agora;
      } else {
        if (lista.length >= PC_MAX_PCS) return { erro: 'Já há ' + PC_MAX_PCS + ' PCs ligados. Tire um com ajustar_pc (esquecer = true) antes.' };
        // O id nunca se repete (um PC novo não herda as tarefas de um PC esquecido).
        let n = Math.max((await this.s.get('pcProximo')) || 0, lista.length + 1);
        const usados = new Set((await this.tarefas()).map(t => t.pc || 'pc1'));
        while (lista.some(x => x.id === 'pc' + n) || usados.has('pc' + n)) n++;
        await this.s.put('pcProximo', n + 1);
        const nome = a.novo === true && lista.some(x => semAcento(x.nome) === semAcento(achou.nome)) ? (achou.nome + ' (' + n + ')').slice(0, 60) : achou.nome;
        pc = { id: 'pc' + n, hash: achou.chaveHash, nome, apelido: '', principal: lista.length === 0, ligado: agora };
        lista.push(pc);
      }
      if (typeof a.apelido === 'string' && a.apelido.trim()) pc.apelido = a.apelido.trim().slice(0, 40);
      if (a.principal === true) lista.forEach(x => { x.principal = x === pc; });
      await this.s.put('pcs', lista);
      return {
        ok: true, pc: this.rotulo(pc), principal: !!pc.principal,
        mensagem: 'PC "' + this.rotulo(pc) + '" ligado' + (pc.principal ? ' (é o principal: os pedidos vão para ele quando não disser qual PC)' : '') + '.'
      };
    }

    if (nome === 'ajustar_pc') {
      const e = await this.escolher(a.pc, true);
      if (e.erro) return e;
      const lista = await this.pcs();
      const pc = lista.find(x => x.id === e.pc.id);
      if (a.esquecer === true) {
        const resto = lista.filter(x => x !== pc);
        if (pc.principal && resto.length) resto[0].principal = true;
        await this.s.put('pcs', resto);
        await this.s.delete(['pcVisto:' + pc.id, 'pcInfo:' + pc.id, 'pcFila:' + pc.id]);
        // As tarefas abertas daquele PC acabam aqui (não ficam para um PC novo, nem "fila" para sempre).
        const tarefas = await this.tarefas();
        let fechadas = 0;
        for (const t of tarefas) {
          if ((t.pc || 'pc1') === pc.id && ABERTAS.includes(t.estado)) {
            t.estado = 'cancelada'; t.resposta = t.resposta || 'O PC foi esquecido (ajustar_pc).'; t.atualizado = Date.now();
            delete t.aguardando; delete t.continuar; delete t.item;
            fechadas++;
            this.avisarMudanca(t.id, t);
          }
        }
        if (fechadas) await this.gravarTarefas(tarefas);
        return { ok: true, mensagem: 'Esqueci o PC "' + this.rotulo(pc) + '"' + (fechadas ? ' e fechei ' + fechadas + ' tarefa(s) aberta(s) dele' : '') + '. Para ligar de novo, abra "Ligar conector do PC" nele.' };
      }
      if (typeof a.apelido === 'string') pc.apelido = a.apelido.trim().slice(0, 40);
      if (a.principal === true) lista.forEach(x => { x.principal = x === pc; });
      await this.s.put('pcs', lista);
      return { ok: true, pc: this.rotulo(pc), principal: !!pc.principal };
    }

    if (nome === 'estado_pc') {
      const lista = await this.pcs();
      const tarefas = await this.tarefas();
      const pcs = [];
      for (const pc of lista) {
        const { online, visto } = await this.online(pc.id);
        pcs.push({
          id: pc.id, nome_windows: pc.nome, apelido: pc.apelido || null, principal: !!pc.principal,
          online, ultimo_contato: hora(visto), info: (await this.s.get('pcInfo:' + pc.id)) || null,
          ...(await this.situacaoTela(pc.id, tarefas))
        });
      }
      const andando = [];
      for (const t of tarefas.filter(t => ['fila', 'rodando', 'pergunta'].includes(t.estado))) andando.push(await this.resumoTarefa(t));
      const pend = ((await this.s.get('pcPendentes')) || []).filter(x => Date.now() - x.criado < PC_CODIGO_MS);
      return {
        pcs,
        esperando_codigo: pend.map(x => x.nome),
        tarefas_em_andamento: andando,
        dica: (lista.length > 1 ? 'Há mais de um PC: toda tarefa precisa do campo pc. Regra do Leonardo: no começo de cada conversa pergunte em qual PC ela roda e mande tudo só para ele. ' : '') + 'tela_ocupada = um Claude está usando a tela/navegador desse PC: outra tarefa de tela para ele espera a vez (não interrompe).'
      };
    }

    if (nome === 'tarefa_no_pc') {
      const texto = String(a.texto || '').trim();
      if (!texto) return { erro: 'Falta o texto da tarefa.' };
      const e = await this.escolher(a.pc, true);
      if (e.erro) return e;
      const pc = e.pc;
      const pasta = a.onde === 'editor' ? 'editor' : 'geral';
      // tela é obrigatório (o padrão "sim" segurava a tela com tarefa só de arquivos, e o contrário deixava passar
      // tarefa de tela sem esperar a vez). Tarefa do editor (vídeo) não usa a tela, salvo se disser tela = true.
      const temTela = a.tela === true || a.tela === 'true' || a.tela === false || a.tela === 'false';
      if (!temTela && pasta !== 'editor') {
        return { erro: 'Falta o campo tela: true = a tarefa usa tela, mouse, teclado ou navegador do PC (espera a vez se outra estiver usando); false = só arquivos, terminal e internet sem navegador (roda junto com as outras). Mande de novo com tela preenchido.' };
      }
      if (a.reaproveitar === true || a.reaproveitar === 'true') {
        // O mesmo pedido já feito neste PC nas últimas 12 h: devolve o resultado guardado (sem gastar de novo).
        const igual = [...(await this.tarefas())].reverse().find(x => (x.pc || 'pc1') === pc.id && x.estado === 'feita' &&
          x.texto.trim() === texto.slice(0, 8000).trim() && Date.now() - x.atualizado < PC_REAPROVEITAR_MS);
        if (igual) return { ...(await this.resumoTarefa(igual)), aviso: 'Reaproveitada: o mesmo pedido já tinha sido feito neste PC às ' + hora(igual.atualizado) + ' (nada novo foi rodado). Para fazer de novo, mande sem reaproveitar.' };
      }
      const t = { id: crypto.randomUUID().slice(0, 8), pc: pc.id, texto: texto.slice(0, 8000), pasta, estado: 'fila', criado: Date.now(), atualizado: Date.now(), progresso: [] };
      if (PESOS.includes(a.peso) && a.peso !== 'normal') t.peso = a.peso;
      if (a.tela === false || a.tela === 'false' || (!temTela && pasta === 'editor')) t.tela = false;
      if (a.origem) t.origem = String(a.origem).slice(0, 60);
      const lista = await this.tarefas();
      const dono = this.usaTela(t) ? await this.telaDe(pc.id, lista) : null;
      if (dono) t.aguardando = true;
      lista.push(t);
      await this.gravarTarefas(lista);
      if (dono) {
        return { ...(await this.resumoTarefa(t)), aviso: 'A tela do PC "' + this.rotulo(pc) + '" está sendo usada por outra tarefa (' + dono.id + ': ' + dono.texto.slice(0, 60) + '…). Esta NÃO interrompe a outra: espera na fila e começa sozinha quando a tela ficar livre. Não mande para outro PC por conta própria (cada conversa roda no PC que o Leonardo escolheu).' };
      }
      await this.entregar(pc.id, this.itemNova(t));
      const { online, visto } = await this.online(pc.id);
      if (!online) {
        return { ...(await this.resumoTarefa(t)), aviso: 'O PC "' + this.rotulo(pc) + '" está desligado ou sem internet (último contato: ' + hora(visto) + '). A tarefa fica guardada e começa quando ele ligar.' };
      }
      const fim = await this.esperarFim(t.id, PC_ESPERA_FERRAMENTA_MS);
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      if (fim) return this.resumoTarefa(atual);
      return { ...(await this.resumoTarefa(atual)), aviso: 'Ainda rodando no PC (id ' + t.id + '). Não fique conferindo: quando acabar chega aviso no celular. Se precisar conferir, ver_tarefa_pc com o id espera até 45 s pelo fim; confira no máximo depois da previsão.' };
    }

    if (nome === 'ver_tarefa_pc') {
      const lista = await this.tarefas();
      if (!a.id) return this.pedirId(lista, 'Diga o id da tarefa (campo id): sem ele eu poderia mostrar a tarefa de outra conversa.', 4);
      const t = lista.find(x => x.id === String(a.id));
      if (!t) return { erro: 'Não achei essa tarefa.' };
      if (['fila', 'rodando'].includes(t.estado) && a.esperar !== false) {
        await this.esperarFim(t.id, PC_ESPERA_VER_MS);
      }
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      return this.resumoTarefa(atual, !!a.completo);
    }

    if (nome === 'responder_tarefa_pc') {
      const lista = await this.tarefas();
      if (!a.id) return this.pedirId(lista, 'Diga o id da tarefa que vai receber a resposta (campo id): sem ele a resposta poderia ir para a pergunta de outra conversa. Nada foi enviado.', 2);
      const t = lista.find(x => x.id === String(a.id));
      const texto = String(a.texto || '').trim();
      if (!t) return { erro: 'Não achei essa tarefa.' };
      if (!texto) return { erro: 'Falta a resposta.' };
      if (['fila', 'rodando'].includes(t.estado)) return { erro: 'Essa tarefa ainda está rodando. Espere acabar ou cancele.' };
      if (t.estado === 'cancelada') return { erro: 'Essa tarefa foi cancelada e não volta a rodar. Se o Leonardo quiser, mande uma tarefa nova.' };
      if (!(await this.pcs()).some(p => p.id === (t.pc || 'pc1'))) return { erro: 'O PC dessa tarefa não está mais ligado ao conector.' };
      t.estado = 'fila';
      t.progresso = (t.progresso || []).concat('Leonardo respondeu: ' + texto.slice(0, 200)).slice(-PC_MAX_LINHAS);
      t.atualizado = Date.now();
      const dono = this.usaTela(t) ? await this.telaDe(t.pc || 'pc1', lista.filter(x => x !== t)) : null;
      if (dono) {
        t.aguardando = true;
        t.continuar = texto.slice(0, 4000);
        await this.gravarTarefas(lista);
        return { ...(await this.resumoTarefa(t)), aviso: 'Resposta guardada. A tela desse PC está com outra tarefa (' + dono.id + '); esta continua sozinha quando a tela ficar livre.' };
      }
      await this.gravarTarefas(lista);
      await this.entregar(t.pc || 'pc1', { tipo: 'continuar', id: t.id, texto: texto.slice(0, 4000), pasta: t.pasta, sessao: t.sessao || null, criado: Date.now(), tela: this.usaTela(t), peso: t.peso || 'normal' });
      const fim = await this.esperarFim(t.id, PC_ESPERA_FERRAMENTA_MS);
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      return fim ? this.resumoTarefa(atual) : { ...(await this.resumoTarefa(atual)), aviso: 'Continuando no PC. Pergunte depois com ver_tarefa_pc.' };
    }

    if (nome === 'cancelar_tarefa_pc') {
      const lista = await this.tarefas();
      if (!a.id) return this.pedirId(lista, 'Diga o id da tarefa a cancelar (campo id): sem ele eu poderia parar a tarefa de outra conversa ou de outro PC. Nada foi cancelado. Só cancele tarefa que o Leonardo pediu para cancelar.', 0);
      const t = lista.find(x => x.id === String(a.id));
      if (!t) return { erro: 'Não achei tarefa para cancelar.' };
      if (['feita', 'erro', 'cancelada'].includes(t.estado)) return { aviso: 'Essa tarefa já tinha acabado (' + t.estado + ').' };
      const esperava = !!t.aguardando;
      t.estado = 'cancelada';
      delete t.aguardando;
      delete t.continuar;
      delete t.item;
      t.atualizado = Date.now();
      await this.gravarTarefas(lista);
      if (!esperava) await this.entregar(t.pc || 'pc1', { tipo: 'cancelar', id: t.id, criado: Date.now() });
      this.avisarMudanca(t.id, t);
      await this.liberarTela(t.pc || 'pc1');
      return { ok: true, id: t.id, mensagem: 'Pedi para o PC parar essa tarefa.' };
    }

    if (nome === 'gasto_pc') {
      const todos = (await this.s.get('pcGasto')) || {};
      const n = Math.max(1, Math.min(Number(a.dias) || 7, 31));
      const dias = Object.keys(todos).sort().slice(-n);
      const nomes = Object.fromEntries((await this.pcs()).map(p => ['pc:' + p.id, 'PC: ' + (p.apelido || p.nome)]));
      const somado = {};
      for (const d of dias) for (const [k, v] of Object.entries(todos[d])) {
        const x = somado[k] = somado[k] || { n: 0, custo: 0, tokens: 0 };
        x.n += v.n; x.custo = Math.round((x.custo + v.custo) * 100) / 100; x.tokens += v.tokens;
      }
      const grupos = Object.entries(somado).filter(([k]) => k !== 'total').sort((x, y) => y[1].custo - x[1].custo)
        .map(([k, v]) => ({ grupo: nomes[k] || k.replace(/^de:/, 'de onde: ').replace(/^tipo:/, 'tipo: '), tarefas: v.n, custo_usd: v.custo, tokens: v.tokens }));
      const caras = (await this.tarefas()).filter(t => t.gasto).sort((x, y) => y.gasto.custo_usd - x.gasto.custo_usd).slice(0, 10)
        .map(t => ({ id: t.id, quando: hora(t.criado), pedido: t.texto.slice(0, 70), custo_usd: t.gasto.custo_usd, tokens: t.gasto.tokens, rodadas: t.gasto.rodadas, modelo: t.gasto.modelo }));
      return {
        dias: dias.map(d => ({ dia: d, tarefas: todos[d].total.n, custo_usd: todos[d].total.custo, tokens: todos[d].total.tokens })),
        total: somado.total || { n: 0, custo: 0, tokens: 0 }, por_grupo: grupos, mais_caras: caras,
        nota: 'Só as tarefas mandadas aos PCs (agente 1.8.0+). custo_usd = equivalente em dólar da API que o Claude Code informa; na assinatura Max não é cobrado assim, mas mostra o que pesa mais. Conversas do app e agentes extras não entram aqui.'
      };
    }

    if (nome === 'listar_tarefas_pc') {
      const n = Math.max(1, Math.min(Number(a.quantas) || 10, PC_MAX_TAREFAS));
      let lista = await this.tarefas();
      if (a.pc) {
        const e = await this.escolher(a.pc);
        if (e.erro) return e;
        lista = lista.filter(t => (t.pc || 'pc1') === e.pc.id);
      }
      const out = [];
      for (const t of lista.slice(-n).reverse()) out.push(await this.resumoTarefa(t));
      return { total: lista.length, tarefas: out };
    }

    return null; // não é ferramenta do PC
  }
}

export const PC_INSTRUCOES =
  ' PCs DO LEONARDO (tarefa_no_pc): manda um pedido em português a um Claude num PC dele (Windows, Editor Toledo, ' +
  'arquivos, placa de vídeo). Só quando ele pedir na conversa; escreva a tarefa completa (o Claude do PC não vê ' +
  'esta conversa). QUAL PC: no começo de cada conversa PERGUNTE em qual PC ela roda ("meu PC" = PC do Leonardo) e ' +
  'mande tudo só para ele; nunca escolha nem troque sozinho. tela: true = usa tela/mouse/teclado/navegador (espera ' +
  'a vez se outra tarefa usa a tela daquele PC); false = só arquivos/terminal. peso: "leve" (ler, listar, copiar, ' +
  'baixar, renomear, conferir) roda num modelo menor e gasta bem menos; "normal" (padrão); "forte" (difícil). ' +
  'Guarde o id: ver/responder/cancelar precisam dele; nunca mexa em tarefa de outra conversa. NÃO fique ' +
  'conferindo: chega aviso no celular quando acaba; ver_tarefa_pc espera até 45 s. Estado "pergunta": leia a ' +
  'pergunta para ele e mande a resposta com responder_tarefa_pc. gasto_pc mostra quanto as tarefas gastaram. ' +
  'EDITOR TOLEDO: mudança no editor = tarefa (onde = "editor") só no ramo previa de %USERPROFILE%\\Editor Toledo\\repo ' +
  '(VERSAO, NOVIDADES.md, testes, push previa); com 100% dos testes a mesma tarefa libera (push previa:principal, ' +
  'py.bat --atualizar, seguranca.py integridade criar + conferir); falhou = não libera e conta. "volta" = py.bat --voltar. ' +
  'PC novo: "Ligar conector do PC" mostra um código de 6 dígitos para o ligar_pc.';

const sem = { type: 'object', properties: {}, additionalProperties: true };
const campoPc = { type: 'string', description: 'apelido ("PC do Leonardo", "PC Gabriela"), nome do Windows ou id; obrigatório com 2+ PCs (o que ele escolheu para esta conversa)' };
export const PC_FERRAMENTAS = [
  { name: 'ligar_pc', description: 'Liga um PC do Leonardo ao conector com o código de 6 dígitos da janela "Ligar conector do PC".', inputSchema: { type: 'object', properties: { codigo: { type: 'string' }, apelido: { type: 'string', description: 'ex.: "PC do Leonardo"' }, principal: { type: 'boolean' }, novo: { type: 'boolean', description: 'outro PC com o mesmo nome do Windows (com o OK dele)' }, trocar: { type: 'boolean', description: 'mesmo PC, chave nova (com o OK dele)' } }, required: ['codigo'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'estado_pc', description: 'PCs ligados (online, versão, tela ocupada) e tarefas em andamento.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'ajustar_pc', description: 'Muda apelido, escolhe o principal ou esquece um PC.', inputSchema: { type: 'object', properties: { pc: campoPc, apelido: { type: 'string' }, principal: { type: 'boolean' }, esquecer: { type: 'boolean' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'tarefa_no_pc', description: 'Manda uma tarefa ao Claude de um PC do Leonardo (só quando ele pedir). Espera ~20 s; se não acabar, volta o id.', inputSchema: { type: 'object', properties: { texto: { type: 'string', description: 'o pedido completo' }, onde: { type: 'string', enum: ['geral', 'editor'], description: '"editor" = dentro do Editor Toledo (vídeos)' }, pc: campoPc, tela: { type: 'boolean', description: 'OBRIGATÓRIO: true = usa tela/mouse/teclado/navegador; false = só arquivos/terminal (com onde = "editor" vale false)' }, peso: { type: 'string', enum: ['leve', 'normal', 'forte'], description: 'leve = modelo menor, gasta bem menos (ler, listar, copiar, baixar, conferir); padrão normal' }, reaproveitar: { type: 'boolean', description: 'true = se o MESMO pedido já foi feito neste PC nas últimas 12 h, devolve aquele resultado sem rodar de novo' }, origem: { type: 'string', description: 'ex.: "celular", "PC Gabriela"' } }, required: ['texto', 'tela'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: true } },
  { name: 'ver_tarefa_pc', description: 'Estado de uma tarefa (pelo id). Rodando: espera até 45 s pelo fim e responde curto com a previsão.', inputSchema: { type: 'object', properties: { id: { type: 'string' }, completo: { type: 'boolean', description: 'resposta e andamento inteiros' }, esperar: { type: 'boolean', description: 'false = não espera' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'responder_tarefa_pc', description: 'Manda a resposta do Leonardo a uma tarefa parada com pergunta (ou um pedido a mais nela).', inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'id da tarefa desta conversa' }, texto: { type: 'string' } }, required: ['id', 'texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'cancelar_tarefa_pc', description: 'Para a tarefa do id dado (só a que o Leonardo pediu para parar).', inputSchema: { type: 'object', properties: { id: { type: 'string' } }, required: ['id'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'listar_tarefas_pc', description: 'Últimas tarefas dos PCs (ou de um), com estado.', inputSchema: { type: 'object', properties: { quantas: { type: 'number' }, pc: campoPc }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'gasto_pc', description: 'Quanto as tarefas dos PCs gastaram: por dia, por PC, por tipo e as mais caras.', inputSchema: { type: 'object', properties: { dias: { type: 'number', description: 'padrão 7, máx. 31' } }, additionalProperties: true }, annotations: { readOnlyHint: true } }
];
