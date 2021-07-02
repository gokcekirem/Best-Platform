package de.tum.best.flowtests

import de.tum.best.flows.ClearMarketTimeFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.jupiter.api.*
import org.junit.runner.RunWith
import kotlin.test.assertEquals


class InitiateMarketTimeFlowTests {
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


                ),notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary","London","GB")))
            )
        )

        a = network.createNode(MockNodeParameters())
        b = network.createNode(MockNodeParameters())
        val startedNodes = arrayListOf(a, b)
        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        startedNodes.forEach { it.registerInitiatedFlow(ClearMarketTimeFlow.Responder::class.java) }
        network.runNetwork()
    }

    @AfterEach
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the initiator`() {
        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the acceptor`() {
        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `flow records a transaction in both parties' transaction storages`() {
        val flow = ClearMarketTimeFlow.Initiator(b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }
}