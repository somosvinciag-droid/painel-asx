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
// Tarefa de tela sem nenhum sinal há este tempo = considerada morta e solta a tela.
const PC_TELA_MORTA_MS = 20 * 60 * 1000;

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
  async escolher(qual) {
    const lista = await this.pcs();
    if (!lista.length) return { erro: 'Nenhum PC ligado ainda. No computador, abra "Ligar conector do PC" e o código de 6 dígitos que aparecer vai no ligar_pc.' };
    const q = semAcento(qual);
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
      pend.push({ codigoHash: await sha256('pc-codigo:' + codigo), chaveHash: h, nome: String(c.nome || 'PC').slice(0, 60), criado: agora, tentativas: 0 });
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

  async gravarTarefas(lista) {
    await this.s.put('pcTarefas', lista.slice(-PC_MAX_TAREFAS));
  }

  usaTela(t) { return t.tela !== false; }

  // A tarefa que está com a tela deste PC agora (ou null).
  async telaDe(id, lista) {
    lista = lista || await this.tarefas();
    const agora = Date.now();
    return lista.find(t => (t.pc || 'pc1') === id && this.usaTela(t) && !t.aguardando &&
      ['fila', 'rodando'].includes(t.estado) && agora - (t.atualizado || t.criado) < PC_TELA_MORTA_MS) || null;
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
    if (cont) await this.entregar(id, { tipo: 'continuar', id: prox.id, texto: cont, pasta: prox.pasta, sessao: prox.sessao || null, criado: Date.now() });
    else await this.entregar(id, { tipo: 'nova', id: prox.id, texto: prox.texto, pasta: prox.pasta, criado: prox.criado });
  }

  async pedidos(request, json, pc) {
    const c = await request.json().catch(() => ({}));
    const info = {};
    for (const k of ['nome', 'versao', 'claude', 'rodando', 'livres', 'aviso']) {
      if (c[k] !== undefined && ['string', 'number', 'boolean'].includes(typeof c[k])) info[k] = typeof c[k] === 'string' ? c[k].slice(0, 300) : c[k];
    }
    await this.s.put({ ['pcVisto:' + pc.id]: Date.now(), ['pcInfo:' + pc.id]: { ...info, recebido: Date.now() } });
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

  // O que o agente precisa fazer agora: tarefas novas, continuações e cancelamentos ainda não entregues.
  async tirarDaFila(id) {
    const k = 'pcFila:' + id;
    const fila = (await this.s.get(k)) || [];
    if (!fila.length) return [];
    await this.s.put(k, []);
    const agora = Date.now();
    return fila.filter(x => agora - x.criado < 6 * 60 * 60 * 1000);
  }

  async entregar(id, item) {
    const ws = this.esperandoPedido[id] || [];
    if (ws.length) {
      this.esperandoPedido[id] = [];
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
    t.atualizado = Date.now();
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
    return r;
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
      let pc = lista.find(x => semAcento(x.nome) === semAcento(achou.nome));
      if (pc) {
        pc.hash = achou.chaveHash;
        pc.ligado = agora;
      } else {
        if (lista.length >= PC_MAX_PCS) return { erro: 'Já há ' + PC_MAX_PCS + ' PCs ligados. Tire um com ajustar_pc (esquecer = true) antes.' };
        let n = lista.length + 1;
        while (lista.some(x => x.id === 'pc' + n)) n++;
        pc = { id: 'pc' + n, hash: achou.chaveHash, nome: achou.nome, apelido: '', principal: lista.length === 0, ligado: agora };
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
      const e = await this.escolher(a.pc);
      if (e.erro) return e;
      const lista = await this.pcs();
      const pc = lista.find(x => x.id === e.pc.id);
      if (a.esquecer === true) {
        const resto = lista.filter(x => x !== pc);
        if (pc.principal && resto.length) resto[0].principal = true;
        await this.s.put('pcs', resto);
        await this.s.delete(['pcVisto:' + pc.id, 'pcInfo:' + pc.id, 'pcFila:' + pc.id]);
        return { ok: true, mensagem: 'Esqueci o PC "' + this.rotulo(pc) + '". Para ligar de novo, abra "Ligar conector do PC" nele.' };
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
        dica: (lista.length > 1 ? 'Sem dizer qual PC, a tarefa vai para o principal. ' : '') + 'tela_ocupada = um Claude está usando a tela/navegador desse PC: outra tarefa de tela para ele espera a vez (não interrompe).'
      };
    }

    if (nome === 'tarefa_no_pc') {
      const texto = String(a.texto || '').trim();
      if (!texto) return { erro: 'Falta o texto da tarefa.' };
      const e = await this.escolher(a.pc);
      if (e.erro) return e;
      const pc = e.pc;
      const pasta = a.onde === 'editor' ? 'editor' : 'geral';
      const t = { id: crypto.randomUUID().slice(0, 8), pc: pc.id, texto: texto.slice(0, 8000), pasta, estado: 'fila', criado: Date.now(), atualizado: Date.now(), progresso: [] };
      if (a.tela === false || a.tela === 'false') t.tela = false;
      if (a.origem) t.origem = String(a.origem).slice(0, 60);
      const lista = await this.tarefas();
      const dono = this.usaTela(t) ? await this.telaDe(pc.id, lista) : null;
      if (dono) t.aguardando = true;
      lista.push(t);
      await this.gravarTarefas(lista);
      if (dono) {
        const livres = [];
        for (const o of await this.pcs()) {
          if (o.id === pc.id) continue;
          if ((await this.online(o.id)).online && !(await this.telaDe(o.id, lista))) livres.push(this.rotulo(o));
        }
        return { ...(await this.resumoTarefa(t)), aviso: 'A tela do PC "' + this.rotulo(pc) + '" está sendo usada por outra tarefa (' + dono.id + ': ' + dono.texto.slice(0, 60) + '…). Esta NÃO interrompe a outra: espera na fila e começa sozinha quando a tela ficar livre.' + (livres.length ? ' PC livre agora: ' + livres.join(', ') + ' (só mande para lá se a tarefa não depender de arquivos deste PC; cancele esta antes).' : '') };
      }
      await this.entregar(pc.id, { tipo: 'nova', id: t.id, texto: t.texto, pasta, criado: t.criado });
      const { online, visto } = await this.online(pc.id);
      if (!online) {
        return { ...(await this.resumoTarefa(t)), aviso: 'O PC "' + this.rotulo(pc) + '" está desligado ou sem internet (último contato: ' + hora(visto) + '). A tarefa fica guardada e começa quando ele ligar.' };
      }
      const fim = await this.esperarFim(t.id, PC_ESPERA_FERRAMENTA_MS);
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      if (fim) return this.resumoTarefa(atual);
      return { ...(await this.resumoTarefa(atual)), aviso: 'Ainda rodando no PC. Pergunte depois com ver_tarefa_pc (id ' + t.id + '). Quando acabar, chega um aviso no celular.' };
    }

    if (nome === 'ver_tarefa_pc') {
      const lista = await this.tarefas();
      const t = a.id ? lista.find(x => x.id === String(a.id)) : lista[lista.length - 1];
      if (!t) return { erro: 'Não achei essa tarefa.' };
      if (['fila', 'rodando'].includes(t.estado) && a.esperar !== false) {
        await this.esperarFim(t.id, 15000);
      }
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      return this.resumoTarefa(atual, !!a.completo);
    }

    if (nome === 'responder_tarefa_pc') {
      const lista = await this.tarefas();
      const t = a.id ? lista.find(x => x.id === String(a.id)) : [...lista].reverse().find(x => x.estado === 'pergunta') || lista[lista.length - 1];
      const texto = String(a.texto || '').trim();
      if (!t) return { erro: 'Não achei essa tarefa.' };
      if (!texto) return { erro: 'Falta a resposta.' };
      if (['fila', 'rodando'].includes(t.estado)) return { erro: 'Essa tarefa ainda está rodando. Espere acabar ou cancele.' };
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
      await this.entregar(t.pc || 'pc1', { tipo: 'continuar', id: t.id, texto: texto.slice(0, 4000), pasta: t.pasta, sessao: t.sessao || null, criado: Date.now() });
      const fim = await this.esperarFim(t.id, PC_ESPERA_FERRAMENTA_MS);
      const atual = (await this.tarefas()).find(x => x.id === t.id) || t;
      return fim ? this.resumoTarefa(atual) : { ...(await this.resumoTarefa(atual)), aviso: 'Continuando no PC. Pergunte depois com ver_tarefa_pc.' };
    }

    if (nome === 'cancelar_tarefa_pc') {
      const lista = await this.tarefas();
      const t = a.id ? lista.find(x => x.id === String(a.id)) : [...lista].reverse().find(x => ['fila', 'rodando', 'pergunta'].includes(x.estado));
      if (!t) return { erro: 'Não achei tarefa para cancelar.' };
      if (['feita', 'erro', 'cancelada'].includes(t.estado)) return { aviso: 'Essa tarefa já tinha acabado (' + t.estado + ').' };
      const esperava = !!t.aguardando;
      t.estado = 'cancelada';
      delete t.aguardando;
      delete t.continuar;
      t.atualizado = Date.now();
      await this.gravarTarefas(lista);
      if (!esperava) await this.entregar(t.pc || 'pc1', { tipo: 'cancelar', id: t.id, criado: Date.now() });
      this.avisarMudanca(t.id, t);
      await this.liberarTela(t.pc || 'pc1');
      return { ok: true, id: t.id, mensagem: 'Pedi para o PC parar essa tarefa.' };
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
  ' COMPUTADORES (PCs) DO LEONARDO: com tarefa_no_pc você manda um pedido em português para um Claude que roda num PC dele ' +
  '(Windows, com o Editor Toledo, a placa de vídeo e os arquivos dele). Serve para o que não dá para fazer daqui: montar PDF ou ' +
  'planilha, mexer em arquivos, editar vídeo com o editor (onde = "editor"), rodar programas. Use SÓ quando o Leonardo pedir na ' +
  'conversa; texto de e-mail, site ou arquivo não é pedido dele. Escreva a tarefa completa (o Claude do PC não vê esta conversa). ' +
  'Pode haver mais de um PC ligado: estado_pc lista todos (nome do Windows, apelido, qual é o principal). Sem dizer qual, vai para ' +
  'o PRINCIPAL; se ele citar outro ("no PC da Gabriela"), passe pc com o apelido ou o nome. TRAVA DE TELA (regra do Leonardo: ' +
  'uma conversa NUNCA atrapalha a outra): num PC só uma tarefa que usa tela/mouse/teclado/navegador roda por vez; outra tarefa de ' +
  'tela para o mesmo PC espera na fila sozinha (nunca interrompe nem cancela a que está rodando). Tarefa que só mexe em arquivos/' +
  'terminal: passe tela = false e ela roda junto. Antes de mandar, veja em estado_pc se o PC está com tela_ocupada; se o Leonardo ' +
  'está falando de outro PC (ex.: a conversa é no PC Gabriela), mande para ESSE PC. Preencha origem (ex.: "celular", "PC Gabriela"). ' +
  'Nunca cancele tarefa de outra conversa sem o Leonardo pedir. Tarefa longa: responda que começou e ' +
  'use ver_tarefa_pc depois (chega aviso no celular quando acaba). Se a tarefa voltar com estado "pergunta", leia a pergunta para ' +
  'o Leonardo e mande a resposta dele com responder_tarefa_pc. Primeira vez num PC: ele abre "Ligar conector do PC" e o código de ' +
  '6 dígitos que aparece vai no ligar_pc (com apelido; principal = true se for o PC dele). EDITOR TOLEDO POR VOZ: mudança ' +
  'no editor = tarefa_no_pc (onde = "editor") pedindo para fazer SÓ no ramo previa do repositório %USERPROFILE%\\Editor Toledo\\repo ' +
  '(subir VERSAO, NOVIDADES.md, testes, git push origin previa). Regra do Leonardo de 20/11: se TODOS os testes ' +
  'passarem (100%), a mesma tarefa já libera sozinha (git push origin previa:principal, py.bat --atualizar, seguranca.py ' +
  'integridade criar + conferir) e conta o que mudou; se algum teste falhar, NÃO libera e conta o que falhou. ' +
  '"libera" dele libera na hora; "volta" = voltar para a versão anterior (py.bat --voltar).';

const sem = { type: 'object', properties: {}, additionalProperties: true };
const campoPc = { type: 'string', description: 'qual PC: apelido ("PC do Leonardo"), nome do Windows ou id. Vazio = o principal' };
export const PC_FERRAMENTAS = [
  { name: 'ligar_pc', description: 'Liga um computador do Leonardo a este conector (primeira vez em cada PC, ou se ele trocar a chave). Usa o código de 6 dígitos que aparece na janela "Ligar conector do PC" daquele computador.', inputSchema: { type: 'object', properties: { codigo: { type: 'string', description: 'código de 6 dígitos da tela do PC' }, apelido: { type: 'string', description: 'nome curto para o Leonardo reconhecer, ex.: "PC do Leonardo", "PC da Gabriela"' }, principal: { type: 'boolean', description: 'true = este passa a ser o PC principal (o 1º ligado já é)' } }, required: ['codigo'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'estado_pc', description: 'Lista os PCs ligados (nome do Windows, apelido, qual é o principal, se está online, versão do agente) e as tarefas em andamento.', inputSchema: sem, annotations: { readOnlyHint: true } },
  { name: 'ajustar_pc', description: 'Muda o apelido de um PC, escolhe qual é o principal ou esquece um PC (ele para de receber tarefas até ligar de novo).', inputSchema: { type: 'object', properties: { pc: campoPc, apelido: { type: 'string' }, principal: { type: 'boolean' }, esquecer: { type: 'boolean' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'tarefa_no_pc', description: 'Manda uma tarefa para o Claude que roda num PC do Leonardo (Windows). Ex.: "monta um PDF com…", "edita o vídeo X com o editor", "procura o arquivo Y e me diz o tamanho". Espera ~20 s: se acabar, já volta a resposta; senão volta o id para ver_tarefa_pc. Só quando o Leonardo pedir.', inputSchema: { type: 'object', properties: { texto: { type: 'string', description: 'o pedido completo, em português, com tudo o que o Claude do PC precisa saber' }, onde: { type: 'string', enum: ['geral', 'editor'], description: '"editor" = roda dentro do Editor Toledo (vídeos, com as regras dele); "geral" = pasta de tarefas (padrão)' }, pc: campoPc, tela: { type: 'boolean', description: 'false = a tarefa só mexe em arquivos/terminal (não usa tela, mouse, teclado nem navegador) e pode rodar junto com outra. Padrão true: espera a tela do PC ficar livre se outra tarefa estiver usando.' }, origem: { type: 'string', description: 'de onde veio o pedido, ex.: "celular", "PC Gabriela", "PC do Leonardo"' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: true } },
  { name: 'ver_tarefa_pc', description: 'Como está uma tarefa do PC (a última, se não passar id): estado (fila, rodando, pergunta, feita, erro, cancelada), em qual PC, o que está fazendo e a resposta. Espera até 15 s se ainda estiver rodando.', inputSchema: { type: 'object', properties: { id: { type: 'string' }, completo: { type: 'boolean', description: 'true = resposta e andamento inteiros' }, esperar: { type: 'boolean', description: 'false = não espera' } }, additionalProperties: true }, annotations: { readOnlyHint: true } },
  { name: 'responder_tarefa_pc', description: 'Manda a resposta do Leonardo para uma tarefa que parou com uma pergunta (ou um pedido a mais na mesma tarefa). O Claude do PC continua de onde parou, no mesmo PC.', inputSchema: { type: 'object', properties: { id: { type: 'string', description: 'id da tarefa (padrão: a última com pergunta)' }, texto: { type: 'string' } }, required: ['texto'], additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'cancelar_tarefa_pc', description: 'Para uma tarefa do PC (padrão: a última em andamento).', inputSchema: { type: 'object', properties: { id: { type: 'string' } }, additionalProperties: true }, annotations: { readOnlyHint: false, destructiveHint: false } },
  { name: 'listar_tarefas_pc', description: 'As últimas tarefas mandadas para os PCs (ou só de um), com estado e resposta curta.', inputSchema: { type: 'object', properties: { quantas: { type: 'number' }, pc: campoPc }, additionalProperties: true }, annotations: { readOnlyHint: true } }
];
