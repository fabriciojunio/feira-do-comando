import sys
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import metricas_do_programa as metricas  # noqa: E402

AGORA = datetime(2026, 10, 30, 12, 0, tzinfo=timezone.utc)
CABECALHO = "repositorio,id,origem,severidade,titulo,aberto_em,fechado_em,desfecho,evidencia\n"


def registro(*linhas: str) -> Path:
    arquivo = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
    arquivo.write(CABECALHO + "".join(linha + "\n" for linha in linhas))
    arquivo.close()
    return Path(arquivo.name)


class MetricasDoProgramaTest(unittest.TestCase):
    def test_tempo_ate_corrigir_e_prazo_por_severidade(self):
        achados = metricas.ler_achados(registro(
            "a,1,osv,critical,x,2026-10-01T00:00:00Z,2026-10-05T00:00:00Z,corrigido,pr",
            "a,2,osv,critical,x,2026-10-01T00:00:00Z,2026-10-21T00:00:00Z,corrigido,pr",
        ))
        texto = metricas.relatorio(achados, [], AGORA)
        # 4 e 20 dias: mediana 12, metade dentro dos 7 dias da crítica.
        self.assertIn("| crítica | 2 | 12,0 | 12,0 | 50% |", texto)

    def test_aberto_com_prazo_estourado(self):
        achados = metricas.ler_achados(registro(
            "a,1,codeql,high,x,2026-09-01T12:00:00Z,,aberto,-",
            "a,2,codeql,high,x,2026-10-20T12:00:00Z,,aberto,-",
        ))
        texto = metricas.relatorio(achados, [], AGORA)
        self.assertIn("| alta | 2 | 59,0 | 1 |", texto)

    def test_falso_positivo_entra_na_taxa_e_nao_no_tempo_de_correcao(self):
        achados = metricas.ler_achados(registro(
            "a,1,codeql,high,x,2026-10-28T00:00:00Z,2026-10-30T00:00:00Z,falso_positivo,justificativa",
            "a,2,codeql,low,x,2026-10-28T00:00:00Z,2026-10-29T00:00:00Z,corrigido,pr",
        ))
        texto = metricas.relatorio(achados, [], AGORA)
        self.assertIn("1 de 2 achados (50%) eram falsos positivos", texto)
        self.assertIn("| alta | 0 | - | - | - |", texto)

    def test_severidades_das_ferramentas_viram_as_quatro_do_prazo(self):
        achados = metricas.ler_achados(registro(
            "a,1,codeql,note,x,2026-10-28T00:00:00Z,,aberto,-",
            "a,2,dc,warning,x,2026-10-28T00:00:00Z,,aberto,-",
        ))
        self.assertEqual({a.severidade for a in achados}, {"baixa"})

    def test_registro_inconsistente_e_recusado(self):
        with self.assertRaisesRegex(ValueError, "linha 2"):
            metricas.ler_achados(registro("a,1,osv,high,x,2026-10-01T00:00:00Z,,corrigido,pr"))
        with self.assertRaisesRegex(ValueError, "linha 2"):
            metricas.ler_achados(registro("a,1,osv,gravissima,x,2026-10-01T00:00:00Z,,aberto,-"))

    def test_situacao_das_excecoes(self):
        hoje = date(2026, 10, 30)
        self.assertEqual(metricas.situacao_do_aceite(date(2026, 10, 1), hoje), "vencido")
        self.assertEqual(metricas.situacao_do_aceite(date(2026, 11, 20), hoje), "vence em até 30 dias")
        self.assertEqual(metricas.situacao_do_aceite(date(2027, 1, 28), hoje), "vigente")
        self.assertEqual(metricas.situacao_do_aceite(None, hoje), "sem prazo")

    def test_le_aceites_do_osv_scanner(self):
        arquivo = tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False, encoding="utf-8")
        arquivo.write('[[IgnoredVulns]]\nid = "GHSA-x"\nignoreUntil = 2026-10-01\nreason = "motivo"\n')
        arquivo.close()
        aceites = metricas.ler_aceites([("feira", Path(arquivo.name))])
        texto = metricas.relatorio([], aceites, AGORA)
        self.assertIn("| feira | GHSA-x | 01/10/2026 | vencido |", texto)
        self.assertIn("1 exceção(ões) vencida(s)", texto)


if __name__ == "__main__":
    unittest.main()
