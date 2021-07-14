package de.tum.best.integration

import de.tum.best.flows.InitiateMarketTimeFlow
import de.tum.best.flows.ListingFlowInitiator
import de.tum.best.states.ElectricityType
import de.tum.best.states.ListingState
import de.tum.best.states.ListingType
import de.tum.best.states.MarketTimeState
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.expect
import net.corda.testing.core.expectEvents
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

    private val identityA = TestIdentity(CordaX500Name("PartyA", "", "GB"))
    private val identityB = TestIdentity(CordaX500Name("mister matching", "", "US"))
    private val identities = listOf(identityA, identityB)
    private val rpcUserA = User("userA", "password1", permissions = setOf("ALL"))
    private val rpcUserB = User("userB", "password2", permissions = setOf("ALL"))
    private val identityUserMap = mapOf(identityA to rpcUserA, identityB to rpcUserB)


    @Test
    fun `listing flow records listing`() = withDriver {
        // Start a pair of nodes and wait for them both to be ready.
        val parties = identities
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

        val clients = parties.map { CordaRPCClient(it.rpcAddress) }
        val proxies = (identities zip clients).map {
            val user =
                identityUserMap[it.first] ?: throw IllegalArgumentException("Identity ${it.first} could not be found")
            it.second.start(user.username, user.password).proxy
        }

        val marketTimeUpdates = proxies.map { it.vaultTrackBy<MarketTimeState>().updates }
        val listingUpdates = proxies.map { it.vaultTrackBy<ListingState>().updates }

        proxies[0].startTrackedFlow(
            InitiateMarketTimeFlow::Initiator,
            parties[1].nodeInfo.singleIdentity()
        ).returnValue.getOrThrow()

        marketTimeUpdates[0].expectEvents {
            expect { update ->
                println("A got vault update of $update")
                assertEquals(1, update.produced.first().state.data.marketTime)
            }
        }

        proxies[0].startTrackedFlow(
            ::ListingFlowInitiator,
            ElectricityType.Renewable,
            5,
            3,
            parties[1].nodeInfo.singleIdentity(),
            ListingType.ProducerListing
        ).returnValue.getOrThrow()

        listingUpdates[0].expectEvents {
            expect { update ->
                println("A got vault update of $update")
                assertEquals(5, update.produced.first().state.data.unitPrice)
            }
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