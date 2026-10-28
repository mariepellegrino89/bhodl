//! Amortization schedule of a mortgage or loan.
//!
//! A debt with terms (original amount, start date, annual rate and a
//! monthly payment or an end date, all in one fiat currency) follows a
//! French amortization plan: one installment a month, each paying the
//! month's interest first and the principal with the rest. That plan is
//! the base; what the user records adjusts it:
//!
//! - an **installment** they entered replaces the planned one of the
//!   month it is nearest to (a different amount, a skipped month paid
//!   twice, …);
//! - an **extra repayment** lowers the principal on its date and the
//!   plan goes on either with the same installment and a shorter term
//!   ([`LoanPaymentKind::ExtraShortenTerm`]) or with the same term and a
//!   lower installment ([`LoanPaymentKind::ExtraLowerPayment`]).
//!
//! The engine is pure: [`amortize`] turns terms and payments into a
//! [`LoanSchedule`], a step series of the outstanding principal from the
//! start date to the payoff, past and projected alike.

#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_precision_loss)]
#![allow(clippy::cast_sign_loss)] // calendar math: values checked non-negative
#![allow(clippy::similar_names)] // doe / doy / yoe: the algorithm's own names

use crate::OnchainError;
use crate::registry::{ManualTxn, Registry, TrackingLiability};

const SECS_PER_DAY: i64 = 86_400;

/// Longest plan walked (100 years): bounds a payment that never covers
/// the interest.
const MAX_MONTHS: u32 = 1_200;

/// Below this the debt is repaid (half a cent).
const PAID_OFF: f64 = 0.005;

/// Liability kinds that can carry an amortization plan.
pub const SCHEDULED_LIABILITY_KINDS: &[&str] = &["mortgage", "loan"];

/// What a payment on a debt is. Stored as the wire values
/// [`LoanPaymentKind::as_str`] in `tracking_manual_txns.liability_payment`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoanPaymentKind {
    /// The month's installment.
    Installment,
    /// Extra repayment; the installment stays, the term gets shorter.
    ExtraShortenTerm,
    /// Extra repayment; the term stays, the installment gets lower.
    ExtraLowerPayment,
}

/// Wire values of [`LoanPaymentKind`].
pub const VALID_LOAN_PAYMENT_KINDS: &[&str] = &["installment", "extra_term", "extra_payment"];

impl LoanPaymentKind {
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Installment => "installment",
            Self::ExtraShortenTerm => "extra_term",
            Self::ExtraLowerPayment => "extra_payment",
        }
    }

    #[must_use]
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "installment" => Some(Self::Installment),
            "extra_term" => Some(Self::ExtraShortenTerm),
            "extra_payment" => Some(Self::ExtraLowerPayment),
            _ => None,
        }
    }
}

/// The terms of a debt, in its own currency.
#[derive(Debug, Clone, PartialEq)]
pub struct LoanTerms {
    pub principal: f64,
    pub annual_rate_pct: f64,
    /// Disbursement day (UTC midnight); the first installment falls a
    /// month later.
    pub start_unix: i64,
    /// The installment; when absent it is computed from [`Self::end_unix`].
    pub monthly_payment: Option<f64>,
    /// Day of the last installment; used only without a payment.
    pub end_unix: Option<i64>,
    /// ISO 4217 code of every amount above.
    pub currency: String,
}

/// One recorded payment on a debt, in the debt's currency.
#[derive(Debug, Clone, PartialEq)]
pub struct LoanPayment {
    pub occurred_unix: i64,
    pub amount: f64,
    pub kind: LoanPaymentKind,
}

/// One change of the outstanding principal.
#[derive(Debug, Clone, PartialEq)]
pub struct LoanStep {
    pub date_unix: i64,
    /// Principal left after this step.
    pub balance: f64,
    /// The installment due from the next month on.
    pub payment: f64,
}

/// The plan of a debt, adjusted by its recorded payments.
#[derive(Debug, Clone, PartialEq)]
pub struct LoanSchedule {
    pub terms: LoanTerms,
    /// The installment the plan starts with.
    pub initial_payment: f64,
    /// Chronological; the first step is the disbursement.
    pub steps: Vec<LoanStep>,
    /// Day of every installment, planned or recorded, in order.
    pub due_dates: Vec<i64>,
    /// Day the principal reaches zero, `None` if it never does within
    /// [`MAX_MONTHS`].
    pub payoff_unix: Option<i64>,
}

