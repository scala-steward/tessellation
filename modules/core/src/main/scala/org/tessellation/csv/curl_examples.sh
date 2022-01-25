
#curl -s -X POST http://127.0.0.1:9002/cluster/join -H 'Content-type: application/json' -d '{ \"ip\": \"$headIP\", \"p2pPort\": \"9001\", \"id\": \"$headId\" }'"


export MAIN_NODE_ID="54.241.85.92"

# tail -f constellation/logs/app.log

# Sample debug endpoint for spreading string gossip
curl -s -X POST http://$MAIN_NODE_ID:9000/debug/gossip/spread/asdf4

# Sample debug invalid transport signature detection -- detected by main node
curl -s -X POST http://$MAIN_NODE_ID:9000/debug/gossip/spread/invalid

# tail -n 200 constellation/logs/app.log

# Test key pairs
# curl -s -X GET http://$MAIN_NODE_ID:9000/csv/test
# export MAIN_NODE_ID="54.241.85.92"

# Create a state channel from simple invalid data
curl -s -X POST http://$MAIN_NODE_ID:9000/csv/upload -H 'Content-type: application/json' -d '{"data": "asdf", "channelName": "invalid_test", "useInvalidKey": false}'

# journalctl -u constellation | tail -n 100

# Create a state channel from valid data
curl -s -X POST http://$MAIN_NODE_ID:9000/csv/upload -H 'Content-type: application/json' -d '{"data": "asdf_valid_length_criteria3", "channelName": "test", "useInvalidKey": false}'

# journalctl -u constellation | tail -n 100

# Add some new data to existing state channel
curl -s -X POST http://$MAIN_NODE_ID:9000/csv/upload -H 'Content-type: application/json' -d '{"data": "asdf_valid_length_criteria4", "channelName": "test", "useInvalidKey": false}'

# View the data according to hash
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/data/4214d64a135515e2a109dbd52c8a29d4514aef5fc3320f32c8956eb63379092c

# Query latest data based on channel name
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/channel_name/test

# Versus invalid (which should have no signatures)
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/channel_name/invalid_test

# Query latest data based on channel id
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/channel_id/907f38f6bcbd4e93e37500deaf5fba93b262b636962d337cb70d187e86be4436

# Query an individual state channel message (to resolve backwards from prevHash)
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/message/08643be54d9691662f9953cd8a279d65ae25a95e9abdcb053e9cc52e3ee910bd

# Query again based on previous hash
curl -s -X GET http://$MAIN_NODE_ID:9000/csv/query/message/9e47f33a1b74aed6ee1e3302c0fa2b24762ebd0030db02e23dcd135c59811d50

# Add some new data corresponding to a CSV
curl -s -X POST http://$MAIN_NODE_ID:9000/csv/upload -H 'Content-type: application/json' -d '{"dataPath": "/home/admin/data1.csv", "channelName": "test", "useInvalidKey": false}'
