package att.ossama.billingservice.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A poison message that exhausted the main listener's retry budget on
 * "ledger-events" and was republished to "ledger-events.DLT". The row is the
 * evidence: what failed, where it came from, and why.
 *
 * <p>Append-only in practice — rows are never updated, replayed or drained.
 * Two rows for the same record are two occurrences, not a duplicate to be
 * squashed.
 */
@Entity
@Table(name = "dead_letter_transactions")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class DeadLetterTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Original topic the record failed on; "ledger-events" when unknowable. */
    private String topic;

    // PARTITION and OFFSET are reserved words in H2 2.x, so the identifiers are
    // quoted; without the quotes the generated DDL is a syntax error. JPA reads
    // and writes through the quoted names, so the columns stay named as the
    // spec's table describes.
    @Column(name = "\"partition\"")
    private Integer partition;

    @Column(name = "\"offset\"")
    private Long offset;

    /** The record's key, may be null. */
    private String recordKey;

    /** The raw value, verbatim — the poison payload is the evidence. */
    @Lob
    private String payload;

    private String exceptionFqcn;

    @Lob
    private String exceptionMessage;

    /** ISO-8601, matching how ledger timestamps are stored elsewhere. */
    private String failedAt;
}
