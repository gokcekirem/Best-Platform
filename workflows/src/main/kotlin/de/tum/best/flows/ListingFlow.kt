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
import de.tum.best.states.ElectricityType

import net.corda.core.transactions.TransactionBuilder

import de.tum.best.states.ListingState
import de.tum.best.states.ListingType
import de.tum.best.states.MarketTimeState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria

/**
 * Listing Flow *
 * Listing flow handles the creation of new listings in the marketplace
*/

/**
 * ListingFlowInitiator initiator is used in order to start the listing creation process

 *@param electricityType Type of the electricity produced (renewable, traditional, ...)
 *@param unitPrice Price of one unit of electricity
 *@param amount The amount of electricity this transaction is for
 *@param matcher ID of the matching service node
 *@param transactionType Determines the type of the listing (1 -> ProducerListing, 0 -> ConsumerListing)
*/
@InitiatingFlow
@StartableByRPC
class ListingFlowInitiator(private val electricityType: ElectricityType,
                           private val unitPrice: Int,
                           private val amount: Int,
                           private val matcher: Party,
                           private val transactionType: ListingType
): FlowLogic<SignedTransaction>() {

    companion object {
        object STEP_ID : ProgressTracker.Step("1. Fetching our identity")
        object STEP_NOTARY : ProgressTracker.Step("2. Fetching notaries")
        object STEP_TYPE : ProgressTracker.Step("3. Determining the listing type")
        object STEP_CLOCK : ProgressTracker.Step("3.5 Getting market clock")
        object STEP_VERIFY_AND_SIGN : ProgressTracker.Step("4. Verifying and signing")
        object STEP_COLLECT_SIG : ProgressTracker.Step("5. Collecting other parties signatures")
        object STEP_FINALIZE : ProgressTracker.Step("6. Finalizing transaction")

        fun tracker() = ProgressTracker(
            STEP_ID,
            STEP_NOTARY,
            STEP_TYPE,
            STEP_VERIFY_AND_SIGN,
            STEP_COLLECT_SIG,
            STEP_FINALIZE,
            STEP_CLOCK
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        // 1.Step: Fetch our address
        progressTracker.currentStep = STEP_ID
        val sender: Party = ourIdentity

        // 2.Step: Get a reference to the notary service on our network and our key pair.
        // Note: ongoing work to support multiple notary identities is still in progress.
        // TODO : Look for a more elegant way
        progressTracker.currentStep = STEP_NOTARY
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // 3. Step: Conversion to ListingType enum and listing creation & market clock fetch
        progressTracker.currentStep = STEP_CLOCK

        //TODO: We need a flow to fetch current market time, that flow will be called here
        //TODO: Insert the subflow for matching here
        val marketClockQuery = serviceHub.vaultService.queryBy<MarketTimeState>(
            QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
        ).states.single().state.data

        val marketClock = marketClockQuery.marketTime

        progressTracker.currentStep = STEP_TYPE

        val listing = ListingState(transactionType, electricityType, unitPrice, amount, sender, matcher, marketClock)
        val listingBuilder = TransactionBuilder(notary)

        if(transactionType == ListingType.ProducerListing){
            // Transaction is of type ProducerListing
            listingBuilder.addCommand(ListingContract.Commands.ProducerListing(), listOf(sender.owningKey, matcher.owningKey))
        } else {
            // Note that this else defaults any errors in transactionType to ConsumerListing
            listingBuilder.addCommand(ListingContract.Commands.ConsumerListing(), listOf(sender.owningKey, matcher.owningKey))
        }

        listingBuilder.addOutputState(listing)

        // 4.Step: Verify and Sign
        progressTracker.currentStep = STEP_VERIFY_AND_SIGN
        listingBuilder.verify(serviceHub)
        val partiallySignedListingTx = serviceHub.signInitialTransaction(listingBuilder)

        // 5.Step: Collect the other party's signature using the SignTransactionFlow.
        progressTracker.currentStep = STEP_COLLECT_SIG
        val sessions = listing.participants.map {
            it as Party
        }.filterNot {
            it == ourIdentity
        }.map {
            initiateFlow(it)
        }

        val signedListingTx = subFlow(CollectSignaturesFlow(partiallySignedListingTx, sessions))

        // 6.Step: Assuming no exceptions, we can now finalise the transaction
        progressTracker.currentStep = STEP_FINALIZE
        return subFlow<SignedTransaction>(FinalityFlow(signedListingTx, sessions))
    }
}

/**
 * ListingFlowResponder responder is used in order to respond to the listing creation process
 */
@InitiatedBy(ListingFlowInitiator::class)
class ListingFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                //TODO: Also can we just call contract to do sanity check or do we need to verify each field again
                "This node is not authorized to perform matching".using(ourIdentity.name.toString().contains("Matching", ignoreCase = true))
                "Clocks must match!".using(clockSyncVerifier(stx))
            }
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }

    fun clockSyncVerifier(stx: SignedTransaction): Boolean {

        //Get current time info from nodes vault
        val timeQuery = serviceHub.vaultService.queryBy<MarketTimeState>(
            QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
        ).states.single().state.data.marketTime

        //Get listing object from the signed transaction
        return stx.tx.outputsOfType<ListingState>()
            //Perform check for all listings in the transaction
            .all {  it.marketClock == timeQuery }
    }
}
