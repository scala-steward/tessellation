package org.tessellation.dag.l1.rosetta.server.enums

object ErrorDescription extends Enumeration {
  type ErrorDescription = Value

  val UNKNOWN_INTERNAL_ERROR = Value(0, "Unable to determine the error type.")
  val UNSUPPORTED_NETWORK = Value(1, "Unable to route request to the specified network or the network is not yet supported.")
  val UNKNOWN_HASH = Value(2, "Unable to find a reference to the hash.")
  val INVALID_REQUEST = Value(3, "Unable to decode the request.")
  val ADDRESS_MALFORMED = Value(4, "The address is invalidly formatted or otherwise un-parseable.")
  val BLOCK_SERVICE_FAILURE = Value(5, "The request to the block service failed.")
  val UNKNOWN_ADDRESS = Value(6, "No known reference to the address.")
  val UNKNOWN_TRANSACTION = Value(7, "No known reference to the transaction.")
  val UNSUPPORTED_OPERATION = Value(8, "The operation is not supported.")
  val MALFORMED_TRANSACTION = Value(9, "Unable to decode the transaction.")
  val UNSUPPORTED_SIGNATURE_TYPE = Value(10, "Cannot translate the signature.")
  val HEX_DECODE_FAILURE = Value(11, "Unable to parse the hex into bytes.")
  val UNSUPPORTED_CURVE_TYPE = Value(12, "The curve type not available for use.")
  val L1_SERVICE_FAILURE = Value(13, "Request to the L1 service failed.")
  val DESERIALIZATION_FAILURE = Value(14, "Unable to deserialize the class to its kryo type.")
  val MALFORMED_REQUEST = Value(15, "The request is missing required information or necessary fields.")

}