package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.contracts.MarketTimeContract
import de.tum.best.states.MarketTimeState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker


/**
 * Accept ask and bids (t1) period initiator flow
 */

object InitiateMarketTimeFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
                    val otherParty: Party) : FlowLogic<SignedTransaction>() { // Could also be private?
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATINGTRANSACTION : ProgressTracker.Step("Generating transaction based on new MarketTime Initiation.")
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

            val queryCriteria = QueryCriteria.VaultQueryCriteria() //Default is UNCONSUMED,

            val vaultPage = serviceHub.vaultService.queryBy<MarketTimeState>(queryCriteria)
            var outputState: MarketTimeState
            var txBuilder: TransactionBuilder

            //If-Else condition to check if there is a history of MarketTimeStates in the vault
            // ( The MarketTime is being created for the first time or not)

            // Stage 1.
            progressTracker.currentStep = GENERATINGTRANSACTION

            // Generate an unsigned transaction based on the given conditions

            if (vaultPage.states.any() ){
                // true, if there exists a StateandRef of type MarketTimeState in the extracted Vault Page

                val inputStateAndRef = vaultPage.states[0]
                val inputState = inputStateAndRef.state.data
                outputState =  MarketTimeState(inputState.marketClock,inputState.marketTime + 1, serviceHub.myInfo.legalIdentities.first(), otherParty)
                val txCommand = Command(MarketTimeContract.Commands.InitiateMarketTime(),outputState.participants.map { it.owningKey })
                txBuilder = TransactionBuilder(notary).addInputState(inputStateAndRef).addOutputState(outputState, MarketTimeContract.ID)
                    .addCommand(txCommand)
            }
            //If this is indeed the initiation of the Market, thus Markettime concept;
            else{
                outputState = MarketTimeState(0,1, serviceHub.myInfo.legalIdentities.first(), otherParty)

                val txCommand = Command(MarketTimeContract.Commands.InitiateMarketTime(),outputState.participants.map { it.owningKey })

                txBuilder = TransactionBuilder(notary).addOutputState(outputState, MarketTimeContract.ID).addCommand(txCommand)
            }



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
                    val output = stx.tx.outputsOfType<MarketTimeState>().single()
                    val inputsaslist = stx.inputs.filterIsInstance<MarketTimeState>()

                    if (inputsaslist.any()) {
                        val inputmarketT = inputsaslist.single()
                        "MarketTime value in the previous (Input) state must be equal to 0." using (inputmarketT.marketTime == 0)

                        val marketT = output
                        "MarketTime value must be equal to 1 after initialization." using (marketT.marketTime == 1)
                        //A MarketTime Value other than 1 should not be possible since this is the initiation flow
                    }

                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}