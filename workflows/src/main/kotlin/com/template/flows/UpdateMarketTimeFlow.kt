package com.template

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.MarketTimeContract
import com.template.states.MarketTimeState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
//import java.util.*

object UpdateMarketTimeFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val marketTime: Int,
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

            fun tracker() = ProgressTracker(
                GENERATINGTRANSACTION,
                VERIFYINGTRANSACTION,
                SIGNINGTRANSACTION,
                GATHERINGSIGS,
                FINALISINGTRANSACTION
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

            // Stage 1.
            progressTracker.currentStep = GENERATINGTRANSACTION
            // Generate an unsigned transaction.

            val marketTimeState = MarketTimeState(marketTime, serviceHub.myInfo.legalIdentities.first(), otherParty)
            val txCommand = Command(MarketTimeContract.Commands.InitiateMarketTime(),marketTimeState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                .addOutputState(marketTimeState, MarketTimeContract.ID)
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
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERINGSIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISINGTRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISINGTRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {

                    val output = stx.tx.outputs.single().data
                    "This must be an MarketTime Update transaction." using (output is MarketTimeState)

                    /**
                    * val marketT = output as MarketTimeState
                    * "MarketTime value must be greater than 0." using (marketT.marketTime > 0)
                    **/

                    val marketT = output as MarketTimeState
                    val inputmarketT = stx.inputs.filterIsInstance<MarketTimeState>().single()
                    "The MarketTime value in the previous (input) state must be lower than the new MarketTime" using (inputmarketT.marketTime < marketT.marketTime )
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}