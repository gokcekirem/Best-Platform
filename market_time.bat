@echo off
if "%1" == "initiate" (
 curl --location --request POST localhost:50006/initiate-market-time^
 --header "Content-Type: application/json" --data-raw "{\"partyName\": \"O=PartyA,L=London,C=GB\"}"
)
if "%1" == "clear" (
 curl --location --request POST localhost:50006/clear-market-time^
 --header "Content-Type: application/json" --data-raw "{\"partyName\": \"O=PartyA,L=London,C=GB\"}"
)
if "%1" == "reset" (
 curl --location --request POST localhost:50006/reset-market-time^
 --header "Content-Type: application/json" --data-raw "{\"partyName\": \"O=PartyA,L=London,C=GB\"}"
)
if "%1" == "match" (
 curl --location --request POST localhost:50006/match
)