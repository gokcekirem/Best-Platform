package de.tum.best.webserver

import de.tum.best.states.ElectricityType
import de.tum.best.states.ListingType

class Forms {

    data class MarketTimeForm(
        val partyName: String
    )

    data class ListingForm(
        val electricityType : ElectricityType,
        val unitPrice: Int,
        val amount: Int,
        val matcherName: String,
        val transactionType: ListingType
    )
}