package org.tessellation.dag.l1.rosetta.server

import org.tessellation.dag.l1.rosetta.Util
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model.{
  BlockEvent => RosettaBlockEvent,
  BlockIdentifier => RosettaBlockIdentifier
}

object RosettaSnapshotUtilities {

  def convertSnapshotsToBlockEvents[F[_]: KryoSerializer](
    gs: List[GlobalSnapshot],
    blockEventType: String
  ): Either[String, List[RosettaBlockEvent]] = {
    val value = gs.map(s => s.hash.map(h => List(h -> s)))
    val hashed = Util.reduceListEither(value).left.map(_.getMessage)
    hashed.map { ls =>
      ls.map {
        case (hash, s) =>
          RosettaBlockEvent(
            s.height.value.value,
            RosettaBlockIdentifier(s.height.value.value, hash.value),
            blockEventType
          )
      }
    }
  }

}
