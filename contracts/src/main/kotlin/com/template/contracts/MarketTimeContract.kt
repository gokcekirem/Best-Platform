package com.template.contracts

import com.template.states.MarketTimeState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.contracts.requireThat

class MarketTimeContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "com.template.contracts.MarketTimeContract"
    }

    override fun verify(tx: LedgerTransaction) {
        TODO("Not yet implemented")
    }
}