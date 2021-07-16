package de.tum.best.states

import de.tum.best.contracts.MarketTimeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party


/**
 * Market Time State
 *
 * This class is for the "Market Time" object in best-marketplace. This class ONLY DENOTES an object
 * functionality is implemented in the corresponding flow and the contract.
 * No need to add more parameters due to increasing complexity.
 *
 * The [marketClock] constantly grows with each market iteration.
 * The [marketTime] repeats the different phases of the market in a cycle.
 * More detail is provided in the report.
 *
 * @property marketClock current market clock
 * @property marketTime current market time
 * @property sender the sender of this state
 * @property receiver the receiver of this sate
 */
@BelongsToContract(MarketTimeContract::class)
class MarketTimeState(
    val marketClock: Int,
    val marketTime: Int,
    val sender: Party,
    val receiver: Party,
    override val linearId: UniqueIdentifier = UniqueIdentifier(), //All MarketTime states share the common ID
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : LinearState