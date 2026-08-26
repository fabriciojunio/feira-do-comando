package br.com.feira.pedidos.infraestrutura.config;

import br.com.feira.pedidos.aplicacao.VarrerPedidosParados;
import br.com.feira.plataforma.inbox.Inbox;
import br.com.feira.pedidos.infraestrutura.persistencia.ChaveDeIdempotenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Rotinas de fundo.
 *
 * Cada uma existe porque algo depende da passagem do tempo e ninguem vai
 * clicar em nada para que aconteca. Todas engolem excecao e registram: falha
 * de rotina nao pode derrubar o servico, mas tambem nao pode passar em branco.
 */
@Component
public class Rotinas {

    private static final Logger log = LoggerFactory.getLogger(Rotinas.class);

    /**
     * Trinta dias cobre com folga qualquer reentrega plausivel do broker.
     * Guardar para sempre so faz a tabela de idempotencia crescer sem uso.
     */
    private static final Duration RETENCAO_DO_INBOX = Duration.ofDays(30);

    /** Ninguem repete uma tentativa de compra dias depois. */
    private static final Duration RETENCAO_DAS_CHAVES = Duration.ofDays(2);

    private final VarrerPedidosParados varredura;
    private final Inbox inbox;
    private final ChaveDeIdempotenciaRepository chaves;
    private final Clock relogio;

    public Rotinas(VarrerPedidosParados varredura, Inbox inbox,
                   ChaveDeIdempotenciaRepository chaves, Clock relogio) {
        this.varredura = varredura;
        this.inbox = inbox;
        this.chaves = chaves;
        this.relogio = relogio;
    }

    @Scheduled(cron = "${feira.saga.cron-varredura:0 * * * * *}")
    public void fecharSagasParadas() {
        try {
            int afetados = varredura.executar();
            if (afetados > 0) {
                log.info("Varredura de saga: {} pedidos passaram do prazo", afetados);
            }
        } catch (Exception e) {
            log.error("Varredura de saga falhou", e);
        }
    }

    @Scheduled(cron = "${feira.inbox.cron-expurgo:0 15 3 * * *}", zone = "America/Sao_Paulo")
    @Transactional
    public void expurgarInbox() {
        try {
            int removidos = inbox.expurgar(relogio.instant().minus(RETENCAO_DO_INBOX));
            if (removidos > 0) {
                log.info("Inbox: {} registros removidos por retencao", removidos);
            }
        } catch (Exception e) {
            log.error("Expurgo do inbox falhou", e);
        }
    }

    @Scheduled(cron = "${feira.chaves.cron-expurgo:0 45 3 * * *}", zone = "America/Sao_Paulo")
    @Transactional
    public void expurgarChaves() {
        try {
            int removidas = chaves.apagarAntesDe(relogio.instant().minus(RETENCAO_DAS_CHAVES));
            if (removidas > 0) {
                log.info("Chaves de idempotencia: {} removidas por retencao", removidas);
            }
        } catch (Exception e) {
            log.error("Expurgo de chaves falhou", e);
        }
    }
}
