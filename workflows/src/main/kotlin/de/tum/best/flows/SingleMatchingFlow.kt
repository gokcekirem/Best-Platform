package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.contracts.ListingContract
import de.tum.best.states.ListingState
import de.tum.best.contracts.MatchingContract
import de.tum.best.states.MatchingState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object SingleMatchingFlow {

    class Initiator(
        val producerStateAndRef: StateAndRef<ListingState>,
        val consumerStateAndRef: StateAndRef<ListingState>,
        val unitPrice: Int,
        val unitAmount: Int,
        override val progressTracker: ProgressTracker
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            // TODO Update Progress descriptions
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new IOU.")
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
            // TODO Make production ready
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val consumer = consumerStateAndRef.state.data.sender
            val producer = producerStateAndRef.state.data.sender
            val matcher = serviceHub.myInfo.legalIdentities.first()
            val matchingState = MatchingState(
                unitPrice, unitAmount,
                consumer,
                producer,
                matcher
            )
            val matchingCommand =
                Command(MatchingContract.Commands.Match(), matchingState.participants.map { it.owningKey })
            val listingCommand =
                Command(ListingContract.Commands.MatchListing(), matchingState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                .addInputState(producerStateAndRef)
                .addInputState(consumerStateAndRef)
                .addOutputState(matchingState, MatchingContract.ID)
                .addCommand(matchingCommand)
                .addCommand(listingCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            val sessions = (setOf(consumer, producer) - matcher).map { initiateFlow(it) }
            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    partSignedTx,
                    sessions,
                    GATHERING_SIGS.childProgressTracker()
                )
            )

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(
                FinalityFlow(
                    fullySignedTx,
                    sessions,
                    FINALISING_TRANSACTION.childProgressTracker()
                )
            )
        }

    }

    @InitiatedBy(MatchingFlow.Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {}
            }
            val txId = subFlow(signTransactionFlow).id
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }

    }

}