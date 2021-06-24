package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.flows.ListingFlowInitiator
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

    data class UnMatchedListings(
        val listings: List<StateAndRef<ListingState>>,
        val currentIterator: Int
    )

    @InitiatingFlow
    @StartableByRPC
    class Initiator() : FlowLogic<Collection<SignedTransaction>>() {

        companion object {
            // TODO Update Progress Descriptions
            object SEARCHING_STATES : ProgressTracker.Step("Generating transaction based on new IOU.")
            object CALCULATING_UNIT_PRICE : ProgressTracker.Step("Verifying contract constraints.")
            object GENERATING_MATCHINGS : ProgressTracker.Step("Verifying contract constraints.")
            object SPLITING_STATE_FLOW : ProgressTracker.Step("Splitting Transaction according to required amounts.") {
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

            val producersStateAndRefs =
                listingStateAndRefs.filter { it.state.data.listingType == ListingTypes.ProducerListing }
            val consumersStateAndRefs =
                listingStateAndRefs.filter { it.state.data.listingType == ListingTypes.ConsumerListing }

            val listingStates = listingStateAndRefs.map { it.state.data }
            val producerStates = listingStates.filter { it.listingType == ListingTypes.ProducerListing }
            val consumerStates = listingStates.filter { it.listingType == ListingTypes.ConsumerListing }

            progressTracker.currentStep = CALCULATING_UNIT_PRICE

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
                participatingProducerStates = sortedProducerStates.subList(0, insertionPoint + 1).toList()
                participatingProducerStates.last().unitPrice
            }

            progressTracker.currentStep = SPLITING_STATE_FLOW

            // TODO Retailer has same unit price as the rest of the producers and consumers
            // TODO Split up the state conceptually into unit states for matching
            // TODO Split up the states via a split flow during the matching
            // TODO Randomly map the producers to the consumers

            val consumerStateIterator = 0
            val producerStateIterator = 0

            // Creates matches from client listings and adds them to the global matchings hashset
            // Returns un matched listings that should be matched to the retailer
            val unMatchedListings = matchListings(
                unitPrice!!,
                producersStateAndRefs,
                consumersStateAndRefs,
                producerStateIterator,
                consumerStateIterator,
                SPLITING_STATE_FLOW.childProgressTracker()
            )

            // Create matches with the retailer
            if (unMatchedListings.listings.isNotEmpty()) {
                for (i in unMatchedListings.currentIterator until unMatchedListings.listings.size) {
                    matchWithRetailer(unMatchedListings.listings[i], unitPrice)
                }
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

        private fun matchListings(
            unitPrice: Int,
            producerStates: List<StateAndRef<ListingState>>,
            consumerStates: List<StateAndRef<ListingState>>,
            producerStateIterator: Int,
            consumerStateIterator: Int,
            progressTracker: ProgressTracker
        ): UnMatchedListings {
            //could possibly have 1 iterator with the current approach

            if (producerStateIterator == producerStates.size || consumerStateIterator == consumerStates.size) {
                if (producerStates.size > producerStateIterator) {
                    return UnMatchedListings(producerStates, producerStateIterator)
                } else if (consumerStates.size > consumerStateIterator) {
                    return UnMatchedListings(consumerStates, consumerStateIterator)
                } else {
                    return UnMatchedListings(emptyList(), 0)
                }
            }

            val match: Matching

            val producerListing = producerStates[producerStateIterator]
            val consumerListing = consumerStates[consumerStateIterator]
            val producerAmount = producerListing.state.data.amount
            val consumerAmount = consumerListing.state.data.amount

            if (producerAmount == consumerAmount) {
                match = Matching(unitPrice, producerAmount, producerListing, consumerListing)
                matchings.add(match)

                matchListings(
                    unitPrice,
                    producerStates,
                    consumerStates,
                    producerStateIterator + 1,
                    consumerStateIterator + 1,
                    progressTracker
                )
            } else if (producerAmount > consumerAmount) {
                // Split Producer Listing State
                val remainderAmount = producerAmount - consumerAmount
                val stx: SignedTransaction = subFlow(
                    SplitListingStateFlow.Initiator(
                        producerListing,
                        consumerAmount,
                        remainderAmount,
                        progressTracker
                    )
                )
                // Get the 2 newly created states
                val ledgerTx = stx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>()
                val producerRequiredListing = ledgerTx.single { it.amount == consumerAmount }
                val producerRemainingListing = ledgerTx.single { it.amount == remainderAmount }

                match = Matching(
                    unitPrice,
                    consumerAmount,
                    StateAndRef(
                        TransactionState(producerRequiredListing, notary = notary),
                        StateRef(producerRequiredListing.hash(), 0)
                    ),
                    consumerListing
                )
                matchings.add(match)

                val updatedProducerStates = producerStates.toMutableList()
                // Add left over energy state to the producers list
                // maybe another elegant solution?
                updatedProducerStates.add(
                    index = producerStateIterator + 1,
                    element = StateAndRef(
                        TransactionState(producerRemainingListing, notary = notary),
                        StateRef(producerRemainingListing.hash(), 0)
                    )
                )
                matchListings(
                    unitPrice,
                    updatedProducerStates,
                    consumerStates,
                    producerStateIterator + 1,
                    consumerStateIterator + 1,
                    progressTracker
                )

            } else if (producerAmount < consumerAmount) {
                // Split Consumer Listing State
                val remainderAmount = consumerAmount - producerAmount
                val stx: SignedTransaction = subFlow(
                    SplitListingStateFlow.Initiator(
                        consumerListing,
                        producerAmount,
                        remainderAmount,
                        progressTracker
                    )
                )
                // Get the 2 newly created states
                val ledgerTx = stx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>()
                val consumerRequiredListing = ledgerTx.single { it.amount == producerAmount }
                val consumerRemainingListing = ledgerTx.single { it.amount == remainderAmount }

                // Create and add match to matching list
                match = Matching(
                    unitPrice,
                    producerAmount,
                    producerListing,
                    StateAndRef(
                        TransactionState(consumerRequiredListing, notary = notary),
                        StateRef(consumerRequiredListing.hash(), 0)
                    )
                )
                matchings.add(match)

                val updatedConsumerStates = consumerStates.toMutableList()
                // Add left over energy state to the consumers list
                // maybe another elegant solution?
                updatedConsumerStates.add(
                    index = consumerStateIterator + 1,
                    element = StateAndRef(
                        TransactionState(consumerRemainingListing, notary = notary),
                        StateRef(consumerRemainingListing.hash(), 0)
                    )
                )
                matchListings(
                    unitPrice,
                    producerStates,
                    updatedConsumerStates,
                    producerStateIterator + 1,
                    consumerStateIterator + 1,
                    progressTracker
                )

            }
            return UnMatchedListings(emptyList(), 0)
        }

        private fun matchWithRetailer(listingStateAndRef: StateAndRef<ListingState>, unitPrice: Int) {
            val listingState = listingStateAndRef.state.data
            val match: Matching
            val retailerSignedTx: SignedTransaction
            if (listingState.listingType == ListingTypes.ProducerListing) {
                retailerSignedTx = subFlow(
                    ListingFlowInitiator(
                        listingState.electricityType,
                        unitPrice,
                        listingState.amount,
                        ourIdentity,
                        0,
                        ListingTypes.ConsumerListing
                    )
                )
                val retailerState =
                    retailerSignedTx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>().first()
                val retailerStateAndRef =
                    StateAndRef(TransactionState(retailerState, notary = notary), StateRef(retailerState.hash(), 0))
                match = Matching(unitPrice, listingState.amount, listingStateAndRef, retailerStateAndRef)
            } else {
                retailerSignedTx = subFlow(
                    ListingFlowInitiator(
                        listingState.electricityType,
                        unitPrice,
                        listingState.amount,
                        ourIdentity,
                        0,
                        ListingTypes.ProducerListing
                    )
                )
                val retailerState =
                    retailerSignedTx.toLedgerTransaction(serviceHub).outputsOfType<ListingState>().first()
                val retailerStateAndRef =
                    StateAndRef(TransactionState(retailerState, notary = notary), StateRef(retailerState.hash(), 0))
                match = Matching(unitPrice, listingState.amount, retailerStateAndRef, listingStateAndRef)
            }

            matchings.add(match)
        }

    }

}
