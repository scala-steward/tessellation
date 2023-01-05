package org.tessellation.currency.l0.cli

import org.tessellation.currency.l0.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import fs2.io.file.Path

object snapshot {

  val snapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("data/snapshot"))

  val opts: Opts[SnapshotConfig] = snapshotPath.map(SnapshotConfig(2L, _, 10L))
}