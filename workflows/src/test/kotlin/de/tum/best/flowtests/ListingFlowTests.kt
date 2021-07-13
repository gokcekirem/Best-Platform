package de.tum.best.flowtests

import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.ListingFlowInitiator
import de.tum.best.flows.RecordTransactionAsObserverFlow
import de.tum.best.states.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListingFlowTests {

    private lateinit var network: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode

    @BeforeEach
    fun setup() {
        network = MockNetwork(
            MockNetworkParameters(
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("de.tum.best.flows"),
                    TestCordapp.findCordapp("de.tum.best.contracts")
                )
            )
        )


        a = network.createNode(MockNodeParameters())
        b = network.createNode(MockNodeParameters().withLegalName(CordaX500Name("mister matching", "", "DE")))

        val startedNodes = arrayListOf(a, b)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.

        startedNodes.forEach {
            it.registerInitiatedFlow(InitiateMarketTimeFlow.Responder::class.java)
            it.registerInitiatedFlow(RecordTransactionAsObserverFlow::class.java)
        }
        network.runNetwork()
    }

    @AfterEach
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `listing flow records listing`() {
        val initiateMarketTimeFlow = InitiateMarketTimeFlow.Initiator(
            b.info.singleIdentity()
        )
        val marketTimeFuture = a.startFlow(initiateMarketTimeFlow)
        network.runNetwork()
        marketTimeFuture.getOrThrow()

        val listingFlow = ListingFlowInitiator(
            ElectricityType.Renewable,
            5,
            3,
            b.info.singleIdentity(),
            ListingType.ProducerListing
        )

        val future = a.startFlow(listingFlow)
        network.runNetwork()
        future.getOrThrow()

        for (node in listOf(a, b)) {
            node.transaction {
                val listingState = node.services.vaultService.queryBy<ListingState>().states.single().state.data
                assertEquals(ListingType.ProducerListing, listingState.listingType)
                assertEquals(ElectricityType.Renewable, listingState.electricityType)
                assertEquals(5, listingState.unitPrice)
                assertEquals(3, listingState.amount)
                assertEquals(a.info.singleIdentity(), listingState.sender)
                assertEquals(b.info.singleIdentity(), listingState.matcher)
                assertEquals(1, listingState.marketClock)
            }
        }
    }
}