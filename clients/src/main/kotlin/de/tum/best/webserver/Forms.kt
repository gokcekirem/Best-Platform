package de.tum.best.webserver

import de.tum.best.states.ElectricityType
import de.tum.best.states.ListingType

class Forms {

    /**
     * Data format for initiating a market time related flow via an HTTP call.
     */
    data class MarketTimeForm(
        val partyName: String
    )

    /**
     * Data format for creating a listing via an HTTP call.
     */
    data class ListingForm(
        val electricityType : ElectricityType,
        val unitPrice: Int,
        val amount: Int,
        val matcherName: String,
        val transactionType: ListingType
    )
}