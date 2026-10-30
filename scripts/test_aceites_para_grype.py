import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import aceites_para_grype as conversor  # noqa: E402

ACEITES = [
    {"id": "GHSA-j9f9-w8pj-32f8", "ignoreUntil": date(2027, 1, 28), "reason": "exige SSE com view"},
    {"id": "GHSA-velho-0000-0000", "ignoreUntil": date(2026, 1, 1), "reason": "prazo vencido"},
]


class AceitesParaGrypeTest(unittest.TestCase):
    def test_aceite_vencido_fica_de_fora(self):
        vigentes = conversor.vigentes(ACEITES, date(2026, 10, 29))
        self.assertEqual([a["id"] for a in vigentes], ["GHSA-j9f9-w8pj-32f8"])

    def test_configuracao_lista_o_id_e_o_motivo(self):
        texto = conversor.configuracao(conversor.vigentes(ACEITES, date(2026, 10, 29)))
        self.assertIn('- vulnerability: "GHSA-j9f9-w8pj-32f8"', texto)
        self.assertIn("exige SSE com view", texto)
        self.assertNotIn("GHSA-velho", texto)

    def test_sem_aceite_vigente_a_configuracao_nao_ignora_nada(self):
        self.assertIn("ignore: []", conversor.configuracao([]))


if __name__ == "__main__":
    unittest.main()
