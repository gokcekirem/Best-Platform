package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.ListingContract
import com.template.states.ListingState
import de.tum.best.contracts.MatchingContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object SplitListingStateFlow {

    class Initiator(
        val listingStateAndRef: StateAndRef<ListingState>,
        val requiredAmount: Int,
        val remainderAmount: Int,
        override val progressTracker: ProgressTracker
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            // TODO Update Progress descriptions
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new IOU.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object FINALISING_TRANSACTION :
                ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
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
            val stx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise the transaction and record the state in the ledger.
            return subFlow(FinalityFlow(stx, listOf(), FINALISING_TRANSACTION.childProgressTracker()))
        }

    }

}
