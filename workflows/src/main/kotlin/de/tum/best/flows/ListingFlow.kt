package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.flows.FinalityFlow

import net.corda.core.flows.CollectSignaturesFlow

import net.corda.core.transactions.SignedTransaction

import java.util.stream.Collectors

import net.corda.core.flows.FlowSession

import net.corda.core.identity.Party

import de.tum.best.contracts.ListingContract

import net.corda.core.transactions.TransactionBuilder

import de.tum.best.states.ListingState
import de.tum.best.states.ListingTypes
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty

// *********
// * Listing Flow *
//
// Listing flow handles the creation of new listings in the marketplace
//
// *********

//ListingFlow initiator is used in order to start the listing creation process
//@params
//  electricityType:    Type of the electricity (renewable, traditional, ...)
//  unitPrice:          Price of one unit of electricity
//  amount:             The amount of electricity this transaction is for
//  matcher:            ID of the matching service node
//  marketClock:         Current market time (@TODO Optional: instead of taking this as an input maybe another way? )
//  transactionType:    Determines the type of the listing (1 -> ProducerListing, 0 -> ConsumerListing)

@InitiatingFlow
@StartableByRPC
class ListingFlowInitiator(private val electricityType: Int,
                           private val unitPrice: Int,
                           private val amount: Int,
                           private val matcher: Party,
                           private val marketClock: Int,
                           private val transactionType: ListingTypes
): FlowLogic<SignedTransaction>() {

    companion object {
        object STEP_ID : ProgressTracker.Step("1. Fetching our identity")
        object STEP_NOTARY : ProgressTracker.Step("2. Fetching notaries")
        object STEP_TYPE : ProgressTracker.Step("3. Determining the listing type")
        object STEP_VERIFY_AND_SIGN : ProgressTracker.Step("4. Verifying and signing")
        object STEP_COLLECT_SIG : ProgressTracker.Step("5. Collecting other parties signatures")
        object STEP_FINALIZE : ProgressTracker.Step("6. Finalizing transaction")

        fun tracker() = ProgressTracker(
            STEP_ID,
            STEP_NOTARY,
            STEP_TYPE,
            STEP_VERIFY_AND_SIGN,
            STEP_COLLECT_SIG,
            STEP_FINALIZE
        )
    }

    override val progressTracker = tracker()

    override fun call(): SignedTransaction {

        // 1.Step: Fetch our address
        progressTracker.currentStep = STEP_ID
        val sender: Party = ourIdentity

        // 2.Step: Get a reference to the notary service on our network and our key pair.
        // Note: ongoing work to support multiple notary identities is still in progress.
        // TODO : Look for a more elegant way
        progressTracker.currentStep = STEP_NOTARY
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        
        val listing = ListingState(transactionType, electricityType, unitPrice, amount, sender, matcher, marketClock)
        val listingBuilder = TransactionBuilder(notary)

        progressTracker.currentStep = STEP_TYPE
        if(transactionType == ListingTypes.ProducerListing){
            // Transaction is of type ProducerListing
            listingBuilder.addCommand(ListingContract.Commands.ProducerListing(), listOf(sender.owningKey, matcher.owningKey))
        } else {
            // Note that this else defaults any errors in transactionType to 0
            listingBuilder.addCommand(ListingContract.Commands.ConsumerListing(), listOf(sender.owningKey, matcher.owningKey))
        }

        listingBuilder.addOutputState(listing)

        // 4.Step: Verify and Sign
        progressTracker.currentStep = STEP_VERIFY_AND_SIGN
        listingBuilder.verify(serviceHub)
        val partiallySignedListingTx = serviceHub.signInitialTransaction(listingBuilder)

        // 5.Step: Collect the other party's signature using the SignTransactionFlow.
        // TODO: Understand!
        progressTracker.currentStep = STEP_COLLECT_SIG
        val otherParties: MutableList<Party> = listing.participants.stream().map { el: AbstractParty? -> el as Party? }.collect(Collectors.toList())
        otherParties.remove(ourIdentity)
        val sessions = otherParties.stream().map { el: Party? -> initiateFlow(el!!) }.collect(Collectors.toList())

        val signedListingTx = subFlow(CollectSignaturesFlow(partiallySignedListingTx, sessions))

        // 6.Step: Assuming no exceptions, we can now finalise the transaction
        // TODO: Understand!
        progressTracker.currentStep = STEP_FINALIZE
        return subFlow<SignedTransaction>(FinalityFlow(signedListingTx, sessions))
    }
}

@InitiatedBy(ListingFlowInitiator::class)
class ListingFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // 1.Step: Check if we are a matching node
        val isMatchingNode: Boolean =  ourIdentity.name.toString().contains("Matching", ignoreCase = true)

        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                //TODO: Also can we just call contract to do sanity check or do we need to verify each field again
                "This node is not authorized to perform matching".using(isMatchingNode)
            }
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
