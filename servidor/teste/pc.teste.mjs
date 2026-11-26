// Testes do conector do PC (servidor/src/pc.js) com um armazenamento falso. Rodar: node servidor/teste/pc.teste.mjs
import { ParteDoPC } from '../src/pc.js';
let off = 0; const realNow = Date.now; Date.now = () => realNow() + off;
const rst = globalThis.setTimeout; globalThis.setTimeout = (f, ms) => rst(f, Math.max(1, ms / 1000));
const enc = new TextEncoder();
const sha256 = async s => Buffer.from(await crypto.subtle.digest('SHA-256', enc.encode(s))).toString('hex');
const iguais = (a, b) => a === b;
const json = (o, st = 200) => ({ st, o });
function novo() {
  const m = new Map();
  const s = {
    async get(k) { if (Array.isArray(k)) return new Map(k.map(x => [x, m.get(x)])); return structuredClone(m.get(k)); },
    async put(k, v) { if (typeof k === 'object') { for (const [a, b] of Object.entries(k)) m.set(a, structuredClone(b)); } else m.set(k, structuredClone(v)); },
    async delete(k) { (Array.isArray(k) ? k : [k]).forEach(x => m.delete(x)); }
  };
  return new ParteDoPC({ ctx: { storage: s }, avisarCelular: async () => {} });
}
const U = { json, sha256, iguais };
const req = (body, chave) => ({ json: async () => body, headers: { get: () => chave ? 'Bearer ' + chave : '' } });
const K1 = 'a'.repeat(40), K2 = 'b'.repeat(40), K3 = 'c'.repeat(40);
async function ligar(P, chave, nome, apelido, extra = {}) {
  const r = await P.rota(req({ chave, nome }), '/pc/registrar', U);
  return P.ferramenta('ligar_pc', { codigo: r.o.codigo, apelido, ...extra }, U);
}
const poll = (P, k, corpo = {}) => P.rota(req(corpo, k), '/pc/pedidos', U);
const F = (P, n, a) => P.ferramenta(n, a, U);
const T = (P, a) => F(P, 'tarefa_no_pc', a);
const res = (P, k, id, estado = 'feita') => P.rota(req({ id, estado, resposta: 'ok' }, k), '/pc/resultado', U);
const and = (P, k, id) => P.rota(req({ id, linha: 'x' }, k), '/pc/andamento', U);
const ids = r => r.o.tarefas.map(x => x.tipo + ':' + x.id);
const ver = async (P, id) => F(P, 'ver_tarefa_pc', { id, esperar: false });
let ok = 0, total = 0;
const conf = (nome, c, det) => { total++; if (c) ok++; console.log((c ? '  CERTO  ' : '  ERRADO ') + nome + (c ? '' : '  ' + JSON.stringify(det))); };
async function setup() { const P = novo(); await ligar(P, K1, 'Leonardo', 'PC do Leonardo'); await ligar(P, K2, 'DESKTOP-UU83OER', 'PC Gabriela'); return P; }
const L = 'PC do Leonardo', G = 'PC Gabriela';

