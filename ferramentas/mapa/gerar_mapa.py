#!/usr/bin/env python3
"""
Gera o pacote do mapa próprio do Painel ASX (regiao.bin) a partir de um recorte do OpenStreetMap (.osm.pbf/.osm).

Uso:  python3 gerar_mapa.py <entrada.osm.pbf> <saida/regiao.bin> <versao> [lat0 lon0]

Um arquivo só, lido pela central com memória mapeada (MapaDados.kt). Tudo little-endian. Formato v1:
  cabeçalho (256 bytes) | nós | primeira aresta de cada nó | arestas | vias | pontos | grade de vias |
  prédios (grade, índices, dados) | nomes | lugares | ruas (índice de busca)

Coordenadas: decímetros numa projeção plana em volta de (lat0, lon0):
  x = (lon - lon0) * M * cos(lat0) * 10 ; y = (lat - lat0) * M * 10 ; M = 111195.08 m por grau.
Dados © colaboradores do OpenStreetMap (ODbL).
"""
import json
import math
import os
import struct
import sys
from array import array

import osmium

M = 6371008.8 * math.pi / 180.0
CELULA_DM = 5000  # 500 m

CLASSES = {
    "motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4,
    "unclassified": 5, "residential": 6, "living_street": 7,
    "motorway_link": 8, "trunk_link": 9, "primary_link": 10, "secondary_link": 11, "tertiary_link": 12,
    "road": 5,
}
VELOCIDADE = {0: 100, 1: 90, 2: 80, 3: 70, 4: 60, 5: 45, 6: 30, 7: 15, 8: 60, 9: 50, 10: 45, 11: 40, 12: 35}
TERRA = {"unpaved", "dirt", "gravel", "ground", "earth", "sand", "mud", "grass", "fine_gravel", "pebblestone", "compacted"}
LUGARES = {"city": 0, "town": 1, "village": 2, "suburb": 3, "neighbourhood": 4, "quarter": 4, "hamlet": 5}

F_ROTATORIA, F_IDA, F_VOLTA, F_TERRA = 1, 2, 4, 8


