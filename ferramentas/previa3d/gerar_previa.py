#!/usr/bin/env python3
"""
Prévia do "mapa 3D estilo jogo de corrida" de Caxambu (só para aprovar o visual; NÃO vai para a central).

Uso:  python3 -I gerar_previa.py <recorte.osm.pbf> <dem.tif> <pasta_ms> <saida.json>

Junta, numa área em volta do centro de Caxambu:
  - ruas, parques, água e prédios do OpenStreetMap (© colaboradores do OpenStreetMap, ODbL);
  - relevo do Copernicus DEM GLO-30 (© DLR e Airbus, Copernicus, licença livre);
  - prédios do Microsoft Global ML Building Footprints (ODbL), com altura estimada quando falta;
  - uma rota de carro (caminho mais curto no grafo de ruas, respeitando mão única) com as curvas em português.

Coordenadas locais em DECÍMETROS (inteiros): x para leste, y para norte, em volta de (LAT0, LON0).
"""
import gzip
import heapq
import json
import math
import os
import sys
import unicodedata

import numpy as np
import osmium
from shapely.geometry import Polygon, LineString, Point
from shapely.strtree import STRtree
from shapely.ops import unary_union

LAT0 = float(os.environ.get("LAT0", "-21.977"))
LON0 = float(os.environ.get("LON0", "-44.932"))
R_PREDIOS = float(os.environ.get("R_PREDIOS", "1700"))   # metros (meia-largura da caixa dos prédios)
R_RUAS = float(os.environ.get("R_RUAS", "3000"))         # ruas e áreas (textura do chão)
R_DEM = float(os.environ.get("R_DEM", "3000"))           # relevo
PASSO_DEM = float(os.environ.get("PASSO_DEM", "30"))     # metros
ROTA = os.environ.get("ROTA", "").strip()                # opcional: "lat,lon;lat,lon;..."

M = 6371008.8 * math.pi / 180.0
KX = M * math.cos(math.radians(LAT0))
KY = M


def xy(lon, lat):
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


def lonlat(x, y):
    return (LON0 + x / KX, LAT0 + y / KY)


def dentro(x, y, r):
    return -r <= x <= r and -r <= y <= r


def sem_acento(s):
    return "".join(c for c in unicodedata.normalize("NFD", s or "") if unicodedata.category(c) != "Mn").lower()


CLASSES = {
    "motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4,
    "unclassified": 5, "residential": 6, "living_street": 7, "service": 8,
    "motorway_link": 1, "trunk_link": 1, "primary_link": 2, "secondary_link": 3, "tertiary_link": 4,
    "road": 5, "track": 9, "pedestrian": 10, "footway": 11, "path": 11, "steps": 11, "cycleway": 11,
}
DIRIGIVEL = {0, 1, 2, 3, 4, 5, 6, 7}

# tipos de área para a textura do chão
AREA_PARQUE, AREA_GRAMA, AREA_MATA, AREA_AGUA, AREA_PRACA = 1, 2, 3, 4, 5


def tipo_area(t):
    n = t.get("natural")
    lu = t.get("landuse")
    le = t.get("leisure")
    if n == "water" or lu in ("reservoir", "basin") or t.get("waterway") == "riverbank" or t.get("water"):
        return AREA_AGUA
    if n in ("wood", "scrub") or lu == "forest":
        return AREA_MATA
    if le in ("park", "garden", "nature_reserve"):
        return AREA_PARQUE
    if lu in ("grass", "meadow", "village_green", "recreation_ground") or n in ("grassland", "heath") or le in ("pitch", "playground"):
        return AREA_GRAMA
    if t.get("place") == "square" or (t.get("highway") == "pedestrian" and t.get("area") == "yes"):
        return AREA_PRACA
    return 0


