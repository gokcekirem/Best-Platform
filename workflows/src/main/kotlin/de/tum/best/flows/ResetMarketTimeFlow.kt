package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.contracts.MarketTimeContract
import de.tum.best.states.MarketTimeState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Market Reset (t2) Initiator Flow
 *
 * @see ClearMarketTimeFlow
 * @see InitiateMarketTimeFlow
 */
object ResetMarketTimeFlow {

    /**
     * Initiates the [ResetMarketTimeFlow]
     * @param otherParty the party to initially communicate the [MarketTimeState] to
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        val otherParty: Party
    ) : FlowLogic<SignedTransaction>() { // Could also be private?
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATINGTRANSACTION :
                ProgressTracker.Step("Generating transaction based on new Update on MarketTime .")

            object VERIFYINGTRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNINGTRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERINGSIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISINGTRANSACTION :
                ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            object BROADCAST_TX : ProgressTracker.Step("Broadcast transaction to all nodes")

            fun tracker() = ProgressTracker(
                GENERATINGTRANSACTION,
                VERIFYINGTRANSACTION,
                SIGNINGTRANSACTION,
                GATHERINGSIGS,
                FINALISINGTRANSACTION,
                BROADCAST_TX
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference from a notary we wish to use.

            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputStateAndRef = serviceHub.vaultService.queryBy<MarketTimeState>(
                QueryCriteria.LinearStateQueryCriteria()
            ).states.first()

            val inputState = inputStateAndRef.state.data

            val outputState =
                MarketTimeState(inputState.marketClock + 1, 0, serviceHub.myInfo.legalIdentities.first(), otherParty)

            // Stage 1.
            progressTracker.currentStep = GENERATINGTRANSACTION

            // Generate an unsigned transaction.

            val txCommand =
                Command(MarketTimeContract.Commands.ResetMarketTime(), outputState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary).addInputState(inputStateAndRef)
                .addOutputState(outputState, MarketTimeContract.ID)
                .addCommand(txCommand)


            // Stage 2.
            progressTracker.currentStep = VERIFYINGTRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNINGTRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERINGSIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(otherParty)
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    partSignedTx, setOf(otherPartySession),
                    GATHERINGSIGS.childProgressTracker()
                )
            )

            // Stage 5.
            progressTracker.currentStep = FINALISINGTRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            val signedTransaction = subFlow(
                FinalityFlow(
                    fullySignedTx, setOf(otherPartySession),
                    FINALISINGTRANSACTION.childProgressTracker()
                )
            )

            progressTracker.currentStep = BROADCAST_TX
            subFlow(BroadcastTransactionFlow.Initiator(signedTransaction))

            return signedTransaction
        }
    }

    /**
     * Responds to the [ResetMarketTimeFlow]
     *
     * @param otherPartySession the session which is providing the transaction to sign
     */
    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputsOfType<MarketTimeState>().single()

                    val ourStateRef = stx.inputs.single()
                    val ourStateAndRef: StateAndRef<MarketTimeState> =
                        serviceHub.toStateAndRef<MarketTimeState>(ourStateRef)
                    val inputState = ourStateAndRef.state.data
                    "MarketTime value in the previous (Input) state must be equal to 2" using (inputState.marketTime == 2)

                    "The MarketTime value after Reset must be equal to 0" using (output.marketTime == 0)
                    //A MarketTime Value other than 0 should not be possible since this is the Reset flow

                    "marketClock value in the output State must be 1 unit greater than the one in the input state" using (output.marketClock == inputState.marketClock + 1)

                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}