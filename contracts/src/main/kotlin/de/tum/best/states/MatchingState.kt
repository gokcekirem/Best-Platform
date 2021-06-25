package de.tum.best.states

import de.tum.best.contracts.MatchingContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

@BelongsToContract(MatchingContract::class)
data class MatchingState(
    val unitPrice: Int,
    val unitAmount: Int,
    val buyer: Party,
    val seller: Party,
    val matcher: Party,
    override val participants: List<AbstractParty> = listOf(buyer, seller, matcher)
) : ContractState