impl LoanSchedule {
    /// Principal outstanding at the end of day `t` (0 before the start).
    #[must_use]
    pub fn balance_at(&self, t: i64) -> f64 {
        if t < self.terms.start_unix {
            return 0.0;
        }
        let i = self.steps.partition_point(|s| s.date_unix <= t);
        self.steps.get(i.wrapping_sub(1)).map_or(0.0, |s| s.balance)
    }

    /// The installment due after day `t`.
    #[must_use]
    pub fn payment_at(&self, t: i64) -> f64 {
        let i = self.steps.partition_point(|s| s.date_unix <= t);
        self.steps
            .get(i.wrapping_sub(1))
            .map_or(self.initial_payment, |s| s.payment)
    }

    /// First installment day after `t`, `None` once repaid.
    #[must_use]
    pub fn next_due_after(&self, t: i64) -> Option<i64> {
        if self.balance_at(t) <= PAID_OFF && t >= self.terms.start_unix {
            return None;
        }
        self.due_dates.iter().copied().find(|&d| d > t)
    }
}

/// Monthly rate of an annual percentage.
fn monthly_rate(annual_pct: f64) -> f64 {
    annual_pct / 100.0 / 12.0
}

/// Installment that repays `principal` in `months` at monthly rate `r`.
fn annuity(principal: f64, r: f64, months: u32) -> f64 {
    let n = f64::from(months.max(1));
    if r.abs() < 1e-12 {
        principal / n
    } else {
        principal * r / (1.0 - (1.0 + r).powf(-n))
    }
}

/// Installments left to repay `balance` with `payment` at rate `r`;
/// [`MAX_MONTHS`] when the payment does not cover the interest.
fn months_left(balance: f64, payment: f64, r: f64) -> u32 {
    if balance <= PAID_OFF {
        return 0;
    }
    if payment <= 0.0 {
        return MAX_MONTHS;
    }
    let n = if r.abs() < 1e-12 {
        balance / payment
    } else {
        let x = 1.0 - r * balance / payment;
        if x <= 0.0 {
            return MAX_MONTHS;
        }
        -x.ln() / (1.0 + r).ln()
    };
    // A rounding hair above an integer is that integer.
    (n - 1e-9).ceil().clamp(1.0, f64::from(MAX_MONTHS)) as u32
}

/// Build the plan of `terms`, adjusted by `payments` (any order).
///
/// Each installment applies on its due day; a recorded one replaces the
/// planned installment of the due day nearest to it (several recorded
/// for the same month add up). Extra repayments apply on their own day,
/// before the installment of their month. Interest accrues on the
/// principal at the start of each month. Payments before the start, or
/// after the payoff, have no effect.
#[must_use]
pub fn amortize(terms: &LoanTerms, payments: &[LoanPayment]) -> LoanSchedule {
    let r = monthly_rate(terms.annual_rate_pct);
    let start = terms.start_unix;
    let initial_payment =
        if let Some(p) = terms.monthly_payment.filter(|p| p.is_finite() && *p > 0.0) {
            p
        } else {
            let months = terms
                .end_unix
                .map_or(0, |end| months_between(start, end))
                .clamp(1, MAX_MONTHS);
            annuity(terms.principal, r, months)
        };

    let mut extras: Vec<&LoanPayment> = payments
        .iter()
        .filter(|p| {
            p.kind != LoanPaymentKind::Installment && p.amount.is_finite() && p.amount > 0.0
        })
        .filter(|p| p.occurred_unix >= start)
        .collect();
    extras.sort_by_key(|p| p.occurred_unix);
    let mut recorded: std::collections::HashMap<u32, f64> = std::collections::HashMap::new();
    for p in payments.iter().filter(|p| {
        p.kind == LoanPaymentKind::Installment && p.amount.is_finite() && p.amount > 0.0
    }) {
        if let Some(k) = nearest_installment(start, p.occurred_unix) {
            *recorded.entry(k).or_insert(0.0) += p.amount;
        }
    }

    let mut balance = terms.principal.max(0.0);
    let mut payment = initial_payment;
    let mut steps = vec![LoanStep {
        date_unix: start,
        balance,
        payment,
    }];
    let mut due_dates = Vec::new();
    let mut next_extra = 0usize;
    let mut payoff_unix = None;
    let mut prev_due = start;

    for k in 1..=MAX_MONTHS {
        let due = add_months(start, k);
        let interest = balance * r;
        while let Some(e) = extras.get(next_extra).filter(|e| e.occurred_unix <= due) {
            next_extra += 1;
            if e.occurred_unix < prev_due {
                continue;
            }
            // The term left before the repayment, this month included.
            let left = months_left(balance, payment, r);
            balance = (balance - e.amount).max(0.0);
            if e.kind == LoanPaymentKind::ExtraLowerPayment && balance > PAID_OFF {
                payment = annuity(balance, r, left);
            }
            steps.push(LoanStep {
                date_unix: e.occurred_unix,
                balance,
                payment,
            });
            if balance <= PAID_OFF {
                break;
            }
        }
        if balance <= PAID_OFF {
            payoff_unix = steps.last().map(|s| s.date_unix);
            break;
        }
        due_dates.push(due);
        let paid = recorded.get(&k).copied().unwrap_or(payment);
        balance = (balance + interest - paid).max(0.0);
        if balance <= PAID_OFF {
            balance = 0.0;
        }
        steps.push(LoanStep {
            date_unix: due,
            balance,
            payment,
        });
        if balance <= 0.0 {
            payoff_unix = Some(due);
            break;
        }
        prev_due = due;
    }

    LoanSchedule {
        terms: terms.clone(),
        initial_payment,
        steps,
        due_dates,
        payoff_unix,
    }
}

