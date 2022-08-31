/**
  * Rosetta
  * Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12
  * Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
  * https://openapi-generator.tech
  */

package org.tessellation.dag.l1.rosetta.model

case class TransactionIdentifier(
  /* Any transactions that are attributable only to a block (ex: a block event) should use the hash of the block as the identifier.  This should be normalized according to the case specified in the transaction_hash_case in network options. */
  hash: String
)
