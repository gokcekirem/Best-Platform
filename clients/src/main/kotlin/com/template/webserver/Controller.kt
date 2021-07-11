package com.template.webserver

import de.tum.best.flows.*
import de.tum.best.states.ListingState
import de.tum.best.states.MarketTimeState
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.bind.annotation.*

/**
 * Define your API endpoints here.
 */
@RestController
@RequestMapping("/") // The paths for HTTP requests are relative to this base path.
class Controller(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    @Bean
    fun mappingJackson2HttpMessageConverter(@Autowired rpcConnection: NodeRPCConnection): MappingJackson2HttpMessageConverter {
        val mapper = JacksonSupport.createDefaultMapper(rpcConnection.proxy)
        val converter = MappingJackson2HttpMessageConverter()
        converter.objectMapper = mapper
        return converter
    }

    private val proxy = rpc.proxy

    @GetMapping(value = ["listings"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getListings(): ResponseEntity<List<StateAndRef<ListingState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<ListingState>().states)
    }

    @GetMapping(value = ["market-time"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getMarketTime(): ResponseEntity<StateAndRef<MarketTimeState>> {
        val nullableMarketTimeState = proxy.vaultQueryBy<MarketTimeState>().states.singleOrNull()
        return if (nullableMarketTimeState != null) {
            ResponseEntity.ok(nullableMarketTimeState)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    enum class MarketTimeFlow {
        INITIATE_FLOW,
        CLEAR_FLOW,
        RESET_FLOW
    }

    @PostMapping(value = ["clear-market-time"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun clearMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<String> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.CLEAR_FLOW)
    }

    @PostMapping(value = ["initiate-market-time"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun initiateMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<String> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.INITIATE_FLOW)
    }

    @PostMapping(value = ["reset-market-time"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun resetMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<String> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.RESET_FLOW)
    }

    private fun startMarketTimeFlow(
        marketTimeForm: Forms.MarketTimeForm,
        flowType: MarketTimeFlow
    ): ResponseEntity<String> {
        val partyName = marketTimeForm.partyName
        val partyX500Name = CordaX500Name.parse(partyName)
        val otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name) ?: return ResponseEntity.badRequest()
            .body("Party named $partyName cannot be found.\n")

        return try {
            // This funky stuff with the Enum has to be performed, because Corda does not want to start the flow
            // when simply a constructor (Party) -> FlowLogic<SignedTransaction> is given as a function parameter
            // It simply returns an exception
            val signedTx = when (flowType) {
                MarketTimeFlow.INITIATE_FLOW -> proxy.startTrackedFlow(
                    InitiateMarketTimeFlow::Initiator,
                    otherParty
                ).returnValue.getOrThrow()
                MarketTimeFlow.CLEAR_FLOW -> proxy.startTrackedFlow(
                    ClearMarketTimeFlow::Initiator,
                    otherParty
                ).returnValue.getOrThrow()
                MarketTimeFlow.RESET_FLOW -> proxy.startTrackedFlow(
                    ResetMarketTimeFlow::Initiator,
                    otherParty
                ).returnValue.getOrThrow()
            }
            ResponseEntity.status(HttpStatus.CREATED).body("Transaction id ${signedTx.id} committed to ledger.\n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ex.message!!)
        }
    }

    @PostMapping(value = ["match"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun initiateMatching(): ResponseEntity<Map<String, List<String>>> {
        return try {
            val signedTxs = proxy.startTrackedFlow(MatchingFlow::Initiator).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf("createdTransactions" to
                        signedTxs.map { "Transaction id ${it.id} committed to ledger" })
            )
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(mapOf("error" to listOf(ex.message!!)))
        }
    }

    @PostMapping(value = ["create-listing"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun createListing(@RequestBody listingForm: Forms.ListingForm): ResponseEntity<String> {
        val matcherName = listingForm.matcherName
        val matcherX500Name = CordaX500Name.parse(matcherName)
        val matcherParty = proxy.wellKnownPartyFromX500Name(matcherX500Name) ?: return ResponseEntity.badRequest()
            .body("Matcher named $matcherName cannot be found.\n")

        return try {
            val signedTx = proxy.startTrackedFlow(
                ::ListingFlowInitiator,
                listingForm.electricityType,
                listingForm.unitPrice,
                listingForm.amount,
                matcherParty,
                listingForm.transactionType
            ).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Listing with id ${signedTx.id} committed to ledger.\n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ex.message!!)
        }
    }
}