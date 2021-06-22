package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.states.ListingState
import com.template.states.ListingTypes
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.hash
import net.corda.core.flows.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

object MatchingFlow {

    data class Matching(
        val unitPrice: Int,
        val unitAmount: Int,
        val producerStateAndRef: StateAndRef<ListingState>,
        val consumerStateAndRef: StateAndRef<ListingState>
    )

    @InitiatingFlow
    @StartableByRPC
    class Initiator() : FlowLogic<Collection<SignedTransaction>>() {

        companion object {
            // TODO Update Progress Descriptions
            object SEARCHING_STATES : ProgressTracker.Step("Generating transaction based on new IOU.")
            object CALCULATING_UNIT_PRICE : ProgressTracker.Step("Verifying contract constraints.")
            object GENERATING_MATCHINGS : ProgressTracker.Step("Verifying contract constraints.")
            object SPLITING_STATE_FLOW : ProgressTracker.Step("Splitting Transaction according to required amounts."){
                override fun childProgressTracker() = SplitListingStateFlow.Initiator.tracker()
            }
            object EXECUTING_SINGLE_MATCHING_FLOWS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = SingleMatchingFlow.Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                SEARCHING_STATES,
                CALCULATING_UNIT_PRICE,
                GENERATING_MATCHINGS,
                EXECUTING_SINGLE_MATCHING_FLOWS
            )
        }

        override val progressTracker = tracker()

        val matchings = HashSet<Matching>()
        val notary = serviceHub.networkMapCache.notaryIdentities.single()

        @Suspendable
        override fun call(): Collection<SignedTransaction> {


            progressTracker.currentStep = SEARCHING_STATES
            // TODO Maybe check for the current market time, in case matching with retailer does not work
            val listingStateAndRefs = serviceHub.vaultService.queryBy<ListingState>(
                QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            )
                .states
//                .map {
//                    it.state.data
//                }

            val producersStateAndRefs = listingStateAndRefs.filter { it.state.data.listingType == ListingTypes.ProducerListing }
            val consumersStateAndRefs = listingStateAndRefs.filter { it.state.data.listingType == ListingTypes.ConsumerListing }

            val listingStates = listingStateAndRefs.map{it.state.data}
            val producerStates = listingStates.filter { it.listingType == ListingTypes.ProducerListing }
            val consumerStates = listingStates.filter { it.listingType == ListingTypes.ConsumerListing }

            progressTracker.currentStep = CALCULATING_UNIT_PRICE
//            val matchings = HashSet<Matching>()
            val consumerEnergySum = consumerStates.map { it.amount }.sum()
            val producerEnergySum = producerStates.map { it.amount }.sum()
            val participatingProducerStates: List<ListingState>
            // Calculate the Merit Order Price
            val unitPrice = if (consumerEnergySum > producerEnergySum) {
                producerStates.map { it.unitPrice }.max()
            } else {
                val sortedProducerStates = producerStates.sortedBy { it.unitPrice }
                val cumulatedAmounts = sortedProducerStates.fold(listOf<Int>())
                { list, state -> list.plus(list.last() + state.amount) }
                val insertionPoint = -(cumulatedAmounts.binarySearch(consumerEnergySum) + 1)
                participatingProducerStates = sortedProducerStates.subList(0, insertionPoint+1).toList()
                participatingProducerStates.last().unitPrice
            }

            progressTracker.currentStep = SPLITING_STATE_FLOW

            // TODO Retailer has same unit price as the rest of the producers and consumers
            // TODO Split up the state conceptually into unit states for matching
            // TODO Split up the states via a split flow during the matching
            // TODO Randomly map the producers to the consumers

            var consumerStateIterator : Int = 0
            var producerStateIterator = 0

            MatchListings(unitPrice!!, producersStateAndRefs, consumersStateAndRefs, producerStateIterator, consumerStateIterator, SPLITING_STATE_FLOW.childProgressTracker())

            if (consumerEnergySum > producerEnergySum) {
                // TODO Create new ProducerListings with the retailer

            } else {
                // TODO Create new ConsumerListing with the retailer
            }

            progressTracker.currentStep = EXECUTING_SINGLE_MATCHING_FLOWS
            return matchings.map {
                subFlow(
                    SingleMatchingFlow.Initiator(
                        it.producerStateAndRef,
                        it.consumerStateAndRef,
                        it.unitPrice,
                        it.unitAmount,
                        EXECUTING_SINGLE_MATCHING_FLOWS.childProgressTracker()
                    )
                )
            }
        }

