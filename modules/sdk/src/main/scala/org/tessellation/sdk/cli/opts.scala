package org.tessellation.sdk.cli

import org.tessellation.ext.decline.decline._

import com.monovore.decline.Opts
import fs2.io.file.Path

object opts {

  val genesisPathOpts: Opts[Path] = Opts.argument[Path]("genesis")

  val genesisSnapshotSalt: Opts[Long] = Opts.argument[Long]("genesis-salt")

  val trustRatingsPathOpts: Opts[Option[Path]] =
    Opts.option[Path]("ratings", "The path to the CSV of peer ID ratings").orNone
}
