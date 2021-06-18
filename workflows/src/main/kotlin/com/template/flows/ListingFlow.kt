package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.flows.FinalityFlow

import net.corda.core.flows.CollectSignaturesFlow

import net.corda.core.transactions.SignedTransaction

import java.util.stream.Collectors

import net.corda.core.flows.FlowSession

import net.corda.core.identity.Party

import com.template.contracts.ListingContract

import net.corda.core.transactions.TransactionBuilder

import com.template.states.ListingState
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
//  marketTime:         Current market time (@TODO Optional: instead of taking this as an input maybe another way? )
//  transactionType:    Determines the type of the listing (1 -> ProducerListing, 0 -> ConsumerListing)

@InitiatingFlow
@StartableByRPC
class ListingFlowInitiator(private val electricityType: Int,
                           private val unitPrice: Int,
                           private val amount: Int,
                           private val matcher: Party,
                           private val marketTime: Int,
                           private val transactionType: Int): FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    override fun call(): SignedTransaction {

        val producerListing : Int = 1
        val consumerListing : Int = 2
        // 1.Step: Fetch our address
        val sender: Party = ourIdentity

        // 2.Step: Get a reference to the notary service on our network and our key pair.
        // Note: ongoing work to support multiple notary identities is still in progress.
        // TODO : Look for a more elegant way
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // 3.Step: Create the transaction object
        val listingType = if (transactionType == 1)  producerListing else consumerListing

        val listing = ListingState(listingType, electricityType, unitPrice, amount, sender, matcher, marketTime)
        val listingBuilder = TransactionBuilder(notary)

        if(transactionType == 1){
            // Transaction is of type ProducerListing
            listingBuilder.addCommand(ListingContract.Commands.ProducerListing(), listOf(sender.owningKey, matcher.owningKey))
        } else {
            // Note that this else defaults any errors in transactionType to 0
            listingBuilder.addCommand(ListingContract.Commands.ConsumerListing(), listOf(sender.owningKey, matcher.owningKey))
        }

        listingBuilder.addOutputState(listing)

        // 4.Step: Verify and Sign
        listingBuilder.verify(serviceHub)
        val partiallySignedListingTx = serviceHub.signInitialTransaction(listingBuilder)

        // 5.Step: Collect the other party's signature using the SignTransactionFlow.
        // TODO: Understand!
        val otherParties: MutableList<Party> = listing.participants.stream().map { el: AbstractParty? -> el as Party? }.collect(Collectors.toList())
        otherParties.remove(ourIdentity)
        val sessions = otherParties.stream().map { el: Party? -> initiateFlow(el!!) }.collect(Collectors.toList())

        val signedListingTx = subFlow(CollectSignaturesFlow(partiallySignedListingTx, sessions))

        // 6.Step: Assuming no exceptions, we can now finalise the transaction
        // TODO: Understand!
        return subFlow<SignedTransaction>(FinalityFlow(signedListingTx, sessions))
    }
}

@InitiatedBy(ListingFlowInitiator::class)
class ListingFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // 1.Step: Check if we are a matching node
        val isMatchingNode: Boolean =  if (ourIdentity.name.toString() != null) ourIdentity.name.toString().contains("Matching", ignoreCase = true) else false

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
