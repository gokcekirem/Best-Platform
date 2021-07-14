package de.tum.best.flows

import co.paralleluniverse.fibers.Suspendable
import de.tum.best.states.ElectricityType
import de.tum.best.states.ListingState
import de.tum.best.states.ListingType
import net.corda.core.contracts.StateAndRef
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
    class Initiator : FlowLogic<Collection<SignedTransaction>>() {

        companion object {
            // TODO Update Progress Descriptions
            object SEARCHING_STATES : ProgressTracker.Step("Looking up un-consumed listing states from the vault.")
            object CALCULATING_UNIT_PRICE : ProgressTracker.Step("Calculating merit order unit price.")
            object GENERATING_MATCHINGS_INLCUDING_SPLITTING_SUBFLOW :
                ProgressTracker.Step("Splitting Transaction according to required amounts.") {
                override fun childProgressTracker() = SplitListingStateFlow.Initiator.tracker()
            }

            object EXECUTING_SINGLE_MATCHING_FLOWS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = SingleMatchingFlow.Initiator.tracker()
            }

            fun tracker() = ProgressTracker(
                SEARCHING_STATES,
                CALCULATING_UNIT_PRICE,
                GENERATING_MATCHINGS_INLCUDING_SPLITTING_SUBFLOW,
                EXECUTING_SINGLE_MATCHING_FLOWS
            )
        }

        override val progressTracker = tracker()

        private val matchings = HashSet<Matching>()

        @Suspendable
        override fun call(): Collection<SignedTransaction> {
            progressTracker.currentStep = SEARCHING_STATES
            // TODO Maybe check for the current market time, in case matching with retailer does not work
            val listingStateAndRefs = serviceHub.vaultService.queryBy<ListingState>(
                QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            )
                .states


            // Iterate over consumer/producer preferences
            // Calculate merit order price for each preference
            // Create matchings accordingly
            for (electricityPreference in ElectricityType.values()){
                val listingsByPreference = listingStateAndRefs.filter { it.state.data.electricityType == electricityPreference }

                val producersByPreference = listingsByPreference.filter { it.state.data.listingType == ListingType.ProducerListing }
                val consumersByPreference = listingsByPreference.filter { it.state.data.listingType == ListingType.ConsumerListing }

                val sortedProducersByReference = producersByPreference.sortedBy { it.state.data.unitPrice }

                // TODO What happens if only one of these lists is empty?
                if (producersByPreference.any() && consumersByPreference.any()) {
                    val sortedProducerStates = sortedProducersByReference.map { it.state.data }
                    val consumerStates = consumersByPreference.map { it.state.data }

                    progressTracker.currentStep = CALCULATING_UNIT_PRICE

                    val producerEnergySum = sortedProducerStates.map { it.amount }.sum()
                    val consumerEnergySum = consumerStates.map { it.amount }.sum()

                    // Calculate the Merit Order Price
                    val unitPrice = if (consumerEnergySum >= producerEnergySum) {
                        sortedProducerStates.map { it.unitPrice }.max()
                            ?: throw IllegalArgumentException("The producer state list should not be empty")
                    } else {
                        val cumulatedAmounts = sortedProducerStates.fold(listOf<Int>())
                        { list, state -> list.plus(list.lastOrNull() ?: 0 + state.amount) }
                        val insertionPoint = -(cumulatedAmounts.binarySearch(consumerEnergySum) + 1)
                        val participatingProducerStates = sortedProducerStates.subList(0, insertionPoint + 1).toList()
                        participatingProducerStates.last().unitPrice
                    }
                    //TODO progressTracker within loops?
                    progressTracker.currentStep = GENERATING_MATCHINGS_INLCUDING_SPLITTING_SUBFLOW

                    // Creates matches from client listings and adds them to the global matchings hashset
                    // Returns un matched listings that should be matched to the retailer
                    matchListings(
                        unitPrice,
                        sortedProducersByReference.toMutableList(),
                        consumersByPreference.sortedByDescending { it.state.data.unitPrice }.toMutableList()
                    )
                        // Create matches with the retailer
                        .forEach { unmatchedListing ->
                            matchWithRetailer(unmatchedListing, unitPrice)
                        }
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
            producerStates: MutableList<StateAndRef<ListingState>>,
            consumerStates: MutableList<StateAndRef<ListingState>>
        ): Iterator<StateAndRef<ListingState>> {
            //could possibly have 1 iterator with the current approach
            val producerStateIterator = producerStates.listIterator()
            val consumerStateIterator = consumerStates.listIterator()

            val splittingFlowProgressTracker = GENERATING_MATCHINGS_INLCUDING_SPLITTING_SUBFLOW.childProgressTracker()

            while (producerStateIterator.hasNext() && consumerStateIterator.hasNext()) {

                val producerListing = producerStateIterator.next()
                val consumerListing = consumerStateIterator.next()
                val producerAmount = producerListing.state.data.amount
                val consumerAmount = consumerListing.state.data.amount

                if (producerAmount == consumerAmount) {
                    matchings.apply {
                        add(
                            Matching(unitPrice, producerAmount, producerListing, consumerListing)
                        )
                    }
                } else {
                    matchWithDifferentAmount(
                        producerStateIterator,
                        consumerStateIterator,
                        splittingFlowProgressTracker,
                        unitPrice,
                        producerListing,
                        consumerListing
                    )
                }
            }
            return when {
                producerStateIterator.hasNext() -> producerStateIterator
                consumerStateIterator.hasNext() -> consumerStateIterator
                else -> emptyList<StateAndRef<ListingState>>().iterator()
            }
        }

        private fun matchWithDifferentAmount(
            producerStateIterator: MutableListIterator<StateAndRef<ListingState>>,
            consumerStateIterator: MutableListIterator<StateAndRef<ListingState>>,
            progressTracker: ProgressTracker,
            unitPrice: Int,
            producerListing: StateAndRef<ListingState>,
            consumerListing: StateAndRef<ListingState>
        ) {
            val producerAmount = producerListing.state.data.amount
            val consumerAmount = consumerListing.state.data.amount
            val isProducerSurplus = producerAmount > consumerAmount

            val higherListing = if (isProducerSurplus) producerListing else consumerListing
            val lowerListing = if (isProducerSurplus) consumerListing else producerListing
            val higherAmount = if (isProducerSurplus) producerAmount else consumerAmount
            val lowerAmount = if (isProducerSurplus) consumerAmount else producerAmount

            // Split Higher Listing State
            val remainderAmount = higherAmount - lowerAmount
            val stx = subFlow(
                SplitListingStateFlow.Initiator(
                    higherListing,
                    lowerAmount,
                    remainderAmount,
                    progressTracker
                )
            )
            // Get the 2 newly created states
            val splitListingsStateAndRef = stx.toLedgerTransaction(serviceHub).outRefsOfType<ListingState>()
            val higherRequiredListingStateAndRef =
                splitListingsStateAndRef.single { it.state.data.amount == lowerAmount }
            val higherRemainingListingStateAndRef =
                splitListingsStateAndRef.single { it.state.data.amount == remainderAmount }

            matchings.apply {
                add(
                    Matching(
                        unitPrice,
                        lowerAmount,
                        if (isProducerSurplus) higherRequiredListingStateAndRef else lowerListing,
                        if (isProducerSurplus) lowerListing else higherRequiredListingStateAndRef
                    )
                )
            }

            // Add left over energy state to the producers/consumer list
            // maybe another elegant solution?
            (if (isProducerSurplus) producerStateIterator else consumerStateIterator).apply {
                add(higherRemainingListingStateAndRef)
                // Jump to the previous value to still iterate over the just added value
                previous()
            }
        }

        private fun matchWithRetailer(listingStateAndRef: StateAndRef<ListingState>, unitPrice: Int) {
            val listingState = listingStateAndRef.state.data

            // Penalty awarded to un-matched listings
            val unitPenalty = if (listingState.listingType == ListingType.ProducerListing) -2  else 2

            // Create new listing for the retailer
            val retailerSignedTx = subFlow(
                ListingFlowInitiator(
                    listingState.electricityType, //TODO shouldn't retailers electricity type be set to either traditional or none(if consumer listing)?
                    unitPrice + unitPenalty,
                    listingState.amount,
                    ourIdentity,
                    if (listingState.listingType == ListingType.ProducerListing)
                        ListingType.ConsumerListing
                    else ListingType.ProducerListing
                )
            )
            val retailerStateAndRef =
                retailerSignedTx.toLedgerTransaction(serviceHub).outRefsOfType<ListingState>().first()

            matchings.apply {
                add(
                    Matching(
                        unitPrice + unitPenalty,
                        listingState.amount,
                        if (listingState.listingType == ListingType.ProducerListing)
                            listingStateAndRef
                        else retailerStateAndRef,
                        if (listingState.listingType == ListingType.ProducerListing)
                            retailerStateAndRef
                        else listingStateAndRef
                    )
                )
            }
        }

    }

}
