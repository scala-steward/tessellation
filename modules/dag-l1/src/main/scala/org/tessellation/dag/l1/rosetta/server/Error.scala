package org.tessellation.dag.l1.rosetta.server

import cats.implicits.catsSyntaxTuple3Parallel
import org.tessellation.rosetta.server.model.{Error => RosettaError}
import org.tessellation.dag.l1.rosetta.server.enums.ErrorDescription.ErrorDescription
import org.tessellation.dag.l1.rosetta.server.enums.{ErrorDescription, ErrorMessage}
import org.tessellation.dag.l1.rosetta.server.enums.ErrorMessage.ErrorMessage
import org.tessellation.rosetta.server.model.dag.schema.{ErrorDetailKeyValue, ErrorDetails}

object Error {

  def getErrorMessageAndDescription(code: Int): (ErrorMessage, ErrorDescription) = {
    ErrorMessage.values.toList
    (ErrorMessage(code), ErrorDescription(code))
  }

  def getErrors(): List[RosettaError] = {
    val errorCodes: List[Int] = ErrorMessage.values.toList.map(_.id)
    val errorMessages: List[String] = ErrorMessage.values.toList.map(_.toString)
    val errorDescriptions: List[String] = ErrorDescription.values.toList.map(_.toString)

    (errorCodes, errorMessages, errorDescriptions).parTupled.map {
      case (code, message, description) => RosettaError(code, message, Some(description), retriable = true, None)
    }
  }

  def makeErrorCodeMsg(code: Int, message: String, retriable: Boolean = true): RosettaError =
    makeErrorCode(code, retriable, Some(ErrorDetails(List(ErrorDetailKeyValue("exception", message)))))

  def makeErrorCode(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): RosettaError = {
    val (message, description) = getErrorMessageAndDescription(code)

    RosettaError(
      code,
      message.toString,
      Some(description.toString),
      retriable,
      details
    )
  }

}
