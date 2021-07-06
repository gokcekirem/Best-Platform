package de.tum.best.contracts

import de.tum.best.states.ElectricityType
import de.tum.best.states.ListingState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

/**
 * Listing Contract *

 *Job of the contract is to verify that the corresponding "state" is legal.
*/
class ListingContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "de.tum.best.contracts.ListingContract"
    }

    // A transaction is valid if the verify() function of the contract of all the transaction's input and output states
    // does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        // Step 1: Get the corresponding command attached to this transaction. Every transaction should have only 1
        //         command attached to them
        val command = tx.commands.requireSingleCommand<Commands>()

        // Step 2: Get the corresponding output states
        val outputs = tx.outputsOfType<ListingState>()

        // Step 3: Based on type of the command do verifications
        when (command.value) {
            is Commands.ConsumerListing -> verifyConsumerListings(outputs)
            is Commands.ProducerListing -> verifyProducerListings(outputs)
            is Commands.SplitTx -> verifySplitListing(tx, tx.inputsOfType(), outputs)
            else -> throw IllegalArgumentException("Unknown command!")
        }
    }

    private fun verifySplitListing(tx: LedgerTransaction, inputs: List<ListingState>, outputs: List<ListingState>) {
        val inputListingState = inputs.single()
        requireThat {
            "Should have one input" using (tx.inputs.size == 1)
            "Should have two outputs" using (tx.outputs.size == 2)

            "Sum of output amounts should equal to input amounts" using
                    (outputs.sumBy { it.amount } == inputListingState.amount)
        }
    }

    /** Helper function in order to verify listings of type ConsumerListing */
    private fun verifyConsumerListings(listings: List<ListingState>) {
        // Go through all listings and verify them. In practice there should only be one listing but
        // in case somebody tries to create modified transactions the system should be able to handle all of them

//        val results = serviceHub.vaultService.queryBy<ContractState>(criteria)
        for (listing in listings) {
            // Requirements
            requireThat {
                "Unit price must be positive".using(listing.unitPrice > 0)
                "Amount should be positive".using(listing.amount > 0)
                "Market clock should be valid".using(listing.marketClock >= 0)
            }
        }
    }

    /** Helper function in order to verify listings of type ProducerListing */
    private fun verifyProducerListings(listings: List<ListingState>) {
        // Go through all listings and verify them. In practice there should only be one listing but
        // in case somebody tries to create modified transactions the system should be able to handle all of them
        for (listing in listings) {
            // Requirements
            requireThat {
                "Unit price must be positive".using(listing.unitPrice > 0)
                "Amount should be positive".using(listing.amount > 0)
                "Market clock should be valid".using(listing.marketClock >= 0)
            }
        }
    }

    // Commands are used to determine the type of the listing.
    // If it is "ConsumerListing" then the listing should be interpreted as "want to buy" (created by a consumer)
    // If it is "producerListing" then the listing should be interpreted as "want to sell" (created by a producer)
    interface Commands : CommandData {
        class ConsumerListing : Commands
        class ProducerListing : Commands
        class SplitTx : Commands
    }
}
