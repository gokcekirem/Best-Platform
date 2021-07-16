package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.contracts.ListingContract
import de.tum.best.states.ListingState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Splits a listing in two different listings for further matching
 *
 * @see MatchingFlow
 */
object SplitListingStateFlow {

    /**
     * Initiates the [SplitListingStateFlow]
     *
     * @param listingStateAndRef the listing to split
     * @param requiredAmount the amount that is required for one of the matchings to be created
     * @param remainderAmount the amount left over from the original listing when splitting away the [requiredAmount]
     * @param progressTracker the [ProgressTracker] to use in this subflow
     */
    class Initiator(
        val listingStateAndRef: StateAndRef<ListingState>,
        val requiredAmount: Int,
        val remainderAmount: Int,
        override val progressTracker: ProgressTracker
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on input Amounts.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION :
                ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {

            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val requiredListingState = listingStateAndRef.state.data.copy(amount = requiredAmount)
            val leftOverListingState = listingStateAndRef.state.data.copy(amount = remainderAmount)

            val txCommand = Command(ListingContract.Commands.SplitTx(), listOf(ourIdentity.owningKey))
            val txBuilder = TransactionBuilder(notary)
                .addInputState(listingStateAndRef)
                .addOutputState(requiredListingState, ListingContract.ID)
                .addOutputState(leftOverListingState, ListingContract.ID)
                .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION
            val senderSession = initiateFlow(listingStateAndRef.state.data.sender)
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    partSignedTx,
                    setOf(senderSession),
                    GATHERING_SIGS.childProgressTracker()
                )
            )

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise the transaction and record the state in the ledger.
            return subFlow(
                FinalityFlow(
                    fullySignedTx,
                    setOf(senderSession),
                    FINALISING_TRANSACTION.childProgressTracker()
                )
            )
        }

    }

}
