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

import org.tessellation.dag.l1.rosetta.model.dag.schema.GenericMetadata
import org.tessellation.dag.l1.rosetta.model.dag.schema.GenericMetadata

case class ConstructionDeriveResponse(
  /* [DEPRECATED by `account_identifier` in `v1.4.4`] Address in network-specific format. */
  address: Option[String],
  accountIdentifier: Option[AccountIdentifier],
  metadata: Option[GenericMetadata]
)
