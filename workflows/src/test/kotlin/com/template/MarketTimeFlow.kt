package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.flows.FinalityFlow

import net.corda.core.flows.CollectSignaturesFlow

import net.corda.core.transactions.SignedTransaction

import java.util.stream.Collectors

import net.corda.core.flows.FlowSession

import net.corda.core.identity.Party

import com.template.contracts.MarketTimeContract

import net.corda.core.transactions.TransactionBuilder

import com.template.states.MarketTimeState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty

@InitiatingFlow
@StartableByRPC
class MarketTimeFlow {
}