package de.tum.best.states

import de.tum.best.contracts.MatchingContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

/**
 * Represents a matching of a producer and consumer listing in the system
 *
 * @property unitPrice the actual unit price of the energy being traded
 * @property unitAmount the amount of energy being traded
 * @property consumer consumer consuming the energy of the matching
 * @property producer producer providing the energy of the matching
 * @property matcher matcher that matched the producer and consumer listing
 * @property consumerDesiredPrice the original asking price of the consumer listing
 * @property producerDesiredPrice the original asking price of the producer listing
 * @property electricityType type of electricity in this matching
 * @property marketClock market clock of this matching
 */
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