class Leitor(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.vias = []      # dict(ids, pts, classe, nome, ida, volta, rot)
        self.predios = []   # (pts, altura|None, niveis|None, id)
        self.areas = []     # (tipo, aneis externos, aneis internos, nome)
        self.agua_linhas = []
        self.lugares = []   # (x, y, nome, tipo)

    def node(self, n):
        t = n.tags
        nome = t.get("name")
        if not nome or not n.location.valid():
            return
        x, y = xy(n.location.lon, n.location.lat)
        if not dentro(x, y, R_PREDIOS):
            return
        tipo = t.get("place") or t.get("amenity") or t.get("tourism") or t.get("leisure") or t.get("historic")
        if tipo:
            self.lugares.append((x, y, nome, tipo))

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw is not None and hw in CLASSES and t.get("area") != "yes":
            try:
                nos = [(nd.ref, nd.location.lon, nd.location.lat) for nd in w.nodes]
            except osmium.InvalidLocationError:
                return
            if len(nos) < 2:
                return
            pts = [xy(lo, la) for _, lo, la in nos]
            if not any(dentro(x, y, R_RUAS) for x, y in pts):
                return
            classe = CLASSES[hw]
            for k in ("access", "motor_vehicle", "motorcar", "vehicle"):
                if t.get(k) in ("no", "private", "agricultural", "forestry", "delivery"):
                    if classe in DIRIGIVEL:
                        classe = 8
            rot = t.get("junction") in ("roundabout", "circular")
            ow = t.get("oneway", "")
            ida, volta = True, True
            if ow in ("yes", "true", "1"):
                volta = False
            elif ow in ("-1", "reverse"):
                ida = False
            elif ow != "no" and (rot or hw == "motorway"):
                volta = False
            self.vias.append(dict(ids=[n[0] for n in nos], pts=pts, classe=classe,
                                  nome=t.get("name") or t.get("ref") or "", ida=ida, volta=volta, rot=rot,
                                  pontes=t.get("bridge") not in (None, "no"), terra=t.get("surface") in
                                  ("unpaved", "dirt", "gravel", "ground", "earth", "sand", "grass", "compacted")))
        ww = t.get("waterway")
        if ww in ("river", "stream", "canal", "ditch", "drain"):
            try:
                pts = [xy(nd.location.lon, nd.location.lat) for nd in w.nodes]
            except osmium.InvalidLocationError:
                return
            if any(dentro(x, y, R_RUAS) for x, y in pts):
                self.agua_linhas.append((ww, pts))

    def area(self, a):
        t = a.tags
        b = t.get("building")
        eh_predio = b is not None and b != "no"
        tipo = 0 if eh_predio else tipo_area(t)
        if not eh_predio and not tipo:
            return
        try:
            for outer in a.outer_rings():
                ext = [xy(n.lon, n.lat) for n in outer]
                if len(ext) < 4:
                    continue
                ints = [[xy(n.lon, n.lat) for n in inner] for inner in a.inner_rings(outer)]
                if eh_predio:
                    cx = sum(p[0] for p in ext) / len(ext)
                    cy = sum(p[1] for p in ext) / len(ext)
                    if not dentro(cx, cy, R_PREDIOS):
                        continue
                    alt = None
                    h = t.get("height")
                    if h:
                        try:
                            alt = float(h.replace("m", "").replace(",", ".").strip())
                        except ValueError:
                            alt = None
                    niv = None
                    if t.get("building:levels"):
                        try:
                            niv = float(t.get("building:levels").replace(",", "."))
                        except ValueError:
                            niv = None
                    self.predios.append((ext, alt, niv, a.orig_id()))
                else:
                    if not any(dentro(x, y, R_RUAS) for x, y in ext):
                        continue
                    self.areas.append((tipo, ext, ints, t.get("name") or ""))
        except osmium.InvalidLocationError:
            return


# ---------------------------------------------------------------- relevo (Copernicus DEM)

def ler_dem(caminho):
    import rasterio
    from rasterio.windows import from_bounds
    lo0, la0 = lonlat(-R_DEM - 200, -R_DEM - 200)
    lo1, la1 = lonlat(R_DEM + 200, R_DEM + 200)
    with rasterio.open(caminho) as src:
        print("DEM:", src.bounds, src.res, src.crs, file=sys.stderr)
        jan = from_bounds(lo0, la0, lo1, la1, src.transform).round_offsets().round_lengths()
        arr = src.read(1, window=jan).astype(np.float64)
        tr = src.window_transform(jan)
        res_lon, res_lat = src.res
    n = int(round(2 * R_DEM / PASSO_DEM)) + 1
    xs = np.linspace(-R_DEM, R_DEM, n)
    ys = np.linspace(R_DEM, -R_DEM, n)   # linha 0 = norte
    X, Y = np.meshgrid(xs, ys)
    LON = LON0 + X / KX
    LAT = LAT0 + Y / KY
    # índices fracionários no raster (centro do pixel)
    inv = ~tr
    col, lin = inv * (LON, LAT)
    col = col - 0.5
    lin = lin - 0.5
    c0 = np.clip(np.floor(col).astype(int), 0, arr.shape[1] - 2)
    l0 = np.clip(np.floor(lin).astype(int), 0, arr.shape[0] - 2)
    fc = np.clip(col - c0, 0, 1)
    fl = np.clip(lin - l0, 0, 1)
    h = (arr[l0, c0] * (1 - fc) * (1 - fl) + arr[l0, c0 + 1] * fc * (1 - fl)
         + arr[l0 + 1, c0] * (1 - fc) * fl + arr[l0 + 1, c0 + 1] * fc * fl)
    res_m = (res_lon * KX, res_lat * KY)
    return n, h, res_m


# ---------------------------------------------------------------- prédios da Microsoft

def ler_ms(pasta):
    polys = []
    alturas = []
    lo0, la0 = lonlat(-R_PREDIOS - 50, -R_PREDIOS - 50)
    lo1, la1 = lonlat(R_PREDIOS + 50, R_PREDIOS + 50)
    vistos = set()
    if not os.path.isdir(pasta):
        return polys, alturas
    for nome in sorted(os.listdir(pasta)):
        if not nome.endswith(".gz"):
            continue
        with gzip.open(os.path.join(pasta, nome), "rt") as f:
            for linha in f:
                linha = linha.strip()
                if not linha:
                    continue
                i = linha.find("[[[")
                if i < 0:
                    continue
                # filtro rápido pelo 1º ponto
                try:
                    j = linha.find("]", i)
                    lo, la = (float(v) for v in linha[i + 3:j].split(",")[:2])
                except ValueError:
                    continue
                if not (lo0 <= lo <= lo1 and la0 <= la <= la1):
                    continue
                ft = json.loads(linha)
                g = ft.get("geometry") or ft
                if g.get("type") != "Polygon":
                    continue
                anel = g["coordinates"][0]
                chave = (round(anel[0][0], 7), round(anel[0][1], 7), len(anel))
                if chave in vistos:
                    continue
                vistos.add(chave)
                pts = [xy(p[0], p[1]) for p in anel]
                props = ft.get("properties") or {}
                h = props.get("height", -1)
                try:
                    h = float(h)
                except (TypeError, ValueError):
                    h = -1
                polys.append(pts)
                alturas.append(h)
    return polys, alturas


def altura_estimada(area, semente):
    # 5 a 9 m pela área (casas pequenas baixas, galpões e prédios maiores mais altos), com variação fixa
    j = ((semente * 2654435761) % 1000) / 1000.0
    if area < 50:
        return 4.2 + j * 1.0
    if area < 120:
        return 5.0 + j * 1.6
    if area < 300:
        return 6.0 + j * 2.0
    if area < 800:
        return 7.0 + j * 2.5
    return 8.0 + j * 3.0


# ---------------------------------------------------------------- rota

def montar_grafo(vias):
    adj = {}
    pos = {}
    nome_aresta = {}
    rot_aresta = set()
    for vi, v in enumerate(vias):
        if v["classe"] not in DIRIGIVEL:
            continue
        ids, pts = v["ids"], v["pts"]
        for i in range(len(ids) - 1):
            a, b = ids[i], ids[i + 1]
            pos[a] = pts[i]
            pos[b] = pts[i + 1]
            d = math.dist(pts[i], pts[i + 1])
            if v["ida"]:
                adj.setdefault(a, []).append((b, d, vi))
            if v["volta"]:
                adj.setdefault(b, []).append((a, d, vi))
            adj.setdefault(a, adj.get(a, []))
            adj.setdefault(b, adj.get(b, []))
            nome_aresta[(a, b)] = vi
            nome_aresta[(b, a)] = vi
            if v["rot"]:
                rot_aresta.add((a, b))
    return adj, pos, nome_aresta, rot_aresta


def grau_nao_dirigido(adj):
    viz = {}
    for a, lst in adj.items():
        for b, _, _ in lst:
            viz.setdefault(a, set()).add(b)
            viz.setdefault(b, set()).add(a)
    return {k: len(v) for k, v in viz.items()}


def dijkstra(adj, pos, ini, fim, penal=None):
    dist = {ini: 0.0}
    ant = {}
    h = [(0.0, ini)]
    while h:
        d, u = heapq.heappop(h)
        if u == fim:
            break
        if d > dist.get(u, 1e18):
            continue
        for v, w, vi in adj.get(u, ()):
            # penaliza vias menores um pouco (rota "de carro", não por becos)
            nd = d + w * (penal(vi) if penal else 1.0)
            if nd < dist.get(v, 1e18):
                dist[v] = nd
                ant[v] = u
                heapq.heappush(h, (nd, v))
    if fim not in dist:
        return None
    cam = [fim]
    while cam[-1] != ini:
        cam.append(ant[cam[-1]])
    return cam[::-1]


def no_mais_perto(cands, pos, x, y):
    melhor, md = None, 1e18
    for n in cands:
        px, py = pos[n]
        d = (px - x) ** 2 + (py - y) ** 2
        if d < md:
            melhor, md = n, d
    return melhor, math.sqrt(md)


def tirar_idas_e_voltas(cam):
    mudou = True
    while mudou:
        mudou = False
        i = 1
        while i < len(cam) - 1:
            if cam[i - 1] == cam[i + 1]:
                del cam[i:i + 2]
                mudou = True
                i = max(1, i - 1)
            else:
                i += 1
    return cam


def rumo(p, q):
    return math.degrees(math.atan2(q[0] - p[0], q[1] - p[1]))  # 0 = norte, horário


def ponto_a_dist(pts, acum, s):
    # ponto na polilinha a s metros do início
    if s <= 0:
        return pts[0]
    if s >= acum[-1]:
        return pts[-1]
    lo, hi = 0, len(acum) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if acum[mid] <= s:
            lo = mid
        else:
            hi = mid
    f = (s - acum[lo]) / max(1e-9, acum[hi] - acum[lo])
    return (pts[lo][0] + (pts[hi][0] - pts[lo][0]) * f, pts[lo][1] + (pts[hi][1] - pts[lo][1]) * f)


def instrucoes(cam, pos, nome_aresta, rot_aresta, vias, grau):
    pts = [pos[n] for n in cam]
    acum = [0.0]
    for i in range(1, len(pts)):
        acum.append(acum[-1] + math.dist(pts[i - 1], pts[i]))
    nomes = []
    for i in range(len(cam) - 1):
        nomes.append(vias[nome_aresta[(cam[i], cam[i + 1])]]["nome"])
    nomes.append(nomes[-1] if nomes else "")
    ins = [dict(s=0, tipo="partida", dir=0, texto=f"Siga pela {nomes[0] or 'rua'}", nome=nomes[0])]
    i = 1
    while i < len(cam) - 1:
        n = cam[i]
        s = acum[i]
        nome_antes = nomes[i - 1]
        nome_depois = nomes[i]
        entra_rot = (cam[i], cam[i + 1]) in rot_aresta and (cam[i - 1], cam[i]) not in rot_aresta
        cruz = grau.get(n, 0) >= 3
        if not cruz and nome_antes == nome_depois:
            i += 1
            continue
        a = ponto_a_dist(pts, acum, s - 15)
        b = ponto_a_dist(pts, acum, s + 15)
        ang = (rumo(pts[i], b) - rumo(a, pts[i]) + 540) % 360 - 180
        if entra_rot:
            # saída da rotatória: conta quantas saídas até sair dela
            j = i
            saidas = 0
            while j < len(cam) - 1 and (cam[j], cam[j + 1]) in rot_aresta:
                j += 1
                if grau.get(cam[j], 0) >= 3 and j < len(cam) - 1 and (cam[j], cam[j + 1]) in rot_aresta:
                    saidas += 1
            nome_saida = nomes[j] if j < len(nomes) else ""
            ins.append(dict(s=round(s), tipo="rotatoria", dir=0,
                            texto=f"Na rotatória, pegue a {saidas + 1}ª saída" + (f" para a {nome_saida}" if nome_saida else ""),
                            nome=nome_saida))
            i = j + 1
            continue
        aa = abs(ang)
        lado = "direita" if ang > 0 else "esquerda"
        para = f" na {nome_depois}" if nome_depois else ""
        if aa < 28:
            if nome_antes != nome_depois and nome_depois:
                ins.append(dict(s=round(s), tipo="reto", dir=0, texto=f"Siga em frente{para}", nome=nome_depois))
        elif aa < 60:
            ins.append(dict(s=round(s), tipo="leve", dir=1 if ang > 0 else -1,
                            texto=f"Mantenha-se à {lado}{para}", nome=nome_depois))
        elif aa < 155:
            ins.append(dict(s=round(s), tipo="vire", dir=1 if ang > 0 else -1,
                            texto=f"Vire à {lado}{para}", nome=nome_depois))
        else:
            ins.append(dict(s=round(s), tipo="retorno", dir=1 if ang > 0 else -1,
                            texto=f"Faça o retorno{para}", nome=nome_depois))
        i += 1
    # junta instruções muito próximas (< 12 m): fica a mais forte
    final = []
    for x in ins:
        if final and x["s"] - final[-1]["s"] < 12 and x["tipo"] != "partida":
            if x["tipo"] in ("vire", "retorno", "rotatoria") or final[-1]["tipo"] in ("reto", "leve"):
                if final[-1]["tipo"] != "partida":
                    final[-1] = x
                    continue
            continue
        final.append(x)
    final.append(dict(s=round(acum[-1]), tipo="chegada", dir=0, texto="Você chegou ao destino", nome=nomes[-1]))
    return pts, acum, nomes, final


def calcular_rota(vias, areas, lugares):
    adj, pos, nome_aresta, rot_aresta = montar_grafo(vias)
    grau = grau_nao_dirigido(adj)
    # só nós com saída e entrada, dentro da área dos prédios
    cands = [n for n in adj if adj[n] and grau.get(n, 0) >= 3 and dentro(*pos[n], R_PREDIOS - 150)]
    classe_de = {vi: v["classe"] for vi, v in enumerate(vias)}

    def penal(vi):
        c = classe_de[vi]
        return {0: 0.9, 1: 0.9, 2: 0.92, 3: 0.95, 4: 0.97, 5: 1.0, 6: 1.05, 7: 1.4}.get(c, 1.5)

    alvos = []
    rotulos = []
    if ROTA:
        for par in ROTA.split(";"):
            la, lo = (float(v) for v in par.split(","))
            alvos.append(xy(lo, la))
            rotulos.append(par)
    else:
        # A: Rua Major Penha (meio da rua)
        mp = [p for v in vias if "major penha" in sem_acento(v["nome"]) for p in v["pts"]]
        if mp:
            mx = sum(p[0] for p in mp) / len(mp)
            my = sum(p[1] for p in mp) / len(mp)
            a = min(mp, key=lambda p: (p[0] - mx) ** 2 + (p[1] - my) ** 2)
            alvos.append(a)
            rotulos.append("Rua Major Penha")
        else:
            alvos.append((0.0, 0.0))
            rotulos.append("centro")
        # Parque das Águas: contorno pelos 4 lados
        pq = [ar for ar in areas if "parque das aguas" in sem_acento(ar[3])]
        if pq:
            pg = Polygon(max(pq, key=lambda ar: Polygon(ar[1]).area)[1])
            x0, y0, x1, y1 = pg.bounds
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            print(f"Parque das Águas: {x1 - x0:.0f} x {y1 - y0:.0f} m, centro ({cx:.0f},{cy:.0f})", file=sys.stderr)
            folga = 25
            lados = [(cx, y1 + folga, "norte"), (x1 + folga, cy, "leste"), (cx, y0 - folga, "sul"), (x0 - folga, cy, "oeste")]
            # começa pelo lado mais perto do ponto A, no sentido horário
            k = min(range(4), key=lambda i: math.dist(lados[i][:2], alvos[0]))
            for d in range(4):
                lx, ly, nm = lados[(k + d) % 4]
                alvos.append((lx, ly))
                rotulos.append("Parque das Águas (" + nm + ")")
        else:
            print("Parque das Águas não achado no OSM; uso um ponto a 700 m", file=sys.stderr)
            alvos.append((alvos[0][0] + 700, alvos[0][1] + 200))
            rotulos.append("ponto a 700 m")
        alvos.append(alvos[0])
        rotulos.append(rotulos[0])
    nos = []
    for (x, y), r in zip(alvos, rotulos):
        n, d = no_mais_perto(cands, pos, x, y)
        nos.append(n)
        print(f"  alvo {r}: nó {n} a {d:.0f} m", file=sys.stderr)
    cam = [nos[0]]
    for a, b in zip(nos, nos[1:]):
        if a == b:
            continue
        p = dijkstra(adj, pos, a, b, penal)
        if p is None:
            print(f"  sem caminho entre {a} e {b}; pulo", file=sys.stderr)
            continue
        cam += p[1:]
    cam = tirar_idas_e_voltas(cam)
    pts, acum, nomes, ins = instrucoes(cam, pos, nome_aresta, rot_aresta, vias, grau)
    return pts, acum, nomes, ins


# ---------------------------------------------------------------- principal

def dm(v):
    return int(round(v * 10))


def achatar(pts):
    out = []
    for x, y in pts:
        out += [dm(x), dm(y)]
    return out


def main():
    osm, dem, pasta_ms, saida = sys.argv[1:5]
    ld = Leitor()
    ld.apply_file(osm, locations=True)
    print(f"OSM: {len(ld.vias)} vias, {len(ld.predios)} prédios, {len(ld.areas)} áreas, {len(ld.lugares)} lugares", file=sys.stderr)

    # ---- relevo
    n, h, res_m = ler_dem(dem)
    hmin = float(h.min())
    hmax = float(h.max())
    print(f"DEM: {n}x{n} pontos de {PASSO_DEM} m, altitude {hmin:.1f} a {hmax:.1f} m (pixel original {res_m[0]:.1f}x{res_m[1]:.1f} m)", file=sys.stderr)
    hq = np.round((h - hmin) * 10).astype(int)

    # ---- ruas (para o chão) e linhas d'água
    nomes = []
    idx_nome = {}

    def nid(s):
        if s not in idx_nome:
            idx_nome[s] = len(nomes)
            nomes.append(s)
        return idx_nome[s]

    ruas = []
    for v in ld.vias:
        ruas.append([v["classe"], nid(v["nome"]), 1 if (v["ida"] != v["volta"]) else 0, 1 if v["terra"] else 0, achatar(v["pts"])])
    rios = [[nid(""), achatar(p)] for _, p in ld.agua_linhas]

    # ---- áreas
    areas = []
    for tipo, ext, ints, nome in ld.areas:
        areas.append([tipo, nid(nome), achatar(ext[:-1]), [achatar(i[:-1]) for i in ints]])

    # ---- prédios: OSM + Microsoft (sem duplicar), fora das ruas
    osm_polys = []
    for ext, alt, niv, oid in ld.predios:
        try:
            p = Polygon(ext).buffer(0)
        except Exception:
            continue
        if p.is_empty or p.area < 8:
            continue
        if p.geom_type == "MultiPolygon":
            p = max(p.geoms, key=lambda g: g.area)
        osm_polys.append((p, alt, niv, oid))
    ms_polys, ms_alt = ler_ms(pasta_ms)
    print(f"Microsoft: {len(ms_polys)} prédios na caixa", file=sys.stderr)

    arv_osm = STRtree([p for p, *_ in osm_polys]) if osm_polys else None
    # faixa das ruas (eixo + meia-largura) para tirar prédios "no meio da rua" (erros do ML)
    meia = {0: 6, 1: 5.5, 2: 5, 3: 4.5, 4: 4, 5: 3.5, 6: 3.2, 7: 2.5}
    faixas = []
    for v in ld.vias:
        if v["classe"] in meia and len(v["pts"]) >= 2:
            faixas.append(LineString(v["pts"]).buffer(meia[v["classe"]], cap_style=2))
    arv_rua = STRtree(faixas) if faixas else None

    predios = []
    cont = dict(osm=0, ms=0, ms_dup=0, ms_rua=0, ms_altura=0, osm_altura=0, estimada=0)
    for p, alt, niv, oid in osm_polys:
        if alt is None and niv:
            alt = niv * 3.0
        if alt is not None:
            cont["osm_altura"] += 1
            fonte = 1
        else:
            alt = altura_estimada(p.area, oid)
            cont["estimada"] += 1
            fonte = 0
        predios.append((p, max(2.5, min(120.0, alt)), fonte))
        cont["osm"] += 1
    for k, (pts, hms) in enumerate(zip(ms_polys, ms_alt)):
        try:
            p = Polygon(pts).buffer(0)
        except Exception:
            continue
        if p.is_empty or p.area < 10:
            continue
        if p.geom_type == "MultiPolygon":
            p = max(p.geoms, key=lambda g: g.area)
        if arv_osm is not None:
            dup = False
            for j in arv_osm.query(p):
                q = osm_polys[int(j)][0]
                inter = p.intersection(q).area
                if inter > 0.3 * min(p.area, q.area):
                    dup = True
                    break
            if dup:
                cont["ms_dup"] += 1
                continue
        if arv_rua is not None:
            na_rua = 0.0
            cands = arv_rua.query(p)
            if len(cands):
                u = unary_union([faixas[int(j)] for j in cands])
                na_rua = p.intersection(u).area
            if na_rua > 0.25 * p.area:
                cont["ms_rua"] += 1
                continue
        if hms is not None and hms > 0:
            alt = hms
            cont["ms_altura"] += 1
            fonte = 2
        else:
            alt = altura_estimada(p.area, k + 7)
            cont["estimada"] += 1
            fonte = 0
        predios.append((p, max(2.5, min(120.0, alt)), fonte))
        cont["ms"] += 1

    pred_out = []
    for p, alt, fonte in predios:
        p = p.simplify(0.25, preserve_topology=True)
        if p.is_empty or p.geom_type != "Polygon":
            continue
        anel = list(p.exterior.coords)[:-1]
        if len(anel) < 3:
            continue
        # sentido anti-horário (visto de cima, y para o norte)
        if Polygon(anel).exterior.is_ccw is False:
            anel = anel[::-1]
        pred_out.append([int(round(alt * 10)), fonte, achatar(anel)])
    print(f"Prédios: {cont}", file=sys.stderr)

    # ---- rota
    rpts, racum, rnomes, ins = calcular_rota(ld.vias, ld.areas, ld.lugares)
    print(f"Rota: {racum[-1]:.0f} m, {len(rpts)} pontos, {len(ins)} instruções", file=sys.stderr)
    for x in ins:
        print(f"  {x['s']:5d} m  {x['texto']}", file=sys.stderr)
    rota = dict(p=achatar(rpts), n=[nid(s) for s in rnomes], ins=ins, comprimento=round(racum[-1]))

    lugares = [[dm(x), dm(y), nome, tipo] for x, y, nome, tipo in ld.lugares]
    dados = dict(
        versao=1,
        origem=dict(lat=LAT0, lon=LON0),
        unidade="dm",
        dem=dict(n=n, passo=PASSO_DEM, r=R_DEM, hmin=round(hmin, 1), hmax=round(hmax, 1), h=hq.flatten().tolist()),
        nomes=nomes,
        ruas=ruas,
        rios=rios,
        areas=areas,
        predios=pred_out,
        rota=rota,
        lugares=lugares,
        contagem=dict(vias=len(ld.vias), vias_dirigiveis=sum(1 for v in ld.vias if v["classe"] in DIRIGIVEL),
                      areas=len(ld.areas), predios=len(pred_out), **cont,
                      altitude_min=round(hmin, 1), altitude_max=round(hmax, 1),
                      rota_m=round(racum[-1])),
        fontes=["© colaboradores do OpenStreetMap (ODbL)", "Copernicus DEM GLO-30 (© DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018, fornecido no âmbito do programa COPERNICUS pela ESA)",
                "Microsoft Global ML Building Footprints (ODbL)"],
    )
    with open(saida, "w") as f:
        json.dump(dados, f, ensure_ascii=False, separators=(",", ":"))
    print(json.dumps(dados["contagem"], ensure_ascii=False), file=sys.stderr)
    print(f"Saída: {os.path.getsize(saida) / 1e6:.2f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
