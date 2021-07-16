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
    val consumer: Party,
    val producer: Party,
    val matcher: Party,
    val consumerDesiredPrice: Int,
    val producerDesiredPrice: Int,
    val electricityType: ElectricityType,
    val marketClock: Int,
    override val participants: List<AbstractParty> = listOf(consumer, producer, matcher)
) : ContractState