        private fun MatchListings(unitPrice:Int, producerStates: List<StateAndRef<ListingState>>, consumerStates: List<StateAndRef<ListingState>>, producerStateIterator: Int, consumerStateIterator: Int, progressTracker: ProgressTracker){
            if(producerStateIterator <= producerStates.size || consumerStateIterator <= consumerStates.size){
                return 
            }

            var match : Matching

            val pListing = producerStates[producerStateIterator]
            val cListing = consumerStates[consumerStateIterator]
            val pAmount = producerStates[producerStateIterator].state.data.amount
            val cAmount = consumerStates[consumerStateIterator].state.data.amount

//            val pListing = StateAndRef(TransactionState(producerStates[producerStateIterator], notary = notary), StateRef(producerStates[producerStateIterator].hash(), 0))
//            val cListing = StateAndRef(TransactionState(consumerStates[consumerStateIterator], notary = notary), StateRef(consumerStates[consumerStateIterator].hash(), 0))

            if (pAmount == cAmount){
                match = Matching(unitPrice, pAmount, pListing, cListing)
                matchings.add(match)

                MatchListings(unitPrice!!, producerStates, consumerStates, producerStateIterator+1, consumerStateIterator+1, progressTracker)
            } else if (pAmount > cAmount){
                // Split Producer Listing State
                val remainderAmount = pAmount - cAmount
                val stx: SignedTransaction = subFlow(SplitListingStateFlow.Initiator(
                    pListing,
                    cAmount,
                    remainderAmount,
                    progressTracker
                ))
                // Get the 2 newly created states
                val ledgerTx = stx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>()
                val pReqListing = ledgerTx.single { it.amount == cAmount }
                val pRemListing = ledgerTx.single { it.amount == remainderAmount }

                match = Matching(unitPrice, cAmount, StateAndRef(TransactionState(pReqListing, notary = notary), StateRef(pReqListing.hash(), 0)), cListing)
                matchings.add(match)

                var updatedProducerStates = producerStates.toMutableList()
                // Add left over energy state to the producers list
                updatedProducerStates.add(index = producerStateIterator + 1, element = StateAndRef(TransactionState(pRemListing, notary = notary), StateRef(pRemListing.hash(), 0)))
                MatchListings(unitPrice!!, updatedProducerStates, consumerStates, producerStateIterator + 1, consumerStateIterator+1, progressTracker)

            } else if (pAmount < cAmount){
                // Split Consumer Listing State
                val remainderAmount = cAmount - pAmount
                val stx: SignedTransaction = subFlow(SplitListingStateFlow.Initiator(
                    cListing,
                    pAmount,
                    remainderAmount,
                    progressTracker
                ))
                // Get the 2 newly created states
                val ledgerTx = stx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>()
                val cReqListing = ledgerTx.single { it.amount == pAmount }
                val cRemListing = ledgerTx.single { it.amount == remainderAmount }

                // Create and add match to matchings list
                match = Matching(unitPrice, pAmount, StateAndRef(TransactionState(cReqListing, notary = notary), StateRef(cReqListing.hash(), 0)), pListing)
                matchings.add(match)

                var updatedConsumerStates = producerStates.toMutableList()
                // Add left over energy state to the producers list
                updatedConsumerStates.add(index = consumerStateIterator + 1, element = StateAndRef(TransactionState(cRemListing, notary = notary), StateRef(cRemListing.hash(), 0)))
                MatchListings(unitPrice!!, producerStates, updatedConsumerStates, producerStateIterator + 1, consumerStateIterator+1, progressTracker)

            }
        }

    }

}