/// Index of the installment whose due day is nearest to `t` (1 = the
/// first), `None` for a day more than half a month before it.
fn nearest_installment(start: i64, t: i64) -> Option<u32> {
    if t < start {
        return None;
    }
    let mut k = months_between(start, t).max(1);
    // `months_between` counts whole months: the next due day may be nearer.
    let here = add_months(start, k);
    let next = add_months(start, k + 1);
    if t > here && (next - t) < (t - here) {
        k += 1;
    }
    if k == 1 && (here - t) > 15 * SECS_PER_DAY {
        return None;
    }
    Some(k.min(MAX_MONTHS))
}

// ---------------------------------------------------------------------------
// Terms from a liability's details
// ---------------------------------------------------------------------------

/// The plan terms in a liability's `details_json`, `None` unless it has
/// an original amount (`original_amount_fiat`), a start day
/// (`start_date`, `YYYY-MM-DD`), a rate (`rate_pct`, 0 allowed) and a
/// payment (`monthly_payment`) or an end day (`end_date`). Amounts are
/// in `currency`, else in `fallback_currency`.
#[must_use]
pub fn terms_from_details(details_json: &str, fallback_currency: &str) -> Option<LoanTerms> {
    let v: serde_json::Value = serde_json::from_str(details_json).ok()?;
    let obj = v.as_object()?;
    let num = |k: &str| -> Option<f64> {
        match obj.get(k)? {
            serde_json::Value::Number(n) => n.as_f64(),
            serde_json::Value::String(s) => s.trim().parse().ok(),
            _ => None,
        }
        .filter(|x: &f64| x.is_finite())
    };
    let date = |k: &str| -> Option<i64> { obj.get(k)?.as_str().and_then(parse_iso_day) };
    let principal = num("original_amount_fiat").filter(|p| *p > 0.0)?;
    let annual_rate_pct = num("rate_pct").filter(|r| *r >= 0.0 && *r < 100.0)?;
    let start_unix = date("start_date")?;
    let monthly_payment = num("monthly_payment").filter(|p| *p > 0.0);
    let end_unix = date("end_date").filter(|e| *e > start_unix);
    if monthly_payment.is_none() && end_unix.is_none() {
        return None;
    }
    let currency = obj
        .get("currency")
        .and_then(|c| c.as_str())
        .map(|c| c.trim().to_uppercase())
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| fallback_currency.trim().to_uppercase());
    Some(LoanTerms {
        principal,
        annual_rate_pct,
        start_unix,
        monthly_payment,
        end_unix,
        currency,
    })
}

