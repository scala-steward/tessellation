FROM amazoncorretto:11
RUN yum update -y && yum upgrade -y
RUN yum install -y tar gzip
RUN mkdir -p /mnt/rosetta
COPY testnet/ /mnt/rosetta
RUN ls /mnt/rosetta
WORKDIR /mnt/rosetta
RUN curl -sSfL https://raw.githubusercontent.com/coinbase/rosetta-cli/master/scripts/install.sh | sh -s
CMD ["./bin/rosetta-cli", "check:construction", "--configuration-file", "./rosetta.conf"]
