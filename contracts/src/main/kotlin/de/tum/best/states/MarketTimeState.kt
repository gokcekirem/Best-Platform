package de.tum.best.states

import de.tum.best.contracts.MarketTimeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party


/**
   Market Time State

  * This class is for the "Market Time" object in best-marketplace. This class ONLY DENOTES an object
  * functionality is implemented in the corresponding flow and the contract.
  * No need to add more parameters due to increasing complexity.

**/

@BelongsToContract(MarketTimeContract::class)
class MarketTimeState (
    val marketClock: Int,
    val marketTime: Int,
    val sender: Party,
    val receiver: Party,
    override val linearId: UniqueIdentifier = UniqueIdentifier(), //All MarketTime states share the common ID
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : LinearState