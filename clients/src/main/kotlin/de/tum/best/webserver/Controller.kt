package de.tum.best.webserver

import de.tum.best.flows.*
import de.tum.best.states.ListingState
import de.tum.best.states.MarketTimeState
import de.tum.best.states.MatchingState
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Define your API endpoints here.
 */
@RestController
@RequestMapping("/") // The paths for HTTP requests are relative to this base path.
class Controller(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    /**
     * Provides a converter to serialize native Corda objects to JSON responses
     */
    @Bean
    fun mappingJackson2HttpMessageConverter(@Autowired rpcConnection: NodeRPCConnection): MappingJackson2HttpMessageConverter {
        val mapper = JacksonSupport.createDefaultMapper(rpcConnection.proxy)
        val converter = MappingJackson2HttpMessageConverter()
        converter.objectMapper = mapper
        return converter
    }

    private val proxy = rpc.proxy

    /**
     * Tries to call the [function] and return the [ResponseEntity].
     * If the call throws a [Throwable], it rethrows it as a [ResponseStatusException]
     * with an [HttpStatus.INTERNAL_SERVER_ERROR].
     */
    private fun <T> tryFunctionAndRethrowError(function: () -> ResponseEntity<T>): ResponseEntity<T> {
        try {
            return function()
        } catch (ex: Throwable) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred with the message '${ex.message}'",
                ex
            )
        }
    }

    /**
     * Get mapping at `/listings` that returns all unconsumed listings the node knows of. 
     */
    @CrossOrigin
    @GetMapping(value = ["listings"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getListings(): ResponseEntity<List<StateAndRef<ListingState>>> {
        return tryFunctionAndRethrowError { ResponseEntity.ok(proxy.vaultQueryBy<ListingState>().states) }
    }

    /**
     * Get mapping at `/matchings` that returns all unconsumed matchings the node knows of.
     *
     * In the case of a regular node this would be all the matchings, the node was part of.
     */
    @CrossOrigin
    @GetMapping(value = ["matchings"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getMatchings(): ResponseEntity<List<StateAndRef<MatchingState>>> {
        return tryFunctionAndRethrowError { ResponseEntity.ok(proxy.vaultQueryBy<MatchingState>().states) }
    }

    /**
     * Get mapping at `/name` that returns the organization name of the node.
     */
    @CrossOrigin
    @GetMapping(value = ["name"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getName(): ResponseEntity<Map<String, String>> {
        return tryFunctionAndRethrowError {
            ResponseEntity.ok(mapOf("name" to proxy.nodeInfo().legalIdentities.single().name.organisation))
        }
    }

    /**
     * Get mapping at `/market-time` that returns the current market time the node knows of.
     */
    @CrossOrigin
    @GetMapping(value = ["market-time"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getMarketTime(): ResponseEntity<StateAndRef<MarketTimeState>> {
        val nullableMarketTimeState = proxy.vaultQueryBy<MarketTimeState>().states.singleOrNull()
        return if (nullableMarketTimeState != null) {
            ResponseEntity.ok(nullableMarketTimeState)
        } else {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "The market time could not be found")
        }
    }

    enum class MarketTimeFlow {
        INITIATE_FLOW,
        CLEAR_FLOW,
        RESET_FLOW
    }

    /**
     * Post mapping at `/clear-market-time` that clears the market time.
     */
    @CrossOrigin
    @PostMapping(value = ["clear-market-time"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<SignedTransaction> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.CLEAR_FLOW)
    }

    /**
     * Post mapping at `/initiate-market-time` that initiates the market time.
     */
    @CrossOrigin
    @PostMapping(value = ["initiate-market-time"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun initiateMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<SignedTransaction> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.INITIATE_FLOW)
    }

    /**
     * Post mapping at `/reset-market-time` that resets the market time.
     */
    @CrossOrigin
    @PostMapping(value = ["reset-market-time"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun resetMarketTime(@RequestBody marketTimeForm: Forms.MarketTimeForm): ResponseEntity<SignedTransaction> {
        return startMarketTimeFlow(marketTimeForm, MarketTimeFlow.RESET_FLOW)
    }

    private fun startMarketTimeFlow(
        marketTimeForm: Forms.MarketTimeForm,
        flowType: MarketTimeFlow
    ): ResponseEntity<SignedTransaction> {
        val partyName = marketTimeForm.partyName
        val partyX500Name = CordaX500Name.parse(partyName)
        val otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name) ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Party named $partyName cannot be found."
        )

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
            ResponseEntity.status(HttpStatus.CREATED).body(signedTx)
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.message, ex)
        }
    }

    /**
     * Post mapping at `/match` that initiates the matching procedure.
     */
    @CrossOrigin
    @PostMapping(value = ["match"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun initiateMatching(): ResponseEntity<Collection<SignedTransaction>> {
        return try {
            val signedTxs = proxy.startTrackedFlow(MatchingFlow::Initiator).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(signedTxs)
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.message, ex)
        }
    }

    /**
     * Post mapping at `/create-listing` that creates a listing.
     */
    @CrossOrigin
    @PostMapping(value = ["create-listing"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createListing(@RequestBody listingForm: Forms.ListingForm): ResponseEntity<SignedTransaction> {
        val matcherName = listingForm.matcherName
        val matcherX500Name = CordaX500Name.parse(matcherName)
        val matcherParty = proxy.wellKnownPartyFromX500Name(matcherX500Name) ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Matcher named $matcherName cannot be found."
        )

        return try {
            val signedTx = proxy.startTrackedFlow(
                ::ListingFlowInitiator,
                listingForm.electricityType,
                listingForm.unitPrice,
                listingForm.amount,
                matcherParty,
                listingForm.transactionType
            ).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(signedTx)
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.message, ex)
        }
    }
}