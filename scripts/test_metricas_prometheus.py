import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from metricas_do_programa import Achado  # noqa: E402
from metricas_prometheus import exposicao  # noqa: E402


def achado(severidade, desfecho, aberto, fechado):
    def data(texto):
        return datetime.fromisoformat(texto).replace(tzinfo=timezone.utc)
    return Achado("feira", severidade, data(aberto), data(fechado) if fechado else None, desfecho)


class Exportador(unittest.TestCase):
    def setUp(self):
        self.agora = datetime(2026, 11, 2, tzinfo=timezone.utc)
        self.achados = [
            # crítica corrigida fora do prazo de 7 dias (levou 10)
            achado("crítica", "corrigido", "2026-10-01T00:00:00", "2026-10-11T00:00:00"),
            # alta corrigida dentro do prazo de 30 dias (levou 5)
            achado("alta", "corrigido", "2026-10-01T00:00:00", "2026-10-06T00:00:00"),
            # média aberta há 100 dias, prazo de 90 estourado
            achado("média", "aberto", "2026-07-25T00:00:00", None),
            achado("baixa", "falso_positivo", "2026-10-01T00:00:00", "2026-10-02T00:00:00"),
        ]
        self.texto = exposicao(self.achados, [], self.agora)

    def test_rotulos_sem_acento(self):
        self.assertIn('severidade="critica"', self.texto)
        self.assertIn('severidade="media"', self.texto)
        self.assertNotIn("crítica", self.texto)

    def test_contagem_por_severidade_e_desfecho(self):
        self.assertIn('appsec_achados{severidade="critica",desfecho="corrigido"} 1', self.texto)
        self.assertIn('appsec_achados{severidade="media",desfecho="aberto"} 1', self.texto)

    def test_sla_cumprido(self):
        # crítica estourou o prazo: 0; alta cumpriu: 1
        self.assertIn('appsec_sla_cumprido_proporcao{severidade="critica"} 0', self.texto)
        self.assertIn('appsec_sla_cumprido_proporcao{severidade="alta"} 1', self.texto)

    def test_aberto_estourado(self):
        self.assertIn('appsec_abertos_estourados{severidade="media"} 1', self.texto)

    def test_totais(self):
        self.assertIn("appsec_achados_total 4", self.texto)
        self.assertIn("appsec_falsos_positivos_total 1", self.texto)

    def test_formato_prometheus(self):
        # Toda linha de métrica tem nome, chaves e um valor numérico ao final.
        for linha in self.texto.splitlines():
            if not linha or linha.startswith("#"):
                continue
            valor = linha.rsplit(" ", 1)[1]
            float(valor)


if __name__ == "__main__":
    unittest.main()
