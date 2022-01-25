package org.tessellation.csv

import org.tesselation.crypto.Signed

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object CSVTypes {

  @derive(decoder, encoder, show)
  case class CreateChannelRequest(
    channelName: String,
    validationClass: String
  )

  @derive(decoder, encoder, show)
  case class CreateChannelResponse(channelName: String, channelId: String)

  @derive(decoder, encoder, show)
  case class UploadDataRequest(
    data: Option[String],
    dataPath: Option[String],
    channelName: Option[String] = None,
    channelId: Option[String] = None,
    useInvalidKey: Boolean = false
  )

  @derive(decoder, encoder, show)
  case class UploadDataResponse(messageHash: String, stateChannelData: StateChannelData)

  @derive(decoder, encoder, show)
  case class QueryDataRequest(messageHash: String)

  @derive(decoder, encoder, show)
  case class QueryDataResponse(signatures: List[String])

  @derive(decoder, encoder, show)
  case class CSVAppRequest(
    createChannelRequest: Option[CreateChannelRequest] = None,
    uploadDataRequest: Option[UploadDataRequest] = None,
    queryDataRequest: Option[QueryDataRequest] = None
    //QueryDataRequest
  )

  @derive(decoder, encoder, show)
  case class CSVAppResponse(
    createChannelResponse: Option[CreateChannelResponse] = None,
    uploadDataResponse: Option[UploadDataResponse] = None,
    queryDataResponse: Option[QueryDataResponse] = None
  )

  @derive(decoder, encoder, show)
  case class StateChannelData(
    dataHash: String,
    prevHash: String,
    channelId: String,
    data: String,
    signature: Signed[String],
    offset: Long = System.currentTimeMillis()
  )

  @derive(decoder, encoder, show)
  case class StateChannelSignature(signature: Signed[StateChannelData])

  @derive(decoder, encoder, show)
  case class ChannelData(
    message: StateChannelData,
    signatures: Set[StateChannelSignature]
  )

}
