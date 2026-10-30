"""Roda cada regra Sigma de verdade, como SQL, contra logs de exemplo.

Validar só a sintaxe não diz se a regra pega o que deveria. Aqui cada regra é
convertida pelo backend SQLite do pySigma e executada numa tabela com linhas no
formato exato que os serviços escrevem (Dados simulados). Cada linha declara
quais regras têm de pegá-la; as que não declaram nada são o ruído normal, que
nenhuma regra pode pegar.

Uso: python -m unittest deteccao/test_regras_sigma.py -v
"""

import json
import sqlite3
import unittest
from pathlib import Path

from sigma.backends.sqlite import sqliteBackend
from sigma.collection import SigmaCollection

PASTA = Path(__file__).parent


def carregar_logs() -> sqlite3.Connection:
    banco = sqlite3.connect(":memory:")
    banco.execute("CREATE TABLE logs (id INTEGER, service TEXT, level TEXT, message TEXT)")
    for linha in (PASTA / "amostras.jsonl").read_text(encoding="utf-8").splitlines():
        if linha.strip():
            log = json.loads(linha)
            banco.execute("INSERT INTO logs VALUES (?, ?, ?, ?)",
                          (log["id"], log["service"], log["level"], log["message"]))
    return banco


def esperados() -> dict[str, set[int]]:
    mapa: dict[str, set[int]] = {}
    for linha in (PASTA / "amostras.jsonl").read_text(encoding="utf-8").splitlines():
        if linha.strip():
            log = json.loads(linha)
            for regra in log["espera"]:
                mapa.setdefault(regra, set()).add(log["id"])
    return mapa


class RegrasSigmaTest(unittest.TestCase):
    def test_cada_regra_pega_exatamente_as_linhas_esperadas(self):
        banco = carregar_logs()
        mapa = esperados()
        regras = sorted((PASTA / "sigma").glob("*.yml"))
        self.assertGreaterEqual(len(regras), 4, "sumiram regras: o teste viraria decoração")
        for arquivo in regras:
            nome = arquivo.stem
            with self.subTest(regra=nome):
                consultas = sqliteBackend().convert(SigmaCollection.from_yaml(arquivo.read_text(encoding="utf-8")))
                self.assertEqual(len(consultas), 1)
                achados = {linha[0] for linha in banco.execute(consultas[0])}
                self.assertEqual(achados, mapa.get(nome, set()))

    def test_toda_regra_tem_amostra_e_runbook(self):
        runbooks = (PASTA / "runbooks.md").read_text(encoding="utf-8")
        mapa = esperados()
        for arquivo in (PASTA / "sigma").glob("*.yml"):
            with self.subTest(regra=arquivo.stem):
                self.assertIn(arquivo.stem, mapa, "regra sem nenhuma amostra que ela deva pegar")
                self.assertIn(f"## {arquivo.stem}", runbooks, "regra sem runbook")


if __name__ == "__main__":
    unittest.main()
