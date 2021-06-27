package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.contracts.MarketTimeContract
import de.tum.best.states.MarketTimeState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import org.apache.commons.lang3.ObjectUtils

class GetMarketTimeFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        val otherParty: Party) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATINGTRANSACTION : ProgressTracker.Step("Generating transaction based on getting MarketTime State.")
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

            // Query the vault to fetch a list of all MarketTimeState states
            // to fetch the desired MarketTimeState state from the vault and get the last state (unconsumed input State).
            // This filtered state would be used as input to the transaction.

            val queryCriteria = QueryCriteria.VaultQueryCriteria(
                Vault.StateStatus.UNCONSUMED,
                null,
                null)

            //Logic:
            //receive the MarketTime from the matching node, called receivedState
            //if vaultState is empty, no input, outputState=receivedState
            //else outputState=receivedState, inputState=vaultState

            val marketTimeStateAndRefs = serviceHub.vaultService.queryBy<MarketTimeState>(queryCriteria).states
            val vaultStateAndRef = marketTimeStateAndRefs[0]
            var vaultState:MarketTimeState

            try {
                // assuming the MarketTime object already exists
                vaultState = vaultStateAndRef.state.data

            } catch (e: NoSuchElementException) {
                // handler, initial MarketTime creation with MarketClock=0, MarketTime=1
                vaultState = MarketTimeState(0,1, serviceHub.myInfo.legalIdentities.first(), otherParty)
            }

            // no updates, just fetch the Market Time state, override issue?
            val outputState = vaultState



            // Stage 1.
            progressTracker.currentStep = GENERATINGTRANSACTION

            // Generate an unsigned transaction.

            val txCommand = Command(MarketTimeContract.Commands.GetMarketTime(),outputState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary).addInputState(vaultStateAndRef)
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
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession),
                FINALISINGTRANSACTION.childProgressTracker()
            ))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {

                    //vaultState and outputState must be equal

                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}