/// The payment a manual transaction makes on liability `liability_id`,
/// in `currency`; `None` when it is not one, or is in another currency.
#[must_use]
pub fn payment_of(txn: &ManualTxn, liability_id: i64, currency: &str) -> Option<LoanPayment> {
    if txn.liability_id != Some(liability_id) || txn.direction != "out" {
        return None;
    }
    let kind = LoanPaymentKind::parse(txn.liability_payment.as_deref()?)?;
    let same_currency = txn
        .currency
        .as_deref()
        .is_some_and(|c| c.trim().eq_ignore_ascii_case(currency));
    let amount = txn.amount_fiat.filter(|_| same_currency)?;
    Some(LoanPayment {
        occurred_unix: txn.occurred_unix,
        amount,
        kind,
    })
}

/// The plan of every mortgage / loan that has terms, with its liability.
///
/// # Errors
///
/// [`OnchainError::Storage`] on DB errors.
pub fn load_loan_schedules(
    registry: &Registry,
    fallback_currency: &str,
) -> Result<Vec<(TrackingLiability, LoanSchedule)>, OnchainError> {
    let details: std::collections::HashMap<i64, String> =
        registry.list_details("liability")?.into_iter().collect();
    let liabilities = registry.list_liabilities()?;
    if !liabilities.iter().any(|l| {
        SCHEDULED_LIABILITY_KINDS.contains(&l.kind.as_str()) && details.contains_key(&l.id)
    }) {
        return Ok(Vec::new());
    }
    let txns = registry.list_manual_txns()?;
    let mut out = Vec::new();
    for l in liabilities {
        if !SCHEDULED_LIABILITY_KINDS.contains(&l.kind.as_str()) {
            continue;
        }
        let Some(terms) = details
            .get(&l.id)
            .and_then(|d| terms_from_details(d, fallback_currency))
        else {
            continue;
        };
        let payments: Vec<LoanPayment> = txns
            .iter()
            .filter_map(|t| payment_of(t, l.id, &terms.currency))
            .collect();
        let schedule = amortize(&terms, &payments);
        out.push((l, schedule));
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// Calendar (UTC, proleptic Gregorian)
// ---------------------------------------------------------------------------

/// Days since 1970-01-01 of a civil date (H. Hinnant's algorithm).
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let m = i64::from(m);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + i64::from(d) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Civil date of a day count since 1970-01-01.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

fn days_in_month(y: i64, m: u32) -> u32 {
    let (ny, nm) = if m == 12 { (y + 1, 1) } else { (y, m + 1) };
    (days_from_civil(ny, nm, 1) - days_from_civil(y, m, 1)) as u32
}

/// `t` (UTC midnight) plus `months`, the day clamped to the month's
/// length (31 January + 1 month = 28/29 February).
fn add_months(t: i64, months: u32) -> i64 {
    let (y, m, d) = civil_from_days(t.div_euclid(SECS_PER_DAY));
    let total = i64::from(m) - 1 + i64::from(months);
    let ny = y + total.div_euclid(12);
    let nm = (total.rem_euclid(12) + 1) as u32;
    days_from_civil(ny, nm, d.min(days_in_month(ny, nm))) * SECS_PER_DAY
}

/// Whole months from `a` to `b` (0 when `b` is before `a`).
fn months_between(a: i64, b: i64) -> u32 {
    if b <= a {
        return 0;
    }
    let (ya, ma, _) = civil_from_days(a.div_euclid(SECS_PER_DAY));
    let (yb, mb, _) = civil_from_days(b.div_euclid(SECS_PER_DAY));
    let mut n = ((yb - ya) * 12 + i64::from(mb) - i64::from(ma)).max(0) as u32;
    if n > 0 && add_months(a, n) > b {
        n -= 1;
    }
    n
}

/// `YYYY-MM-DD` as its UTC midnight.
fn parse_iso_day(s: &str) -> Option<i64> {
    let mut it = s.trim().splitn(3, '-');
    let y: i64 = it.next()?.parse().ok()?;
    let m: u32 = it.next()?.parse().ok()?;
    let d: u32 = it.next()?.parse().ok()?;
    if !(1..=12).contains(&m) || d == 0 || d > days_in_month(y, m) {
        return None;
    }
    Some(days_from_civil(y, m, d) * SECS_PER_DAY)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(s: &str) -> i64 {
        parse_iso_day(s).unwrap()
    }

    fn terms(principal: f64, rate: f64, payment: Option<f64>, end: Option<&str>) -> LoanTerms {
        LoanTerms {
            principal,
            annual_rate_pct: rate,
            start_unix: day("2020-01-15"),
            monthly_payment: payment,
            end_unix: end.map(day),
            currency: "EUR".into(),
        }
    }

    fn pay(date: &str, amount: f64, kind: LoanPaymentKind) -> LoanPayment {
        LoanPayment {
            occurred_unix: day(date),
            amount,
            kind,
        }
    }

    #[test]
    fn calendar_round_trips_and_clamps_month_ends() {
        assert_eq!(day("1970-01-01"), 0);
        for z in [-800_000, -1, 0, 59, 11_016, 20_000, 2_000_000] {
            let (y, m, d) = civil_from_days(z);
            assert_eq!(days_from_civil(y, m, d), z);
        }
        assert_eq!(add_months(day("2024-01-31"), 1), day("2024-02-29"));
        assert_eq!(add_months(day("2023-01-31"), 1), day("2023-02-28"));
        assert_eq!(add_months(day("2023-11-15"), 3), day("2024-02-15"));
        assert_eq!(months_between(day("2020-01-15"), day("2020-03-14")), 1);
        assert_eq!(months_between(day("2020-01-15"), day("2020-03-15")), 2);
        assert_eq!(parse_iso_day("2023-02-29"), None);
    }

    #[test]
    fn plan_from_end_date_repays_on_the_last_installment() {
        // 120,000 at 3% over 20 years: the textbook installment is 665.52.
        let s = amortize(&terms(120_000.0, 3.0, None, Some("2040-01-15")), &[]);
        assert!(
            (s.initial_payment - 665.52).abs() < 0.01,
            "{}",
            s.initial_payment
        );
        assert_eq!(s.payoff_unix, Some(day("2040-01-15")));
        assert_eq!(s.due_dates.len(), 240);
        // After the first installment: 120,000 + 300 interest − 665.52.
        assert!((s.balance_at(day("2020-02-15")) - 119_634.48).abs() < 0.01);
        // The day before it nothing has moved yet.
        assert!((s.balance_at(day("2020-02-14")) - 120_000.0).abs() < 1e-9);
        assert!(
            s.balance_at(day("2019-12-31")).abs() < 1e-9,
            "no debt before the start"
        );
        assert!(s.balance_at(day("2041-01-01")).abs() < 1e-9);
    }

    #[test]
    fn zero_rate_divides_the_principal_evenly() {
        let s = amortize(&terms(12_000.0, 0.0, None, Some("2021-01-15")), &[]);
        assert!((s.initial_payment - 1_000.0).abs() < 1e-9);
        assert!((s.balance_at(day("2020-07-15")) - 6_000.0).abs() < 1e-6);
        assert_eq!(s.payoff_unix, Some(day("2021-01-15")));
    }

    #[test]
    fn a_recorded_installment_replaces_the_planned_one_of_its_month() {
        let t = terms(12_000.0, 0.0, Some(1_000.0), None);
        // Paid three days late and 500 more than planned.
        let s = amortize(
            &t,
            &[pay("2020-03-18", 1_500.0, LoanPaymentKind::Installment)],
        );
        // Feb planned (1,000), March recorded (1,500): 9,500 left.
        assert!((s.balance_at(day("2020-03-20")) - 9_500.0).abs() < 1e-6);
        // Recorded installments matching the plan change nothing.
        let same = amortize(
            &t,
            &[pay("2020-02-15", 1_000.0, LoanPaymentKind::Installment)],
        );
        assert_eq!(same.steps, amortize(&t, &[]).steps);
    }

    #[test]
    fn extra_repayment_shortens_the_term_by_default_payment() {
        let t = terms(12_000.0, 0.0, Some(1_000.0), None);
        let s = amortize(
            &t,
            &[pay(
                "2020-04-01",
                3_000.0,
                LoanPaymentKind::ExtraShortenTerm,
            )],
        );
        // Feb + Mar paid (10,000), then 3,000 extra → 7,000 on 1 April.
        assert!((s.balance_at(day("2020-04-01")) - 7_000.0).abs() < 1e-6);
        assert!((s.payment_at(day("2020-04-01")) - 1_000.0).abs() < 1e-9);
        // Seven more installments: April 15 … October 15.
        assert_eq!(s.payoff_unix, Some(day("2020-10-15")));
    }

    #[test]
    fn extra_repayment_can_lower_the_installment_instead() {
        let t = terms(12_000.0, 0.0, Some(1_000.0), None);
        let s = amortize(
            &t,
            &[pay(
                "2020-04-01",
                3_000.0,
                LoanPaymentKind::ExtraLowerPayment,
            )],
        );
        // 10 installments were left (April … January): 7,000 / 10.
        assert!((s.payment_at(day("2020-04-01")) - 700.0).abs() < 1e-6);
        assert_eq!(s.payoff_unix, Some(day("2021-01-15")));
    }

    #[test]
    fn lower_payment_keeps_the_end_date_with_interest() {
        let t = terms(100_000.0, 2.4, None, Some("2040-01-15"));
        let s = amortize(
            &t,
            &[pay(
                "2025-01-15",
                20_000.0,
                LoanPaymentKind::ExtraLowerPayment,
            )],
        );
        assert!(s.payment_at(day("2025-02-01")) < s.initial_payment);
        let end = s.payoff_unix.unwrap();
        assert!(
            (end - day("2040-01-15")).abs() <= 31 * SECS_PER_DAY,
            "end moved to {end}"
        );
        let shorter = amortize(
            &t,
            &[pay(
                "2025-01-15",
                20_000.0,
                LoanPaymentKind::ExtraShortenTerm,
            )],
        );
        assert!(shorter.payoff_unix.unwrap() < day("2038-01-15"));
    }

    #[test]
    fn extra_repaying_everything_closes_the_debt_on_its_day() {
        let t = terms(12_000.0, 0.0, Some(1_000.0), None);
        let s = amortize(
            &t,
            &[pay(
                "2020-03-01",
                50_000.0,
                LoanPaymentKind::ExtraShortenTerm,
            )],
        );
        assert_eq!(s.payoff_unix, Some(day("2020-03-01")));
        assert!(s.balance_at(day("2020-03-01")).abs() < 1e-9);
        assert_eq!(s.next_due_after(day("2020-03-02")), None);
    }

    #[test]
    fn a_payment_below_the_interest_never_repays() {
        let s = amortize(&terms(100_000.0, 12.0, Some(500.0), None), &[]);
        assert_eq!(s.payoff_unix, None);
        assert_eq!(s.due_dates.len(), MAX_MONTHS as usize);
    }

    #[test]
    fn terms_read_numbers_and_strings_and_need_a_payment_or_an_end() {
        let t = terms_from_details(
            r#"{"original_amount_fiat":"150000","rate_pct":2.4,"start_date":"2019-05-10","monthly_payment":690,"currency":"eur"}"#,
            "USD",
        )
        .unwrap();
        assert_eq!(t.currency, "EUR");
        assert!((t.principal - 150_000.0).abs() < 1e-9);
        assert_eq!(t.start_unix, day("2019-05-10"));
        let fallback = terms_from_details(
            r#"{"original_amount_fiat":1000,"rate_pct":0,"start_date":"2019-05-10","end_date":"2020-05-10"}"#,
            "chf",
        )
        .unwrap();
        assert_eq!(fallback.currency, "CHF");
        assert!(
            terms_from_details(
                r#"{"original_amount_fiat":1000,"rate_pct":1,"start_date":"2019-05-10"}"#,
                "EUR"
            )
            .is_none()
        );
        assert!(
            terms_from_details(
                r#"{"original_amount_sat":"290000000","rate_pct":2.4,"monthly_payment":690}"#,
                "EUR"
            )
            .is_none()
        );
        assert!(terms_from_details("not json", "EUR").is_none());
    }

    #[test]
    fn next_due_is_the_first_installment_after_the_day() {
        let s = amortize(&terms(12_000.0, 0.0, Some(1_000.0), None), &[]);
        assert_eq!(s.next_due_after(day("2020-02-15")), Some(day("2020-03-15")));
        assert_eq!(s.next_due_after(day("2019-01-01")), Some(day("2020-02-15")));
    }
}
