package com.template.webserver

import de.tum.best.states.ListingTypes

class Forms {

    data class MarketTimeForm(
        val partyName: String
    )

    data class ListingForm(
        val electricityType : Int,
        val unitPrice: Int,
        val amount: Int,
        val matcherName: String,
        val transactionType: ListingTypes
    )
}