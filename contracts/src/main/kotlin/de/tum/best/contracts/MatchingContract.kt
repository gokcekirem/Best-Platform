package de.tum.best.contracts

import de.tum.best.states.ListingState
import de.tum.best.states.ListingType
import de.tum.best.states.MatchingState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

/**
 * Sanity checks for matching object fields
 */
class MatchingContract : Contract {

    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "de.tum.best.contracts.MatchingContract"
    }


    override fun verify(tx: LedgerTransaction) {
        // tx here is the matching transaction

        // Step 1: Get the corresponding command attached to this transaction. Every transaction should have only 1
        //         command attached to them
        // val command = tx.commands.requireSingleCommand<ListingContract.Commands>()

        // Get the output states
        val matchingState = tx.outputsOfType<MatchingState>().single()

        //Get the input states
        val listingStates = tx.inputsOfType<ListingState>()

        requireThat {
            "Should have two inputs" using (tx.inputs.size == 2)
            // If we use UTXO model, we could possibly get more then 1 output where leftover energy would have
            // have a state in addition to the matching state
            "Should have one output" using (tx.outputs.size == 1)

            for(listingState in listingStates) {
                when (listingState.listingType) {
                    ListingType.ProducerListing -> {
                        "Seller should be the producer" using (listingState.sender == matchingState.seller)
                    }
                    ListingType.ConsumerListing -> {
                        "Buyer should be the consumer" using (listingState.sender == matchingState.buyer)
                    }
                }

                "Amounts should match up" using (listingState.amount == matchingState.unitAmount)
                "Matcher should match up" using (listingState.matcher == matchingState.matcher)
            }
        }
    }

    interface Commands : CommandData {
        class Match : Commands
    }

}