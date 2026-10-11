/// Direction of a transaction relative to the wallet.
///
/// `Internal` covers self-transfers (consolidation, change-only moves, swaps
/// between layers owned by the same wallet) — they affect privacy/fee accounting
/// but are not income or expense for tracking purposes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Direction {
    Incoming,
    Outgoing,
    Internal,
}
