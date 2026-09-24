export interface Customer {
  id?: number;
  name: string;
  email: string;
}

export interface Product {
  id?: number;
  name: string;
  price: number;
  quantity: number;
}

export interface OrderItem {
  id?: number;
  productId?: number;
  price?: number;
  quantity?: number;
  discount?: number;
  product?: Product;
}

export interface Order {
  id?: number;
  createdAt?: string;
  status?: string;
  customerId?: number;
  customer?: Customer;
  productItems?: OrderItem[];
}

export interface BillItem {
  id?: number;
  productId?: number;
  quantity?: number;
  unitPrice?: number;
  product?: Product;
}

export interface Bill {
  id?: number;
  billingDate?: string;
  customerId?: number;
  customer?: Customer;
  productItems?: BillItem[];
}

export interface LedgerEvent {
  eventId?: string;
  occurredAt?: string;
  offset?: number;
  type?: string;        // ACCOUNT_CREATED | MONEY_CREDITED | MONEY_DEBITED
  amount?: number;
  description?: string;
  accountId?: string;
  customerId?: number;
  holderName?: string;
}

export interface Account {
  accountId: string;
  customerId?: number;
  holderName?: string;
  balance: number;
  history?: LedgerEvent[];
}

export interface JournalEntry {
  id?: string;
  transactionId?: string;
  debitAccountId?: string;
  creditAccountId?: string;
  amount?: number;
  currency?: string;
  description?: string;
  createdAt?: string;
  postedBy?: string;
}

export interface TrialBalance {
  totalDebits: number;
  totalCredits: number;
  balanced: boolean;
}

export interface StatementLine {
  entry?: JournalEntry;
  runningBalance?: number;
}

export interface AccountStatement {
  accountId: string;
  lines?: StatementLine[];
  finalBalance?: number;
}

export interface SagaStep {
  name: string;
  timestamp?: string;
  offset?: number;
  status: string;
}

export interface SagaState {
  transactionId: string;
  status: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  steps?: SagaStep[];
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
}

/**
 * One KPI's trend against the same period immediately before it (brief 5.3).
 *
 * `deltaPercent` is null when there is nothing to divide by — a zero or absent
 * value in the comparison window, and every KPI of the `all` range, which has no
 * earlier period at all — which the card renders as "—" rather than as 0%.
 */
export interface KpiTrend {
  current: number;
  previous: number;
  deltaPercent: number | null;
  /**
   * One point per bucket of the selected range, oldest first, the last being the
   * period in progress: seven daily points for `7d`, thirty for `30d`, thirteen
   * weekly for `90d`, and twelve (or up to twenty-four) monthly for `1y` and
   * `all`. Drives the inline sparkline; the unit follows the KPI — currency for
   * the money cards, a count for accounts, transfers and sagas, a percentage for
   * the rate cards, and milliseconds for the duration cards.
   *
   * `null` is a gap, not a zero: the dashboard-SLA card is measured in the
   * server's memory, so a day before the service started has no reading at all.
   * Plotting that as 0 would draw a week-long outage that never happened, so the
   * sparkline drops the point instead.
   */
  history: (number | null)[];
}

/**
 * Payload of `GET /api/dashboard/kpi-trends?range=…`, keyed by KPI card.
 *
 * Every trend in it covers the range the request asked for, so the same payload
 * shape serves all five: only the windows behind the numbers and the length of
 * each history change. `all` has no earlier period to compare against, so every
 * `deltaPercent` in it is null and the cards draw a dash.
 */
export interface KpiTrends {
  assetsUnderManagement: KpiTrend;
  activeAccounts: KpiTrend;
  todayVolume: KpiTrend;
  problemSagas: KpiTrend;
  /** Money into customer accounts minus money out over the selected range. */
  netCashFlow: KpiTrend;
  /** Mean amount posted over the selected range. */
  avgTransactionValue: KpiTrend;
  /** Share of all balances held by the ten largest accounts, as a percentage. */
  balanceConcentration: KpiTrend;
  /** Postings above $10,000 in the selected range. */
  largeTransfers: KpiTrend;
  /** Sagas started in the selected range that completed, as a percentage. */
  sagaSuccessRate: KpiTrend;
  /** Mean duration, in milliseconds, of the sagas that started and finished. */
  avgSagaDuration: KpiTrend;
  /** 95th percentile of the same durations, in milliseconds. */
  p95SagaDuration: KpiTrend;
  /** Sagas started in the selected range that are being unwound, as a percentage. */
  compensationRate: KpiTrend;

  /*
   * The governance four. These describe the pipeline that produces the KPIs
   * above rather than the bank's business, and they are reported as measured —
   * including when the answer is unflattering.
   */

  /**
   * Transfer postings in the selected range that have a matching saga record, as
   * a percentage. The ledger log and the saga store are two records of the same
   * transactions; this is how far apart they have drifted.
   */
  sagaJournalReconciliation: KpiTrend;
  /**
   * Postings in the selected range whose amount is more than three standard
   * deviations from the mean of the trailing 30 days, as a percentage.
   */
  anomalyRate: KpiTrend;
  /** Postings in the selected range carrying every audit field, as a percentage. */
  auditTrailCompleteness: KpiTrend;
  /**
   * Command Center requests answered in under 500ms over the last 24 hours, as
   * a percentage. Sampled in the service's memory, so its history starts when
   * the process did — and the one card that ignores the range selector, because
   * an API's freshness is an operations metric rather than a business one.
   */
  dashboardSlaCompliance: KpiTrend;
}

/**
 * One point of the volume series: how many journal entries landed on `date`.
 *
 * `date` is a plain `YYYY-MM-DD` day, not an instant: the server groups with
 * Postgres `to_char(..., 'YYYY-MM-DD')`, and the client buckets on that same
 * string so no timezone can shift a point into the neighbouring day.
 */
export interface ChartPoint {
  date: string;
  count: number;
}

/** One point of the cash-flow series. `in`/`out` sum the legs that touch a customer account. */
export interface ChartFlowPoint {
  date: string;
  in: number;
  out: number;
}

/** One slice of the balance distribution: the ten largest balances, per the server. */
export interface ChartAccountBalance {
  accountId: string;
  balance: number;
}

/** Saga counts by terminal status. */
export interface SagaBreakdown {
  completed: number;
  compensating: number;
  failed: number;
}

/**
 * Payload of `GET /api/dashboard/chart-series?range=…`: everything the four
 * Command Center charts draw, for one selected range.
 *
 * The series cover the whole ledger rather than the entry page the feed uses,
 * which is the point of the endpoint — a 90-day chart drawn from 200 recent
 * rows would show a fraction of its own window.
 *
 * The volume and flow series are sparse (only days with activity appear), so the
 * client rebuilds the day axis from the range and zero-fills the gaps.
 */
export interface ChartSeries {
  /** The range the server applied, echoed back as its own token (7d, 30d, …). */
  range: string;
  dailyVolume: ChartPoint[];
  dailyFlow: ChartFlowPoint[];
  balanceDistribution: ChartAccountBalance[];
  sagaBreakdown: SagaBreakdown;
  /**
   * The earliest instant the ledger holds, ISO-8601, or null for an empty log.
   *
   * Optional because it is an addition to a payload other code already builds:
   * a client running ahead of its server simply gets `undefined` and leaves the
   * timeline scrubber disabled, which is the same state as a ledger with no
   * history. It rides here rather than on an endpoint of its own because the
   * scrubber needs it on mount, and the dashboard is already making this call.
   */
  historyStart?: string | null;
}
