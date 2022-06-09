package org.tessellation.tools

import cats.Applicative
import cats.effect.std.{Console, Random}
import cats.effect._
import cats.syntax.all._
import com.monovore.decline._
import com.monovore.decline.effect._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric._
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowEncoder
import fs2.io.file.{Files, Path}
import fs2.{Pipe, Pure, Stream, text}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.tessellation.BuildInfo
import org.tessellation.infrastructure.genesis.types.GenesisCSVAccount
import org.tessellation.keytool.{KeyPairGenerator, KeyStoreUtils}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.tools.TransactionGenerator._
import org.tessellation.tools.cli.method._

import java.nio.file.{Path => JPath}
import java.security.KeyPair
import scala.concurrent.duration._
import scala.math.Integral.Implicits._

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Tools",
      version = BuildInfo.version
    ) {

  /** Continuously sends transactions to a cluster
    *
    * @example {{{
    * send-transactions localhost:9100
    *  --loadWallets kubernetes/data/genesis-keys/
    * }}}
    *
    * @example {{{
    * send-transactions localhost:9100
    *  --generateWallets 100
    *  --genesisPath genesis.csv
    * }}}
    *
    * @return
    */
  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        KryoSerializer.forAsync[IO](sharedKryoRegistrar).use { implicit kryo =>
          EmberClientBuilder.default[IO].build.use { client =>
            Random.scalaUtilRandom[IO].flatMap { implicit random =>
              (method match {
                case SendTransactionsCmd(basicOpts, walletsOpts) =>
                  walletsOpts match {
                    case w: GeneratedWallets => sendTxsUsingGeneratedWallets(client, basicOpts, w)
                    case l: LoadedWallets    => sendTxsUsingLoadedWallets(client, basicOpts, l)
                  }
                case _ => IO.raiseError(new Throwable("Not implemented"))
              }).as(ExitCode.Success)
            }
          }
        }
      }
    }

  def sendTxsUsingGeneratedWallets[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: GeneratedWallets
  ): F[Unit] =
    console.green(s"Configuration: ") >>
      console.yellow(s"Wallets: ${walletsOpts.count.show}") >>
      console.yellow(s"Genesis path: ${walletsOpts.genesisPath.toString.show}") >>
      generateKeys[F](walletsOpts.count).flatMap { keys =>
        createGenesis(walletsOpts.genesisPath, keys) >>
          console.cyan("Genesis created. Please start the network and continue... [ENTER]") >>
          Console[F].readLine >>
          sendTransactions(client, basicOpts, keys.map(AddressParams(_)))
            .flatMap(_ => Async[F].sleep(10.seconds))
            .flatMap(_ => checkLastReferences(client, basicOpts.baseUrl, keys.map(_.getPublic.toAddress)))
      }

  def sendTxsUsingLoadedWallets[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: LoadedWallets
  ): F[Unit] =
    for {
      keys <- loadKeys(walletsOpts)
      _ <- console.green(s"Loaded ${keys.size} keys")
      addressParams <- keys.traverse { key =>
        getLastReference(client, basicOpts.baseUrl)(key.getPublic.toAddress)
          .map(lastTxRef => AddressParams(key, lastTxRef))
      }
      _ <- sendTransactions(client, basicOpts, addressParams)
      _ <- checkLastReferences(client, basicOpts.baseUrl, addressParams.map(_.address))
    } yield ()

  def sendTransactions[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    addressParams: List[AddressParams]
  ): F[Unit] =
    Clock[F].monotonic.flatMap { startTime =>
      infiniteTransactionStream(basicOpts.chunkSize, addressParams)
        .evalTap(postTransaction(client, basicOpts.baseUrl))
        .zipWithIndex
        .evalTap(printProgress[F](basicOpts.verbose, startTime))
        .through(applyLimit(basicOpts.take))
        .through(applyDelay(basicOpts.delay))
        .handleErrorWith(e => Stream.eval(console.red(e.toString) >> e.raiseError[F, Unit]))
        .attempts(exponentialBackoff(0.5.seconds, basicOpts.retryTimeout))
        .compile
        .drain
    }

  def exponentialBackoff(
    init: FiniteDuration,
    timeout: FiniteDuration,
    factor: NonNegDouble = 1.5
  ): Stream[Pure, FiniteDuration] =
    Stream
      .unfold((init, 0.seconds)) {
        case (step, acc) =>
          val nextStep = (step * factor).asInstanceOf[FiniteDuration]
          val nextAcc = acc + step
          Option.when(nextAcc <= timeout)((step, (nextStep, nextAcc)))
      }

  def applyLimit[F[_], A](maybeLimit: Option[PosLong]): Pipe[F, A, A] =
    in => maybeLimit.map(in.take(_)).getOrElse(in)

  def applyDelay[F[_]: Temporal, A](delay: Option[FiniteDuration]): Pipe[F, A, A] =
    in => delay.map(in.delayBy(_)).getOrElse(in)

  def loadKeys[F[_]: Files: Async: SecurityProvider](opts: LoadedWallets): F[List[KeyPair]] =
    Files[F]
      .walk(Path.fromNioPath(opts.walletsPath), 1, followLinks = false)
      .filter(_.extName === ".p12")
      .evalMap { keyFile =>
        KeyStoreUtils.readKeyPairFromStore(
          keyFile.toString,
          opts.alias,
          opts.password.toCharArray,
          opts.password.toCharArray
        )
      }
      .compile
      .toList

  def generateKeys[F[_]: Async: SecurityProvider](wallets: Int): F[List[KeyPair]] =
    (1 to wallets).toList.traverse { _ =>
      KeyPairGenerator.makeKeyPair[F]
    }

  def createGenesis[F[_]: Async](genesisPath: JPath, keys: List[KeyPair]): F[Unit] = {
    implicit val encoder: RowEncoder[GenesisCSVAccount] = deriveRowEncoder

    Stream
      .emits[F, KeyPair](keys)
      .map(_.getPublic.toAddress)
      .map(_.value.toString)
      .map(GenesisCSVAccount(_, 100000000L))
      .through(encodeWithoutHeaders[GenesisCSVAccount]())
      .through(text.utf8.encode)
      .through(Files[F].writeAll(Path.fromNioPath(genesisPath)))
      .compile
      .drain
  }

  def printProgress[F[_]: Async: Console](verbose: Boolean, startTime: FiniteDuration)(
    tuple: (Signed[Transaction], Long)
  ): F[Unit] =
    tuple match {
      case (tx, index) =>
        Applicative[F].whenA(verbose)(console.cyan(s"Transaction sent ordinal=${tx.ordinal} source=${tx.source}")) >>
          Applicative[F].whenA((index + 1) % 100 === 0) {
            Clock[F].monotonic.flatMap { currTime =>
              val (minutes, seconds) = (currTime - startTime).toSeconds /% 60
              console.green(s"${index + 1} transactions sent in ${minutes}m ${seconds}s")
            }
          }
    }

  def postTransaction[F[_]: Async](client: Client[F], baseUrl: UrlString)(
    tx: Signed[Transaction]
  ): F[Unit] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath("transaction")
    val req = Request[F](method = Method.POST, uri = target).withEntity(tx)

    client
      .successful(req)
      .void
  }

  def checkLastReferences[F[_]: Async: Console](
    client: Client[F],
    baseUrl: UrlString,
    addresses: List[Address]
  ): F[Unit] =
    addresses.traverse(checkLastReference(client, baseUrl)).void

  def checkLastReference[F[_]: Async: Console](client: Client[F], baseUrl: UrlString)(address: Address): F[Unit] =
    getLastReference(client, baseUrl)(address).flatMap { reference =>
      console.green(s"Reference for address: ${address} is ${reference.show}")
    }

  def getLastReference[F[_]: Async](client: Client[F], baseUrl: UrlString)(
    address: Address
  ): F[TransactionReference] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath(s"transaction/last-reference/${address.value.value}")
    val req = Request[F](method = Method.GET, uri = target)

    client.expect[TransactionReference](req)
  }

  object console {
    def red[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.RED}${t}${scala.Console.RESET}")
    def cyan[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.CYAN}${t}${scala.Console.RESET}")
    def green[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.GREEN}${t}${scala.Console.RESET}")
    def yellow[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.YELLOW}${t}${scala.Console.RESET}")
  }
}
