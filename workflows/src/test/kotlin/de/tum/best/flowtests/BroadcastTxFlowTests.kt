package de.tum.best.flowtests

import de.tum.best.flows.BroadcastTransactionFlow
import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.RecordTransactionAsObserverFlow
import de.tum.best.states.MarketTimeState
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals


/**
 * MarketTimeFlowTests for testing the flows with respect to the requirements relating to:
 * the Corda Network
 * Attributes (marketClock and MarketTime) of the MarketTimeState in input and output state with respect to each other
 * MarketTimeContract
 *
 *
 */

class BroadcastTxFlowTests {
    private lateinit var network: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var c: StartedMockNode
    private lateinit var d: StartedMockNode

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
        c = network.createNode(MockNodeParameters())
        d = network.createNode(MockNodeParameters())

        val startedNodes = arrayListOf(a, b,c,d)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.

        startedNodes.forEach { it.registerInitiatedFlow(InitiateMarketTimeFlow.Responder::class.java) }
        startedNodes.forEach{it.registerInitiatedFlow(RecordTransactionAsObserverFlow::class.java)}
        network.runNetwork()
    }

    @AfterEach
    fun tearDown() {
        network.stopNodes()
    }

    /**
     * Tests for Transaction broadcasting flows
     */
    @Test
    fun `flow records a transaction in ALL parties' transaction storages`() {
        val flow = InitiateMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        val broadcast = BroadcastTransactionFlow(signedTx)
        a.startFlow(broadcast)
        network.runNetwork()

        val criteria = QueryCriteria.LinearStateQueryCriteria()
        val requiredMarketTime = 1
        val requiredMarketClock = 0
        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b,c,d)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
            assertEquals(requiredMarketTime,node.services.vaultService.queryBy<MarketTimeState>(criteria).states.last().state.data.marketTime)
            assertEquals(requiredMarketClock,node.services.vaultService.queryBy<MarketTimeState>(criteria).states.last().state.data.marketClock)

        }
    }

}