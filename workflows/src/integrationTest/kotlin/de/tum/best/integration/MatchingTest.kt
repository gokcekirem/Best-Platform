package de.tum.best.integration

import de.tum.best.flows.BroadcastTransactionFlow
import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.ListingFlowInitiator
import de.tum.best.flows.MatchingFlow
import de.tum.best.states.*
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.*
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingTest {

    private val identities = listOf(
        TestIdentity(CordaX500Name("PartyA", "", "GB")),
        TestIdentity(CordaX500Name("mister matching", "", "US")),
        TestIdentity(CordaX500Name("PartyC", "", "DE")),
        TestIdentity(CordaX500Name("PartyD", "", "DE"))
    )
    private val rpcUsers = listOf(
        User("userA", "password1", permissions = setOf("ALL")),
        User("userB", "password2", permissions = setOf("ALL")),
        User("userC", "password3", permissions = setOf("ALL")),
        User("userD", "password4", permissions = setOf("ALL"))
    )
    private val identityUserMap = (identities zip rpcUsers).toMap()


    private fun withDriver(test: DriverDSL.() -> Unit) = driver(
        DriverParameters(
            isDebug = true,
            startNodesInProcess = true,
            cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("de.tum.best.flows"),
                TestCordapp.findCordapp("de.tum.best.contracts")
            )
        )
    ) { test() }

    private fun DriverDSL.startNodes(): Pair<List<NodeHandle>, List<CordaRPCOps>> {
        val nodeHandles = identities
            .map {
                startNode(
                    NodeParameters(
                        providedName = it.name,
                        rpcUsers = listOf(
                            identityUserMap[it] ?: throw IllegalArgumentException("Party $it could not be found")
                        )
                    )
                )
            }
            .map { it.getOrThrow() }

        val clients = nodeHandles.map { CordaRPCClient(it.rpcAddress) }
        val proxies = (identities zip clients).map {
            val user =
                identityUserMap[it.first] ?: throw IllegalArgumentException("Identity ${it.first} could not be found")
            it.second.start(user.username, user.password).proxy
        }
        return Pair(nodeHandles, proxies)
    }

    private fun initiateMarket(
        proxies: List<CordaRPCOps>,
        nodeHandles: List<NodeHandle>
    ) {
        proxies[0].startTrackedFlow(
            InitiateMarketTimeFlow::Initiator,
            nodeHandles[1].nodeInfo.singleIdentity()
        ).returnValue.getOrThrow()

        assertEquals(1, proxies[0].vaultQueryBy<MarketTimeState>().states.first().state.data.marketTime)
    }

    @Test
    fun `single producer and consumer listing are matched`() = withDriver {
        val (nodeHandles, proxies) = startNodes()
        initiateMarket(proxies, nodeHandles)

        proxies[0].startTrackedFlow(
            ::ListingFlowInitiator,
            ElectricityType.Renewable,
            5,
            3,
            nodeHandles[1].nodeInfo.singleIdentity(),
            ListingType.ProducerListing
        ).returnValue.getOrThrow()

        assertEquals(5, proxies[0].vaultQueryBy<ListingState>().states.first().state.data.unitPrice)

        proxies[2].startTrackedFlow(
            ::ListingFlowInitiator,
            ElectricityType.Renewable,
            7,
            3,
            nodeHandles[1].nodeInfo.singleIdentity(),
            ListingType.ConsumerListing
        ).returnValue.getOrThrow()

        assertEquals(7, proxies[1].vaultQueryBy<ListingState>().states.last().state.data.unitPrice)

        proxies[1].startTrackedFlow(
            MatchingFlow::Initiator
        ).returnValue.getOrThrow()

        proxies.slice(0..2).forEach {
            val states = it.vaultQueryBy<MatchingState>().states
            val matchingState = states.single().state.data
            assertEquals(nodeHandles[0].nodeInfo.singleIdentity(), matchingState.seller)
            assertEquals(nodeHandles[1].nodeInfo.singleIdentity(), matchingState.matcher)
            assertEquals(nodeHandles[2].nodeInfo.singleIdentity(), matchingState.buyer)
            assertEquals(5, matchingState.unitPrice)
            assertEquals(3, matchingState.unitAmount)
        }
    }

    @Test
    fun `producer listing must be split`() = withDriver {
        val (nodeHandles, proxies) = startNodes()
        initiateMarket(proxies, nodeHandles)

        proxies[0].startTrackedFlow(
            ::ListingFlowInitiator,
            ElectricityType.Renewable,
            5,
            6,
            nodeHandles[1].nodeInfo.singleIdentity(),
            ListingType.ProducerListing
        ).returnValue.getOrThrow()

        assertEquals(5, proxies[0].vaultQueryBy<ListingState>().states.first().state.data.unitPrice)

        proxies[2].startTrackedFlow(
            ::ListingFlowInitiator,
            ElectricityType.Renewable,
            7,
            3,
            nodeHandles[1].nodeInfo.singleIdentity(),
            ListingType.ConsumerListing
        ).returnValue.getOrThrow()

        assertEquals(7, proxies[1].vaultQueryBy<ListingState>().states.last().state.data.unitPrice)

        proxies[1].startTrackedFlow(
            MatchingFlow::Initiator
        ).returnValue.getOrThrow()

        val states = proxies[0].vaultQueryBy<MatchingState>().states
        val (firstMatchingState, secondMatchingState) = states.first().state.data to states.last().state.data

        assertTrue(
            (firstMatchingState.buyer == nodeHandles[1].nodeInfo.singleIdentity()
                    && secondMatchingState.buyer == nodeHandles[2].nodeInfo.singleIdentity()) ||
                    (firstMatchingState.buyer == nodeHandles[2].nodeInfo.singleIdentity()
                    && secondMatchingState.buyer == nodeHandles[1].nodeInfo.singleIdentity())
        )

        assertTrue(
            firstMatchingState.unitPrice == 5
                    && secondMatchingState.unitPrice == 3 ||
                    firstMatchingState.unitPrice == 3
                    && secondMatchingState.unitPrice == 5
        )

        setOf(firstMatchingState, secondMatchingState).forEach {
            assertEquals(nodeHandles[0].nodeInfo.singleIdentity(), it.seller)
            assertEquals(nodeHandles[1].nodeInfo.singleIdentity(), it.matcher)
            assertEquals(3, it.unitAmount)
        }
    }

}