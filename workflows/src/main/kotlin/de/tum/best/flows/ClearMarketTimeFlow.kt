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
//import java.util.*


/**
 * Market Clearing (t1) Initiator Flow
 */

object ClearMarketTimeFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
                    val otherParty: Party) : FlowLogic<SignedTransaction>() { // Could also be private?
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATINGTRANSACTION : ProgressTracker.Step("Generating transaction based on new Update on MarketTime .")
            object VERIFYINGTRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNINGTRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERINGSIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISINGTRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
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

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {

            // Obtain a reference from a notary we wish to use.

            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputStateAndRef = serviceHub.vaultService.queryBy<MarketTimeState>(
                QueryCriteria.LinearStateQueryCriteria()
            ).states.first()

            val inputState = inputStateAndRef.state.data
            val outputState = MarketTimeState(inputState.marketClock,inputState.marketTime + 1, serviceHub.myInfo.legalIdentities.first(), otherParty)

            // Stage 1.
            progressTracker.currentStep = GENERATINGTRANSACTION

            // Generate an unsigned transaction.

            val txCommand = Command(MarketTimeContract.Commands.ClearMarketTime(),outputState.participants.map { it.owningKey })
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
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession),
                GATHERINGSIGS.childProgressTracker()
            ))

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
            subFlow(BroadcastTransactionFlow(signedTransaction))

            return signedTransaction
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputsOfType<MarketTimeState>().single()

                    val ourStateRef = stx.inputs.single()
                    val ourStateAndRef: StateAndRef<MarketTimeState> = serviceHub.toStateAndRef<MarketTimeState>(ourStateRef)
                    val inputstate = ourStateAndRef.state.data


                    "MarketTime value after Market Clearing must be equal to 2." using(output.marketTime == 2)
                    //A MarketTime Value other than 2 should not be possible since this is the market clearing flow

                    "marketClock should stay the same after Transaction" using(output.marketClock == inputstate.marketClock)

                    "MarketTime value in the input State must be equal to 1" using(inputstate.marketTime == 1)

                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}