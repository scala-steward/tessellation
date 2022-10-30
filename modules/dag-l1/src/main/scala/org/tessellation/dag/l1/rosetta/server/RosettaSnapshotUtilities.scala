package org.tessellation.dag.l1.rosetta.server

import cats.effect.Async
import org.tessellation.dag.l1.rosetta.Util
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model.{BlockEvent => RosettaBlockEvent, BlockIdentifier => RosettaBlockIdentifier}
import org.tessellation.security.SecurityProvider
import org.tessellation.ext.crypto._

object RosettaSnapshotUtilities {

  def convertSnapshotsToBlockEvents[F[_]: KryoSerializer: SecurityProvider: Async](
                                                                                    gs: List[GlobalSnapshot],
                                                                                    blockEventType: String
                                                                                  ): Either[String, List[RosettaBlockEvent]] = {
    val value = gs.map(s => s.hash.map(h => List(h -> s)))
    val hashed = Util.reduceListEither(value).left.map(_.getMessage)
    hashed.map { ls =>
      ls.map {
        case (hash, s) =>
          RosettaBlockEvent(s.height.value.value, RosettaBlockIdentifier(s.height.value.value, hash.value), blockEventType)
      }
    }
  }

}
