package br.com.feira.plataforma.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma mensagem esperando para ser publicada.
 *
 * <h2>Por que existe</h2>
 * Gravar no banco e publicar no Kafka sao dois sistemas diferentes. Nao ha
 * transacao que cubra os dois. Sem outbox, uma das duas coisas acontece:
 *
 * <ul>
 *   <li>publica primeiro e grava depois: o processo cai no meio, o evento foi
 *       para a fila e o pedido nao existe no banco. O estoque reserva
 *       mercadoria para um pedido fantasma.</li>
 *   <li>grava primeiro e publica depois: o processo cai no meio, o pedido
 *       existe e ninguem foi avisado. O cliente ve "processando" para sempre.</li>
 * </ul>
 *
 * Com outbox, a mensagem e gravada <b>na mesma transacao</b> da mudanca de
 * estado. Ou as duas coisas acontecem, ou nenhuma. Um processo separado le a
 * tabela e publica. Isso troca "pode perder evento" por "pode publicar o mesmo
 * evento duas vezes", que e um problema com solucao conhecida: consumidor
 * idempotente.
 */
@Entity
@Table(name = "outbox")
public class MensagemDoOutbox {

    /** Numero maximo de tentativas antes de mandar para analise manual. */
    public static final int TENTATIVAS_MAXIMAS = 10;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "topico", nullable = false, length = 120)
    private String topico;

    /**
     * Chave de particao. E o id do pedido, para que todos os eventos do mesmo
     * pedido caiam na mesma particao e cheguem em ordem.
     */
    @Column(name = "chave", nullable = false, length = 80)
    private String chave;

    @Column(name = "tipo", nullable = false, length = 60)
    private String tipo;

    @Column(name = "carga", nullable = false, columnDefinition = "text")
    private String carga;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "publicada_em")
    private Instant publicadaEm;

    @Column(name = "tentativas", nullable = false)
    private int tentativas;

    @Column(name = "ultimo_erro", length = 500)
    private String ultimoErro;

    /**
     * Contexto de rastro de quem gerou o evento, no formato do W3C.
     *
     * Nulo quando o evento nao nasceu de uma requisicao, o que e o caso das
     * tarefas agendadas. Ver RastroDaMensagem.
     */
    @Column(name = "trace_pai", length = 80)
    private String tracePai;

    /** Quando a proxima tentativa pode acontecer. Base do recuo exponencial. */
    @Column(name = "tentar_apos", nullable = false)
    private Instant tentarApos;

    protected MensagemDoOutbox() {
        // exigido pelo JPA
    }

    private MensagemDoOutbox(UUID id, String topico, String chave, String tipo, String carga,
                             Instant agora, String tracePai) {
        this.id = id;
        this.topico = topico;
        this.chave = chave;
        this.tipo = tipo;
        this.carga = carga;
        this.criadaEm = agora;
        this.tentarApos = agora;
        this.tentativas = 0;
        this.tracePai = tracePai;
    }

    public String tracePai() {
        return tracePai;
    }

    public static MensagemDoOutbox nova(UUID eventoId, String topico, String chave, String tipo,
                                        String carga, Instant agora) {
        return nova(eventoId, topico, chave, tipo, carga, agora, null);
    }

    public static MensagemDoOutbox nova(UUID eventoId, String topico, String chave, String tipo,
                                        String carga, Instant agora, String tracePai) {
        return new MensagemDoOutbox(eventoId, topico, chave, tipo, carga, agora, tracePai);
    }

    public void marcarPublicada(Instant agora) {
        this.publicadaEm = agora;
        this.ultimoErro = null;
    }

    /**
     * Recuo exponencial com teto de cinco minutos.
     *
     * Sem teto, a decima tentativa cairia em mais de dezessete minutos e o
     * evento ficaria parado tempo demais depois de o broker voltar. Sem recuo
     * nenhum, um Kafka fora do ar vira mil tentativas por segundo e um log
     * inutilizavel.
     */
    public void marcarFalha(String erro, Instant agora) {
        this.tentativas++;
        this.ultimoErro = erro == null ? null : erro.substring(0, Math.min(erro.length(), 500));
        long segundos = Math.min(300L, (long) Math.pow(2, Math.min(tentativas, 8)));
        this.tentarApos = agora.plusSeconds(segundos);
    }

    public boolean esgotouTentativas() {
        return tentativas >= TENTATIVAS_MAXIMAS;
    }

    public boolean publicada() {
        return publicadaEm != null;
    }

    public UUID id() {
        return id;
    }

    public String topico() {
        return topico;
    }

    public String chave() {
        return chave;
    }

    public String tipo() {
        return tipo;
    }

    public String carga() {
        return carga;
    }

    public Instant criadaEm() {
        return criadaEm;
    }

    public Instant publicadaEm() {
        return publicadaEm;
    }

    public int tentativas() {
        return tentativas;
    }

    public String ultimoErro() {
        return ultimoErro;
    }

    public Instant tentarApos() {
        return tentarApos;
    }
}
