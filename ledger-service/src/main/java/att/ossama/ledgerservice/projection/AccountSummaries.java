package att.ossama.ledgerservice.projection;

import att.ossama.ledgerservice.domain.AccountState;

import java.util.List;

/**
 * The account list, without replaying the log.
 *
 * <p>{@link AccountProjection#allAccounts()} used to answer this by loading every
 * event to collect the account ids and then replaying each account's stream —
 * 1,115 queries and ~102,000 JSON payload decodes on the portfolio ledger, about
 * eleven seconds. This is the same question asked as a single aggregate, which is
 * how the KPI trends were fixed.
 *
 * <p>Implementations return each account's summary with an empty event history:
 * the history is a per-account concern (the statement view asks for one account
 * at a time, and {@link AccountProjection#rebuild(String)} still replays it), and
 * attaching 51,025 events to a list response is most of what made it slow to send
 * as well as to build.
 */
public interface AccountSummaries {

    /** Every account, ordered by account id, with no event history attached. */
    List<AccountState> allAccounts();
}
