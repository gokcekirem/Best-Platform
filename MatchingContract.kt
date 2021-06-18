
package de.tum.best.contracts

import com.template.contracts.ListingContract
import com.template.states.ListingState
import de.tum.best.states.MatchingState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

/**
 * Sanity checks for matching object fields
 */
class MatchingContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        // tx here is the matching transaction
        val producerListing : Int = 1
        val consumerListing : Int = 2
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

            for(state in listingStates) {
                when (state.listingType) {
                    producerListing -> {
                        "Seller should be the producer" using (state.sender == matchingState.seller)
                        // producer set the minimum price he can sell at
                        "Matched price to be greater then or equal to ask price" using (state.unitPrice <= matchingState.unitPrice)
                    }
                    consumerListing -> {
                        "Buyer should be the consumer" using (state.sender == matchingState.buyer)
                        // consumer set the maximum price he can buy at
                        "Matched price to be less then or equal to bid price" using (state.unitPrice >= matchingState.unitPrice)
                    }
                }

                "Amounts should match up" using (state.amount == matchingState.unitAmount)
                "Matcher should match up" using (state.matcher == matchingState.matcher)
            }
        }
    }

    interface Commands : CommandData {
        class Match : Commands
    }

}