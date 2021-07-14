package de.tum.best.integration

import de.tum.best.flows.BroadcastTransactionFlow
import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.ListingFlowInitiator
import de.tum.best.flows.MatchingFlow
import de.tum.best.states.*
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MatchingTest {

    private val identities = listOf(
        TestIdentity(CordaX500Name("PartyA", "", "GB")),
        TestIdentity(CordaX500Name("mister matching", "", "US")),
        TestIdentity(CordaX500Name("PartyC", "", "DE"))
    )
    private val rpcUsers = listOf(
        User("userA", "password1", permissions = setOf("ALL")),
        User("userB", "password2", permissions = setOf("ALL")),
        User("userC", "password3", permissions = setOf("ALL"))
    )
    private val identityUserMap = (identities zip rpcUsers).toMap()


    @Test
    fun `single producer and consumer listing are matched`() = withDriver {
        // Start a pair of nodes and wait for them both to be ready.
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

        val signedMarketTimeTransaction = proxies[0].startTrackedFlow(
            InitiateMarketTimeFlow::Initiator,
            nodeHandles[1].nodeInfo.singleIdentity()
        ).returnValue.getOrThrow()

        assertEquals(1, proxies[0].vaultQueryBy<MarketTimeState>().states.first().state.data.marketTime)

        proxies[0].startTrackedFlow(
            ::BroadcastTransactionFlow,
            signedMarketTimeTransaction
        ).returnValue.getOrThrow()

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

        proxies.forEach {
            val matchingState = it.vaultQueryBy<MatchingState>().states.single().state.data
            assertEquals(nodeHandles[0].nodeInfo.singleIdentity(), matchingState.seller)
            assertEquals(nodeHandles[1].nodeInfo.singleIdentity(), matchingState.matcher)
            assertEquals(nodeHandles[2].nodeInfo.singleIdentity(), matchingState.buyer)
            assertEquals(5, matchingState.unitPrice)
            assertEquals(3, matchingState.unitAmount)
        }
    }

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

}