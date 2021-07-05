package de.tum.best.flowtests

import de.tum.best.contracts.MarketTimeContract
import de.tum.best.flows.ClearMarketTimeFlow
import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.ResetMarketTimeFlow
import de.tum.best.states.MarketTimeState
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import net.corda.core.contracts.StateAndRef
import net.corda.core.node.services.vault.QueryCriteria


/**
 * MarketTimeFlowTests for testing the flows with respect to the requirements relating to:
 * the Corda Network
 * Attributes (marketClock and MarketTime) of the MarketTimeState in input and output state with respect to each other
 * MarketTimeContract
 *
 *
 */

class MarketTimeFlowTests {
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
        b = network.createNode(MockNodeParameters())

        val startedNodes = arrayListOf(a, b)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.

        startedNodes.forEach { it.registerInitiatedFlow(InitiateMarketTimeFlow.Responder::class.java) }
        startedNodes.forEach { it.registerInitiatedFlow(ClearMarketTimeFlow.Responder::class.java) }
        //startedNodes.forEach { it.registerInitiatedFlow(ResetMarketTimeFlow.Responder::class.java) }
        network.runNetwork()
    }

    @AfterEach
    fun tearDown() {
        network.stopNodes()
    }

    /**
     * Tests for InitialMarketTimeFlow
     */


    @Test
    fun `SignedTransaction returned by the InitiateMarketTimeFlow is signed by the initiator`() {
        val flow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the acceptor`() {
        val flow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `flow records a transaction in both parties' transaction storages`() {
        val flow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    //No Input, Market is created for the first time
    @Test
    fun `flow records the correct MarketTimeState after InitiateMarketTimeFlow in both parties' vaults`() {
        val outputmarketClock = 0
        val outputmarketTime = 1
        val flow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded MarketTimeState in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val markettimestates = node.services.vaultService.queryBy<MarketTimeState>().states
                assertEquals(1, markettimestates.size)
                val recordedState = markettimestates.single().state.data
                assertEquals(recordedState.marketClock, outputmarketClock)
                assertEquals(recordedState.marketTime, outputmarketTime)
                assertEquals(recordedState.sender, a.info.singleIdentity())
                assertEquals(recordedState.receiver, b.info.singleIdentity())
            }
        }
    }

    /**
     * Tests for ClearMarketTimeFlow
     */


    @Test
    fun `SignedTransaction returned by the ClearMarketTimeFlow is signed by the initiator`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)

        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the ClearMarketTimeFlow is signed by the acceptor`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val past = a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `ClearMarketTimeFlow records a transaction in both parties' transaction storages`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val past = a.startFlow(preflow)
        network.runNetwork()
        past.getOrThrow()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    //InitiateMarketTimeFlow has to come first since the remaining 2 Markettime Flows query the unconsumed MarketTime state a
    // and use it as Input to the transaction
    @Test
    fun `flow records the correct MarketTimeState after ClearMarketTimeFlow in both parties' vaults`() {
        val outputmarketClock = 0
        val outputmarketTime = 2
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val past = a.startFlow(preflow)
        network.runNetwork()
        past.getOrThrow()
        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        future.getOrThrow()

        // We check the recorded MarketTimeState in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val markettimestates = node.services.vaultService.queryBy<MarketTimeState>().states
                assertEquals(1, markettimestates.size)
                val recordedState = markettimestates.single().state.data
                assertEquals(recordedState.marketClock, outputmarketClock)
                assertEquals(recordedState.marketTime, outputmarketTime)
                assertEquals(recordedState.sender, a.info.singleIdentity())
                assertEquals(recordedState.receiver, b.info.singleIdentity())
            }
        }

    }

    /*
    ResetMarketTimeFlow Tests
     */
    @Test
    fun `SignedTransaction returned by the ResetMarketTimeFlow is signed by the initiator`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present = a.startFlow(flow)
        network.runNetwork()

        val postflow = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(postflow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the ResetMarketTimeFlow is signed by the acceptor`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present = a.startFlow(flow)
        network.runNetwork()

        val postflow = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(postflow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `ResetMarketTimeFlow records a transaction in both parties' transaction storages`() {
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present = a.startFlow(flow)
        network.runNetwork()

        val postflow = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(postflow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    //InitiateMarketTimeFlow has to come first since the remaining 2 Markettime Flows query the unconsumed MarketTime state a
    // and use it as Input to the transaction
    @Test
    fun `flow records the correct MarketTimeState after ResetMarketTimeFlow in both parties' vaults`() {
        val outputmarketClock = 1
        val outputmarketTime = 0
        val preflow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow)
        network.runNetwork()

        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present = a.startFlow(flow)
        network.runNetwork()

        val postflow = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(postflow)
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded MarketTimeState in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val markettimestates = node.services.vaultService.queryBy<MarketTimeState>().states
                assertEquals(1, markettimestates.size)
                val recordedState = markettimestates.single().state.data
                assertEquals(recordedState.marketClock, outputmarketClock)
                assertEquals(recordedState.marketTime, outputmarketTime)
                assertEquals(recordedState.sender, a.info.singleIdentity())
                assertEquals(recordedState.receiver, b.info.singleIdentity())
            }
        }

    }
    // The following test checks if flows generate the correct states through transactions after Market stage loops
    @Test
    fun `flow records the correct MarketTimeState after 2 Market loops in both parties' vaults`() {
        val outputmarketClock = 2
        val outputmarketTime = 1
        val preflow1 = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow1)
        network.runNetwork()

        val flow1 = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present1 = a.startFlow(flow1)
        network.runNetwork()

        val postflow1 = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future1 = a.startFlow(postflow1)
        network.runNetwork()

        val preflow2 = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow2)
        network.runNetwork()

        val flow2 = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val present2 = a.startFlow(flow2)
        network.runNetwork()

        val postflow2 = ResetMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future2 = a.startFlow(postflow2)
        network.runNetwork()

        val preflow3 = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        a.startFlow(preflow3)
        network.runNetwork()
        // We check the recorded MarketTimeState in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val markettimestates = node.services.vaultService.queryBy<MarketTimeState>().states
                assertEquals(1, markettimestates.size)
                val recordedState = markettimestates.single().state.data
                assertEquals(recordedState.marketClock, outputmarketClock)
                assertEquals(recordedState.marketTime, outputmarketTime)
                assertEquals(recordedState.sender, a.info.singleIdentity())
                assertEquals(recordedState.receiver, b.info.singleIdentity())
            }
        }

    }
}