class Leitor(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.vias = []        # (ids array('q'), lon array('d'), lat array('d'), classe, vel, flags_base, nome, ida, volta)
        self.predios = []     # (id, [(lon, lat)], altura)
        self.lugares = []     # (lon, lat, nome, tipo)
        self.uso = {}         # id do nó -> quantas vezes aparece em vias (pontas contam 2)

    def node(self, n):
        p = n.tags.get("place")
        if p in LUGARES and n.tags.get("name") and n.location.valid():
            self.lugares.append((n.location.lon, n.location.lat, n.tags.get("name"), LUGARES[p]))

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw is not None:
            self.via(w, hw, t)
        b = t.get("building")
        if b is not None and b != "no":
            self.predio(w, t)

    def via(self, w, hw, t):
        if hw not in CLASSES:
            return
        if t.get("area") == "yes":
            return
        for k in ("access", "motor_vehicle", "motorcar", "vehicle"):
            if t.get(k) in ("no", "private", "agricultural", "forestry", "delivery"):
                return
        try:
            nos = [(nd.ref, nd.location.lon, nd.location.lat) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(nos) < 2:
            return
        classe = CLASSES[hw]
        vel = VELOCIDADE[classe]
        ms = t.get("maxspeed", "")
        try:
            v = int("".join(ch for ch in ms.split(";")[0] if ch.isdigit()) or "0")
            if 10 <= v <= 130:
                vel = max(10, min(120, round(v * 0.85)))
        except ValueError:
            pass
        flags = 0
        if t.get("surface") in TERRA or (t.get("tracktype") or "") in ("grade3", "grade4", "grade5"):
            flags |= F_TERRA
            vel = min(vel, 35)
        rot = t.get("junction") in ("roundabout", "circular")
        if rot:
            flags |= F_ROTATORIA
        ow = t.get("oneway", "")
        ida, volta = True, True
        if ow in ("yes", "true", "1"):
            volta = False
        elif ow == "-1" or ow == "reverse":
            ida = False
        elif ow != "no" and (rot or hw == "motorway"):
            volta = False
        nome = t.get("name") or t.get("ref") or ""
        ids = array("q", (n[0] for n in nos))
        lon = array("d", (n[1] for n in nos))
        lat = array("d", (n[2] for n in nos))
        self.vias.append((ids, lon, lat, classe, vel, flags, nome, ida, volta))
        u = self.uso
        for i, nid in enumerate(ids):
            u[nid] = u.get(nid, 0) + (2 if i == 0 or i == len(ids) - 1 else 1)

    def predio(self, w, t):
        try:
            pts = [(nd.location.lon, nd.location.lat) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 4 or w.nodes[0].ref != w.nodes[-1].ref:
            return
        alt = None
        h = t.get("height")
        if h:
            try:
                alt = float(h.replace("m", "").replace(",", ".").strip())
            except ValueError:
                alt = None
        if alt is None and t.get("building:levels"):
            try:
                alt = float(t.get("building:levels").replace(",", ".")) * 3.0
            except ValueError:
                alt = None
        if alt is None:
            alt = 4.5 + (w.id % 8) * 0.5
        alt = max(2.0, min(250.0, alt))
        self.predios.append((w.id, pts[:-1], alt))


# ---------------------------------------------------------------- geometria

def area2(p):
    s = 0
    n = len(p)
    for i in range(n):
        x1, y1 = p[i]
        x2, y2 = p[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return s


def triangular(p):
    """Recorte de orelhas (polígono simples em sentido anti-horário). Devolve índices ou None."""
    n = len(p)
    if n == 3:
        return [0, 1, 2]
    idx = list(range(n))
    tris = []

    def dentro(ax, ay, bx, by, cx, cy, px, py):
        d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        neg = d1 < 0 or d2 < 0 or d3 < 0
        pos = d1 > 0 or d2 > 0 or d3 > 0
        return not (neg and pos)

    voltas = 0
    i = 0
    while len(idx) > 3 and voltas < len(idx) * 2:
        m = len(idx)
        a, b, c = idx[(i - 1) % m], idx[i % m], idx[(i + 1) % m]
        ax, ay = p[a]; bx, by = p[b]; cx, cy = p[c]
        cruz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        orelha = cruz > 0
        if orelha:
            for j in idx:
                if j in (a, b, c):
                    continue
                if dentro(ax, ay, bx, by, cx, cy, p[j][0], p[j][1]):
                    orelha = False
                    break
        if orelha:
            tris += [a, b, c]
            idx.pop(i % m)
            voltas = 0
            i = i % max(1, len(idx))
        else:
            i = (i + 1) % m
            voltas += 1
    if len(idx) == 3:
        tris += idx
        return tris
    # Polígono estranho (cruzado): leque, melhor do que nada.
    return [v for k in range(1, n - 1) for v in (0, k, k + 1)]


def main():
    entrada, saida, versao = sys.argv[1], sys.argv[2], int(sys.argv[3])
    h = Leitor()
    h.apply_file(entrada, locations=True, idx="flex_mem")
    print(f"lido: {len(h.vias)} vias, {len(h.predios)} prédios, {len(h.lugares)} lugares", flush=True)
    if not h.vias:
        raise SystemExit("nenhuma via de carro no recorte")

    if len(sys.argv) >= 6:
        lat0, lon0 = float(sys.argv[4]), float(sys.argv[5])
    else:
        lats = [la for v in h.vias for la in v[2]]
        lons = [lo for v in h.vias for lo in v[1]]
        lat0 = (min(lats) + max(lats)) / 2
        lon0 = (min(lons) + max(lons)) / 2
    cos0 = math.cos(math.radians(lat0))

    def px(lon):
        return int(round((lon - lon0) * M * cos0 * 10))

    def py(lat):
        return int(round((lat - lat0) * M * 10))

    def metros(lon1, lat1, lon2, lat2):
        f1, f2 = math.radians(lat1), math.radians(lat2)
        df = f2 - f1
        dl = math.radians(lon2 - lon1)
        a = math.sin(df / 2) ** 2 + math.cos(f1) * math.cos(f2) * math.sin(dl / 2) ** 2
        return 2 * 6371008.8 * math.asin(min(1.0, math.sqrt(a)))

    # ------------------------------------------------ rede: nós de cruzamento e vias entre eles
    uso = h.uso
    no_idx = {}
    nos_x, nos_y = array("i"), array("i")

    def no(nid, lon, lat):
        k = no_idx.get(nid)
        if k is None:
            k = len(nos_x)
            no_idx[nid] = k
            nos_x.append(px(lon)); nos_y.append(py(lat))
        return k

    nomes = {"": -1}
    lista_nomes = []

    def nome_idx(s):
        if s not in nomes:
            nomes[s] = len(lista_nomes)
            lista_nomes.append(s)
        return nomes[s]

    vias = []  # [a, b, pts_x, pts_y, comp_dm, nome, classe, vel, flags]
    for ids, lon, lat, classe, vel, flags, nome, ida, volta in h.vias:
        fl = flags | (F_IDA if ida else 0) | (F_VOLTA if volta else 0)
        ni = nome_idx(nome)
        ini = 0
        for i in range(1, len(ids)):
            if i == len(ids) - 1 or uso.get(ids[i], 0) >= 2:
                if ids[i] == ids[ini] and i - ini < 2:
                    ini = i
                    continue
                a = no(ids[ini], lon[ini], lat[ini])
                b = no(ids[i], lon[i], lat[i])
                xs = array("i", (px(lon[k]) for k in range(ini, i + 1)))
                ys = array("i", (py(lat[k]) for k in range(ini, i + 1)))
                comp = sum(metros(lon[k], lat[k], lon[k + 1], lat[k + 1]) for k in range(ini, i))
                if a != b or comp > 1:
                    vias.append([a, b, xs, ys, max(1, int(round(comp * 10))), ni, classe, vel, fl])
                ini = i
    print(f"rede: {len(nos_x)} nós, {len(vias)} vias", flush=True)

    # ------------------------------------------------ só os pedaços ligados ao resto (ilhas pequenas fora)
    pai = list(range(len(nos_x)))

    def raiz(k):
        while pai[k] != k:
            pai[k] = pai[pai[k]]
            k = pai[k]
        return k

    for v in vias:
        ra, rb = raiz(v[0]), raiz(v[1])
        if ra != rb:
            pai[ra] = rb
    tam = {}
    for k in range(len(nos_x)):
        r = raiz(k)
        tam[r] = tam.get(r, 0) + 1
    minimo = int(os.environ.get("MIN_COMPONENTE", "50"))
    manter = [tam[raiz(k)] >= minimo for k in range(len(nos_x))]
    novo = [-1] * len(nos_x)
    nx, ny = array("i"), array("i")
    for k in range(len(nos_x)):
        if manter[k]:
            novo[k] = len(nx)
            nx.append(nos_x[k]); ny.append(nos_y[k])
    vias = [v for v in vias if manter[v[0]]]
    for v in vias:
        v[0], v[1] = novo[v[0]], novo[v[1]]
    nos_x, nos_y = nx, ny
    nN = len(nos_x)
    print(f"rede ligada: {nN} nós, {len(vias)} vias", flush=True)

    # ------------------------------------------------ arestas (mão de direção) em ordem de nó
    arestas = []
    for i, v in enumerate(vias):
        if v[8] & F_IDA:
            arestas.append((v[0], v[1], i))
        if v[8] & F_VOLTA:
            arestas.append((v[1], v[0], i | 0x80000000))
    arestas.sort(key=lambda e: e[0])
    prim = array("i", [0] * (nN + 1))
    for e in arestas:
        prim[e[0] + 1] += 1
    for k in range(nN):
        prim[k + 1] += prim[k]

    # ------------------------------------------------ limites e grade
    todos_x = list(nos_x)
    todos_y = list(nos_y)
    min_x, max_x = min(todos_x) - CELULA_DM, max(todos_x) + CELULA_DM
    min_y, max_y = min(todos_y) - CELULA_DM, max(todos_y) + CELULA_DM
    gw = (max_x - min_x) // CELULA_DM + 1
    gh = (max_y - min_y) // CELULA_DM + 1
    ncel = gw * gh

    def cel(x, y):
        cx = min(gw - 1, max(0, (x - min_x) // CELULA_DM))
        cy = min(gh - 1, max(0, (y - min_y) // CELULA_DM))
        return cy * gw + cx

    grade = {}
    for i, v in enumerate(vias):
        xs, ys = v[2], v[3]
        vistas = set()
        for k in range(len(xs) - 1):
            x0, x1 = sorted((xs[k], xs[k + 1]))
            y0, y1 = sorted((ys[k], ys[k + 1]))
            for cx in range((x0 - min_x) // CELULA_DM, (x1 - min_x) // CELULA_DM + 1):
                for cy in range((y0 - min_y) // CELULA_DM, (y1 - min_y) // CELULA_DM + 1):
                    if 0 <= cx < gw and 0 <= cy < gh:
                        vistas.add(cy * gw + cx)
        for c in vistas:
            grade.setdefault(c, []).append(i)
    grade_ini = array("i", [0] * (ncel + 1))
    for c, l in grade.items():
        grade_ini[c + 1] = len(l)
    for c in range(ncel):
        grade_ini[c + 1] += grade_ini[c]
    grade_lista = array("i", [0] * grade_ini[ncel])
    for c, l in grade.items():
        grade_lista[grade_ini[c]:grade_ini[c] + len(l)] = array("i", l)

    # ------------------------------------------------ prédios
    pred = []  # (celula, bytes)
    for pid, pts, alt in h.predios:
        p = []
        for lon, lat in pts:
            q = (px(lon), py(lat))
            if not p or p[-1] != q:
                p.append(q)
        while len(p) > 1 and p[0] == p[-1]:
            p.pop()
        if len(p) < 3 or len(p) > 400:
            continue
        a = area2(p)
        if abs(a) < 2 * 400:  # menos de 4 m² (em dm² x2)
            continue
        if a < 0:
            p.reverse()
        tris = triangular(p)
        cx = sum(q[0] for q in p) // len(p)
        cy = sum(q[1] for q in p) // len(p)
        if not (min_x <= cx <= max_x and min_y <= cy <= max_y):
            continue
        b = bytearray(struct.pack("<HBBHH", len(p), int(round(alt)), 0, len(tris) // 3, 0))
        for q in p:
            b += struct.pack("<ii", q[0], q[1])
        b += struct.pack("<%dH" % len(tris), *tris)
        while len(b) % 4:
            b += b"\0"
        pred.append((cel(cx, cy), bytes(b)))
    pred.sort(key=lambda t: t[0])
    pred_cel = array("i", [0] * (ncel + 1))
    for c, _ in pred:
        pred_cel[c + 1] += 1
    for c in range(ncel):
        pred_cel[c + 1] += pred_cel[c]
    pred_off = array("i", [0])
    pred_dados = bytearray()
    for _, b in pred:
        pred_dados += b
        pred_off.append(len(pred_dados))
    print(f"prédios: {len(pred)}", flush=True)

    # ------------------------------------------------ lugares e ruas (busca)
    lug = []
    for lon, lat, nome, tipo in h.lugares:
        x, y = px(lon), py(lat)
        if min_x <= x <= max_x and min_y <= y <= max_y:
            lug.append((x, y, nome_idx(nome), tipo))
    G = 100000  # 10 km
    grade_cid = {}
    for i, (x, y, _, tipo) in enumerate(lug):
        if tipo <= 2:
            grade_cid.setdefault((x // G, y // G), []).append(i)

    def cidade_perto(x, y):
        melhor, md = -1, (25000 * 10) ** 2
        gx, gy = x // G, y // G
        for dx in (-1, 0, 1, 2, -2):
            for dy in (-1, 0, 1, 2, -2):
                for i in grade_cid.get((gx + dx, gy + dy), ()):
                    d = (lug[i][0] - x) ** 2 + (lug[i][1] - y) ** 2
                    # cidade "pesa" mais que vila (a rua de Caxambu não vira rua do povoado vizinho)
                    d = d / (1.0, 1.4, 2.5)[lug[i][3]]
                    if d < md:
                        melhor, md = i, d
        return melhor

    grupos = {}
    for v in vias:
        if v[5] < 0:
            continue
        xs, ys = v[2], v[3]
        k = len(xs) // 2
        mx, my = xs[k], ys[k]
        c = cidade_perto(mx, my)
        grupos.setdefault((v[5], c), []).append((mx, my))
    ruas = []
    for (ni, c), pts in grupos.items():
        sx = sum(p[0] for p in pts) / len(pts)
        sy = sum(p[1] for p in pts) / len(pts)
        rx, ry = min(pts, key=lambda p: (p[0] - sx) ** 2 + (p[1] - sy) ** 2)
        ruas.append((ni, c, rx, ry))
    ruas.sort()

    # ------------------------------------------------ escrever
    nomes_b = [s.encode("utf-8") for s in lista_nomes]
    nomes_off = array("i", [0])
    for s in nomes_b:
        nomes_off.append(nomes_off[-1] + len(s))
    nomes_dados = b"".join(nomes_b)

    pontos = array("i")
    vias_b = bytearray()
    for a, b, xs, ys, comp, ni, classe, vel, fl in vias:
        prim_p = len(pontos) // 2
        for k in range(len(xs)):
            pontos.append(xs[k]); pontos.append(ys[k])
        vias_b += struct.pack("<iiiiiHBBBxxx", a, b, prim_p, comp, ni, len(xs), classe, vel, fl)

    secoes = []

    def sec(b):
        if isinstance(b, array):
            b = b.tobytes()
        secoes.append(bytes(b))

    nos_b = array("i")
    for k in range(nN):
        nos_b.append(nos_x[k]); nos_b.append(nos_y[k])
    ar_b = array("I")
    for e in arestas:
        ar_b.append(e[1]); ar_b.append(e[2])
    lug_b = bytearray()
    for x, y, ni, tipo in lug:
        lug_b += struct.pack("<iiiBxxx", x, y, ni, tipo)
    ruas_b = bytearray()
    for ni, c, x, y in ruas:
        ruas_b += struct.pack("<iiii", ni, c, x, y)

    assert array("i").itemsize == 4 and sys.byteorder == "little"
    sec(nos_b); sec(prim); sec(ar_b); sec(vias_b); sec(pontos); sec(grade_ini); sec(grade_lista)
    sec(pred_cel); sec(pred_off); sec(pred_dados); sec(nomes_off)
    nd = bytearray(nomes_dados)
    while len(nd) % 4:
        nd += b"\0"
    sec(nd); sec(lug_b); sec(ruas_b)

    offs = []
    pos = 256
    for s in secoes:
        offs.append(pos)
        pos += len(s)
    cab = bytearray(b"PASXMAP1")
    cab += struct.pack("<ii", 1, versao)
    cab += struct.pack("<dd", lat0, lon0)
    cab += struct.pack("<iiii", min_x, min_y, max_x, max_y)
    cab += struct.pack("<iii", CELULA_DM, gw, gh)
    cab += struct.pack("<iiiiiiii", nN, len(arestas), len(vias), len(pontos) // 2, len(pred), len(lista_nomes), len(lug), len(ruas))
    cab += struct.pack("<%di" % len(offs), *offs)
    cab += struct.pack("<ii", len(grade_lista), pos)
    assert len(cab) <= 256, len(cab)
    cab += b"\0" * (256 - len(cab))
    with open(saida, "wb") as f:
        f.write(cab)
        for s in secoes:
            f.write(s)
    # contagem de prédios em caixas pedidas: CONTAR="Caxambu:-44.99,-22.03,-44.88,-21.93;Outra:..."
    contagem = {}
    for item in filter(None, os.environ.get("CONTAR", "").split(";")):
        nome, caixa = item.split(":")
        lo1, la1, lo2, la2 = (float(v) for v in caixa.split(","))
        n = 0
        for _, pts, _ in h.predios:
            lo = sum(q[0] for q in pts) / len(pts)
            la = sum(q[1] for q in pts) / len(pts)
            if lo1 <= lo <= lo2 and la1 <= la <= la2:
                n += 1
        contagem[nome] = n
    info = {
        "formato": 1, "versao": versao, "tamanho": pos, "lat0": lat0, "lon0": lon0,
        "nos": nN, "arestas": len(arestas), "vias": len(vias), "pontos": len(pontos) // 2,
        "predios": len(pred), "nomes": len(lista_nomes), "lugares": len(lug), "ruas": len(ruas),
        "predios_na_caixa": contagem,
    }
    with open(saida + ".json", "w") as f:
        json.dump(info, f, ensure_ascii=False)
    print(json.dumps(info, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