{ // 1 qual PC
  const P = await setup();
  let r = await T(P, { texto: 'x', tela: false });
  conf('2 PCs e sem pc: recusa e manda perguntar ao Leonardo', r.erro && /PERGUNTE/.test(r.erro) && (await P.tarefas()).length === 0, r);
  r = await T(P, { texto: 'x', tela: false, pc: 'meu PC' });
  conf('"meu PC" vai para o notebook', r.pc && r.pc.startsWith(L), r);
  r = await T(P, { texto: 'x', tela: false, pc: G });
  conf('"PC Gabriela" vai para o PC Gabriela', r.pc && r.pc.startsWith(G), r);
  r = await T(P, { texto: 'x', tela: false, pc: 'Gabi' });
  conf('nome desconhecido: erro, sem chutar', !!r.erro, r);
  const P1 = novo(); await ligar(P1, K1, 'Leonardo', L);
  r = await T(P1, { texto: 'x', tela: false });
  conf('um PC só: sem pc continua funcionando', !r.erro && r.id, r);
  r = await F(P, 'ajustar_pc', { esquecer: true });
  conf('ajustar_pc (esquecer) sem pc com 2 PCs: recusa', !!r.erro && (await P.pcs()).length === 2, r);
}
{ // 2 tela obrigatório
  const P = await setup();
  let r = await T(P, { texto: 'x', pc: L });
  conf('sem o campo tela: recusa e explica', r.erro && /tela/.test(r.erro), r);
  r = await T(P, { texto: 'edita', pc: L, onde: 'editor' });
  conf('editor sem tela: vale tela=false', r.usa_tela === false, r);
  r = await T(P, { texto: 'x', pc: L, tela: true });
  conf('tela=true aceito', r.usa_tela === true, r);
}
{ // 3 sem id não pega tarefa de outra conversa
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: true, origem: 'celular' });
  const g = await T(P, { texto: 'G', pc: G, tela: false, origem: 'PC Gabriela' });
  let r = await F(P, 'cancelar_tarefa_pc', {});
  conf('cancelar sem id: nada cancelado, devolve a lista', r.erro && r.tarefas.length === 2 && (await ver(P, g.id)).estado === 'fila' && (await ver(P, a.id)).estado === 'fila', r);
  await poll(P, K2); await res(P, K2, g.id, 'pergunta');
  r = await F(P, 'responder_tarefa_pc', { texto: 'opção 2' });
  conf('responder sem id: nada enviado', r.erro && (await ver(P, g.id)).estado === 'pergunta', r);
  r = await F(P, 'ver_tarefa_pc', {});
  conf('ver sem id: lista em vez de "a última"', r.erro && Array.isArray(r.tarefas), r);
  r = await F(P, 'cancelar_tarefa_pc', { id: a.id });
  conf('cancelar com id: cancela só aquela', r.ok && (await ver(P, a.id)).estado === 'cancelada' && (await ver(P, g.id)).estado === 'pergunta', r);
  r = await F(P, 'responder_tarefa_pc', { id: a.id, texto: 'oi' });
  conf('responder em tarefa cancelada: recusa (não volta a rodar)', r.erro && (await ver(P, a.id)).estado === 'cancelada', r);
  r = await F(P, 'responder_tarefa_pc', { id: g.id, texto: 'opção 2' });
  conf('responder com id: vai para o PC da tarefa', ids(await poll(P, K2)).includes('continuar:' + g.id) && ids(await poll(P, K1)).every(x => !x.includes(g.id)), r);
}
{ // 4 trava de tela continua valendo
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: true }); const b = await T(P, { texto: 'B', pc: L, tela: true });
  const c = await T(P, { texto: 'C', pc: L, tela: false }); const g = await T(P, { texto: 'G', pc: G, tela: true });
  conf('2ª de tela espera; arquivos e outro PC não esperam', !!b.esperando_tela && !c.esperando_tela && !g.esperando_tela && !/PC livre/.test(b.aviso || ''), b);
  const p = ids(await poll(P, K1));
  conf('PC recebe A e C, não B', p.includes('nova:' + a.id) && p.includes('nova:' + c.id) && !p.includes('nova:' + b.id), p);
  await res(P, K1, a.id);
  conf('A acabou: B sai sozinha', ids(await poll(P, K1)).includes('nova:' + b.id));
}
{ // 5 tarefa viva e calada não perde a tela (sinal de vida pelos ids)
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: true }); await poll(P, K1, { ids: [] }); await and(P, K1, a.id);
  const b = await T(P, { texto: 'B', pc: L, tela: true });
  for (let i = 0; i < 5; i++) { off += 10 * 60e3; await poll(P, K1, { ids: [a.id] }); }
  conf('A calada 50 min mas viva (ids): B continua esperando', (await ver(P, b.id)).esperando_tela && (await ver(P, a.id)).estado === 'rodando');
  off = 0;
  const P2 = await setup();   // agente antigo (sem ids): 21 min calada não solta mais (antes soltava aos 20)
  const a2 = await T(P2, { texto: 'A', pc: L, tela: true }); await poll(P2, K1); await and(P2, K1, a2.id);
  const b2 = await T(P2, { texto: 'B', pc: L, tela: true }); off += 21 * 60e3;
  conf('agente antigo: 21 min calada ainda segura a tela', ids(await poll(P2, K1)).length === 0);
  off += 30 * 60e3;
  conf('agente antigo: 51 min sem nenhum sinal solta (tarefa morta)', ids(await poll(P2, K1)).includes('nova:' + b2.id));
  off = 0;
}
{ // 6 PC desligado: a dona da vez não vence
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: true }); off += 61 * 60e3;
  const b = await T(P, { texto: 'B', pc: L, tela: true });
  const p = ids(await poll(P, K1));
  conf('PC desligado 61 min: B espera e o PC recebe só A ao ligar', !!b.esperando_tela && p.length === 1 && p[0] === 'nova:' + a.id, p);
  off += 6 * 60 * 60e3;
  const c = await T(P, { texto: 'C', pc: G, tela: false }); off += 6.1 * 60 * 60e3;
  await poll(P, K2);
  conf('guardada mais de 6 h: vira erro com explicação (não fica "fila" para sempre)', (await ver(P, c.id)).estado === 'erro');
  off = 0;
}
{ // 7 entrega perdida e conector reiniciado
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: true });
  await poll(P, K1, { ids: [] });                  // entregue... mas a resposta se perdeu no caminho
  off += 60e3; let p = ids(await poll(P, K1, { ids: [] }));
  conf('1 min depois: ainda não reentrega (pode estar chegando)', p.length === 0, p);
  off += 40e3; await poll(P, K1, { ids: [] }); p = ids(await poll(P, K1, { ids: [] }));
  const v = await ver(P, a.id);
  conf('PC diz que não tem a tarefa: o servidor manda de novo', (v.andamento || []).some(x => /de novo/.test(x)), v);
  await and(P, K1, a.id);                          // agora começou
  off += 4 * 60e3; await poll(P, K1, { ids: [] }); // conector reiniciou: não tem mais a tarefa
  const b = await T(P, { texto: 'B', pc: L, tela: true });
  conf('rodando e o PC não tem mais: vira erro e solta a tela', (await ver(P, a.id)).estado === 'erro' && !b.esperando_tela, await ver(P, a.id));
  off = 0;
  const P2 = await setup();
  const g = await T(P2, { texto: 'G', pc: G, tela: false }); await poll(P2, K2, { ids: [] }); await res(P2, K2, g.id, 'pergunta');
  await F(P2, 'responder_tarefa_pc', { id: g.id, texto: 'sim' }); await poll(P2, K2, { ids: [] });
  off += 100e3; await poll(P2, K2, { ids: [] });
  p = ids(await poll(P2, K2, { ids: [] }));
  conf('continuação perdida: também é mandada de novo', (await ver(P2, g.id)).andamento.some(x => /de novo/.test(x)));
  off = 0;
}
{ // 8 esquecer um PC
  const P = await setup();
  const g = await T(P, { texto: 'G velha', pc: G, tela: false });
  let r = await F(P, 'ajustar_pc', { pc: G, esquecer: true });
  conf('esquecer: fecha as tarefas abertas daquele PC', (await ver(P, g.id)).estado === 'cancelada', r);
  r = await ligar(P, K3, 'PC-NOVO', 'PC do escritório');
  const novoPc = (await P.pcs()).find(p => p.apelido === 'PC do escritório');
  conf('PC novo ganha id novo e não recebe tarefa do esquecido', novoPc.id !== 'pc2' && ids(await poll(P, K3)).length === 0, novoPc);
}
{ // 9 mesmo nome do Windows
  const P = await setup();
  await poll(P, K1);                                // notebook online
  let r0 = await P.rota(req({ chave: K3, nome: 'Leonardo' }), '/pc/registrar', U);
  let r = await F(P, 'ligar_pc', { codigo: r0.o.codigo, apelido: 'PC 3' });
  conf('mesmo nome de um PC online: não troca a chave calado', r.erro && (await P.pcs()).length === 2 && ids(await poll(P, K1)).length === 0, r);
  r = await F(P, 'ligar_pc', { codigo: r0.o.codigo, apelido: 'PC 3', novo: true });
  conf('novo = true: entra como outro PC e o notebook continua valendo', r.ok && (await P.pcs()).length === 3 && (await poll(P, K1)).st === 200, r);
  const P2 = await setup();                         // notebook offline, chave nova = mesmo PC
  r = await ligar(P2, K3, 'Leonardo');
  conf('mesmo nome com o PC offline: troca a chave e mantém o apelido', r.ok && (await P2.pcs()).length === 2 && r.pc.startsWith(L), r);
}
{ // 10 isolamento entre PCs e lista grande
  const P = await setup();
  const a = await T(P, { texto: 'A', pc: L, tela: false });
  conf('PC Gabriela não mexe em tarefa do notebook', (await and(P, K2, a.id)).st === 404 && (await res(P, K2, a.id)).st === 404 && ids(await poll(P, K2)).length === 0);
  await poll(P, K1); await and(P, K1, a.id);
  for (let i = 0; i < 70; i++) { const t = await T(P, { texto: 't' + i, pc: G, tela: false }); await poll(P, K2); await res(P, K2, t.id); }
  conf('mais de 60 tarefas: a que ainda roda não some da lista', (await ver(P, a.id)).estado === 'rodando' && (await P.tarefas()).length <= 61);
}
{ // 11 crédito (agente 1.8.0): tela e peso vão ao PC, gasto, previsão, reaproveitar, conferida magra
  const P = await setup();
  const a = await T(P, { texto: 'lista os arquivos', pc: L, tela: false, peso: 'leve', origem: 'celular' });
  let r = await poll(P, K1);
  const it = r.o.tarefas.find(x => x.id === a.id);
  conf('o PC recebe tela=false e peso=leve', it && it.tela === false && it.peso === 'leve', it);
  const b = await T(P, { texto: 'sobe o produto', pc: L, tela: true });
  r = await poll(P, K1);
  const itb = r.o.tarefas.find(x => x.id === b.id);
  conf('tarefa sem peso vai como normal e com tela=true', itb && itb.tela === true && itb.peso === 'normal', itb);
  await P.rota(req({ id: a.id, estado: 'feita', resposta: 'ok', gasto: { custo_usd: 0.25, rodadas: 4, entrada: 100, saida: 50, cache_novo: 1000, cache_lido: 5000, modelo: 'sonnet' } }, K1), '/pc/resultado', U);
  await P.rota(req({ id: b.id, estado: 'feita', resposta: 'ok', gasto: { custo_usd: 1.5, rodadas: 30, entrada: 10, saida: 10, cache_novo: 10, cache_lido: 10, modelo: 'opus' } }, K1), '/pc/resultado', U);
  const v = await ver(P, a.id);
  conf('a tarefa feita mostra o gasto', v.gasto && v.gasto.custo_usd === 0.25 && v.gasto.tokens === 6150, v.gasto);
  const gst = await F(P, 'gasto_pc', {});
  conf('gasto_pc soma por dia, por tipo e mostra a mais cara primeiro',
    gst.total && gst.total.n === 2 && Math.abs(gst.total.custo - 1.75) < 1e-9 && gst.mais_caras[0].id === b.id &&
    gst.por_grupo.some(g => /tipo: tela/.test(g.grupo)) && gst.por_grupo.some(g => /arquivos\/leve/.test(g.grupo)), gst);
  r = await T(P, { texto: 'lista os arquivos', pc: L, tela: false, reaproveitar: true });
  conf('reaproveitar: mesmo pedido feito há pouco volta sem rodar de novo', r.id === a.id && /Reaproveitada/.test(r.aviso || ''), r);
  r = await T(P, { texto: 'lista os arquivos', pc: G, tela: false, reaproveitar: true });
  conf('reaproveitar não pega tarefa de OUTRO PC', r.id !== a.id && !/Reaproveitada/.test(r.aviso || ''), r);
  r = await T(P, { texto: 'lista os arquivos', pc: L, tela: false });
  conf('sem reaproveitar: roda de novo', r.id !== a.id, r);
  // previsão: 3 tarefas parecidas de ~10 min
  const P2 = await setup();
  for (let i = 0; i < 3; i++) { const t = await T(P2, { texto: 'x' + i, pc: L, tela: false }); await poll(P2, K1); off += 10 * 60e3; await res(P2, K1, t.id); }
  const c = await T(P2, { texto: 'nova', pc: L, tela: false }); await poll(P2, K1); await and(P2, K1, c.id); off += 4 * 60e3;
  const vc = await ver(P2, c.id);
  conf('rodando: resposta magra (sem o pedido inteiro) e com previsão', /falta ~6 min/.test(vc.previsao || '') && !('criada' in vc), vc);
  off = 0;
}
console.log(`\nServidor do conector do PC: ${ok} de ${total} certos (${Math.round(100 * ok / total)}%)`);
process.exit(ok === total ? 0 : 1);
