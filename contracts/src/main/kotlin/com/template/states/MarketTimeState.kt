package com.template.states

import com.template.contracts.MarketTimeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.QueryableState
import java.util.*


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