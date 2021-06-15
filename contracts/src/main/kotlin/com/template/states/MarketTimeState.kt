package com.template.states

import com.template.contracts.MarketTimeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party


// *********
// * Market Time State *
//
// This class is for the "Market Time" object in best-marketplace. This class ONLY DENOTES an object,
// functionality is implemented in the corresponding flow and the contract.
// No need to add more parameters due to increasing complexity.
//
// params:
//      marketTime:         Current market time
//
// *********

@BelongsToContract(MarketTimeContract::class)
class MarketTimeState (
    val marketTime: Int,
    val sender: Party,
    val receiver: Party,
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : ContractState
