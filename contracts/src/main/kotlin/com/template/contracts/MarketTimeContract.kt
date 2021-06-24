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
        // Step 1: Get the corresponding command attached to this transaction. Every transaction should have only 1
        //         command attached to them
        val command = tx.commands.requireSingleCommand<MarketTimeContract.Commands>()

        // Step 2: Get the corresponding output state
        val outputs = tx.outputsOfType<MarketTimeState>()
        val inputs = tx.inputsOfType<MarketTimeState>()

        requireThat{

            "There should only be a single input state" using(inputs.size == 1)

            "There should only be a single output state" using(outputs.size == 1)
        }
        // Step 3: Based on type of the command do verifications

        when (command.value) {
            is Commands.InitiateMarketTime, is Commands.ClearMarketTime, is Commands.ResetMarketTime ->
                verifyMarketTime(outputs)
            else -> {
                throw IllegalArgumentException("Unknown command!")
            }
        }

    }
    // Helper function in order to verify Market Time
    private fun verifyMarketTime(marketTimes: List<MarketTimeState>) {
        // Go through all listings and verify them. In practice there should only be one listing but
        // in case somebody tries to create modified transactions the system should be able to handle all of them
        for(marketTime in marketTimes) {
            requireThat {
                "marketTime value must be greater than or equal to 0" using(marketTime.marketTime >= 0)

                "marketTime value must be lower than 3." using(marketTime.marketTime <3)

                "marketClock value must be non-negative" using(marketTime.marketClock > 0)
            }
        }
    }

    // Commands are used to determine the type of the listing.
    // -InitiateMarketTime: initialization after the creation of Market Time object
    // -UpdateMarketTime: updating the marketing time after its creation
    interface Commands : CommandData {
        class InitiateMarketTime : Commands {}
        class ClearMarketTime : Commands {}
        class ResetMarketTime : Commands {}
    }
}


