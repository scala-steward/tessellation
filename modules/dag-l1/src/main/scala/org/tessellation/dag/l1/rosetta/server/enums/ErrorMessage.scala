package org.tessellation.dag.l1.rosetta.server.enums

object ErrorMessage extends Enumeration {
  type ErrorMessage = Value

  val UNKNOWN_INTERNAL_ERROR = Value(0, "Unknown internal error")
  val UNSUPPORTED_NETWORK = Value(1, "Unsupported network")
  val UNKNOWN_HASH = Value(2, "Unknown hash")
  val INVALID_REQUEST = Value(3, "Invalid request")
  val ADDRESS_MALFORMED = Value(4, "Malformed address")
  val BLOCK_SERVICE_FAILURE = Value(5, "Block service failure")
  val UNKNOWN_ADDRESS = Value(6, "Unknown address")
  val UNKNOWN_TRANSACTION = Value(7, "Unknown transaction")
  val UNSUPPORTED_OPERATION = Value(8, "Unsupported operation")
  val MALFORMED_TRANSACTION = Value(9, "Malformed transaction")
  val UNSUPPORTED_SIGNATURE_TYPE = Value(10, "Unsupported signature type")
  val HEX_DECODE_FAILURE = Value(11, "Hex decode failure")
  val UNSUPPORTED_CURVE_TYPE = Value(12, "Unsupported curve type")
  val L1_SERVICE_FAILURE = Value(13, "L1 service failure")
  val DESERIALIZATION_FAILURE = Value(14, "Deserialization failure")
  val MALFORMED_REQUEST = Value(15, "Malformed request")

}