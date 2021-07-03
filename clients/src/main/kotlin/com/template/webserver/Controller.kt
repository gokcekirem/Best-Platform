package com.template.webserver

import de.tum.best.flows.ClearMarketTimeFlow.Initiator
import net.corda.client.jackson.JacksonSupport
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

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
    open fun mappingJackson2HttpMessageConverter(@Autowired rpcConnection: NodeRPCConnection): MappingJackson2HttpMessageConverter {
        val mapper = JacksonSupport.createDefaultMapper(rpcConnection.proxy)
        val converter = MappingJackson2HttpMessageConverter()
        converter.objectMapper = mapper
        return converter
    }

    private val proxy = rpc.proxy

    @PostMapping(
        value = ["clear-market-time"],
        produces = [MediaType.TEXT_PLAIN_VALUE],
        headers = ["Content-Type=${MediaType.APPLICATION_FORM_URLENCODED_VALUE}"]
    )
    fun clearMarketTime(request: HttpServletRequest): ResponseEntity<String> {
        val partyName = request.getParameter("partyName") ?: return ResponseEntity.badRequest()
            .body("Query parameter 'partyName' must not be null.\n")
        val partyX500Name = CordaX500Name.parse(partyName)
        val otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name) ?: return ResponseEntity.badRequest()
            .body("Party named $partyName cannot be found.\n")

        return try {
            val signedTx = proxy.startTrackedFlow(::Initiator, otherParty).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Transaction id ${signedTx.id} committed to ledger.\n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ex.message!!)
        }
    }
}