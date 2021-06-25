package de.tum.best.states

import de.tum.best.contracts.ListingContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
/**
* Listing State *

*This class is for the "Listing" object in best-marketplace. This class ONLY DENOTES an object,
*functionality is implemented in the corresponding flow and the contract. They type of the Listing
*will be determined by the accompanying "command" (see the Listing Contract).

*@param listingType Type of Listing: ProducerListing (Want to sell...)  or ConsumerListing (Want to buy...)
*@param electricityType Type of the electricity in the listing, will be set to -1 if they type of the listing is "ConsumerListing". 0 -> Renewable, 1 -> Normal. More types can be added
*@param unitPrice Denotes the unit price belonging to this listing
*@param amount Denotes how many units of electricity this listing has. It should be interpreted as for command -> ConsumerListing this is how much electricity the node needs, for command -> ProducerListing this is how much electricity the node would like to sell
*@param sender ID/Address of the node creating this listing
*@param matcher ID/Adress of the node which will be responsible for matching producers and consumers
*@param marketClock Current market clock
*/
@BelongsToContract(ListingContract::class)
data class ListingState(
    val listingType: ListingTypes,
    val electricityType: Int,
    val unitPrice: Int,
    val amount: Int,
    val sender: Party,
    val matcher: Party,
    val marketClock: Int,
    override val participants: List<AbstractParty> = listOf(sender, matcher)
) : ContractState

enum class ListingTypes {
    ProducerListing, ConsumerListing
}