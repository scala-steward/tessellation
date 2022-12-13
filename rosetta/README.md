Cleanup local data folder in event of test failure:
`rm -rf ./rosetta-data` 

`rosetta-cli check:construction --configuration-file rosetta.conf`

`rosetta-cli check:data --configuration-file rosetta.conf` 

https://github.com/coinbase/rosetta-ethereum/blob/master/rosetta-cli-conf/testnet/ethereum.ros

### Running tests from Docker ###

1. Modify the rosetta configuration file (i.e., `testnet/rosetta.conf`) to point to server or server instances (e.g.,
the instances started by `skaffold`.) If you are running `skaffold` locally and will be running the Docker image with
bridge networking, the server will be at: `http://host.docker.internal:9100/rosetta`. Make sure to set both `"online_url"` and
`"construction": {"offline_url": }`
2. From the same directory as the Dockerfile (i.e., `rosetta`), build the Docker image. You can run the build script
(i.e., `build.sh`) to do this.
3. Run the Docker image. You can easily run the image with bridge networking by running the `runConstructionTest.sh`
script.