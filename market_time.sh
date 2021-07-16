if [ "$1" == "initiate" ]; then
  curl --location --request POST 'localhost:50006/initiate-market-time' \
    --header 'Content-Type: application/json' \
    --data-raw '{
    "partyName": "O=PartyA,L=London,C=GB"
    }'
elif [ "$1" == "clear" ]; then
  curl --location --request POST 'localhost:50006/clear-market-time' \
    --header 'Content-Type: application/json' \
    --data-raw '{
    "partyName": "O=PartyA,L=London,C=GB"
    }'
elif [ "$1" == "reset" ]; then
  curl --location --request POST 'localhost:50006/reset-market-time' \
    --header 'Content-Type: application/json' \
    --data-raw '{
    "partyName": "O=PartyA,L=London,C=GB"
    }'
fi
