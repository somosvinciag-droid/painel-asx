#!/usr/bin/env python3
"""Baixa os arquivos do Microsoft Global ML Building Footprints (ODbL) que cobrem a caixa em volta de (LAT0, LON0).

Uso: python3 -I baixar_ms.py <pasta_saida>
O índice (dataset-links.csv) diz o arquivo de cada quadkey de nível 9.
"""
import csv
import io
import math
import os
import sys
import urllib.request

LAT0 = float(os.environ.get("LAT0", "-21.977"))
LON0 = float(os.environ.get("LON0", "-44.932"))
R = float(os.environ.get("R_PREDIOS", "1700")) + 100
INDICE = "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv"


def quadkey(lat, lon, z=9):
    s = math.sin(math.radians(lat))
    x = (lon + 180) / 360
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    n = 1 << z
    tx = min(n - 1, max(0, int(x * n)))
    ty = min(n - 1, max(0, int(y * n)))
    q = ""
    for i in range(z, 0, -1):
        d = 0
        m = 1 << (i - 1)
        if tx & m:
            d += 1
        if ty & m:
            d += 2
        q += str(d)
    return q


def main():
    pasta = sys.argv[1]
    os.makedirs(pasta, exist_ok=True)
    dlat = R / 111195.0
    dlon = R / (111195.0 * math.cos(math.radians(LAT0)))
    qks = {quadkey(LAT0 + a * dlat, LON0 + b * dlon) for a in (-1, 0, 1) for b in (-1, 0, 1)}
    print("quadkeys:", sorted(qks))
    txt = urllib.request.urlopen(INDICE, timeout=120).read().decode()
    linhas = [r for r in csv.DictReader(io.StringIO(txt)) if r.get("QuadKey") in qks]
    print("arquivos:", [(r["Location"], r["QuadKey"], r.get("Size")) for r in linhas])
    for i, r in enumerate(linhas):
        destino = os.path.join(pasta, f"ms_{r['QuadKey']}_{i}.csv.gz")
        urllib.request.urlretrieve(r["Url"], destino)
        print(destino, os.path.getsize(destino))


if __name__ == "__main__":
    main()
